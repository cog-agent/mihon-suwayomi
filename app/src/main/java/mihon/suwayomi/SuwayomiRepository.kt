package mihon.suwayomi

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.network.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import mihon.graphql.suwayomi.SuwayomiCategoriesQuery
import mihon.graphql.suwayomi.SuwayomiClearDownloaderMutation
import mihon.graphql.suwayomi.SuwayomiCreateCategoryMutation
import mihon.graphql.suwayomi.SuwayomiDequeueDownloadsMutation
import mihon.graphql.suwayomi.SuwayomiDownloadStatusQuery
import mihon.graphql.suwayomi.SuwayomiEnqueueDownloadsMutation
import mihon.graphql.suwayomi.SuwayomiExtensionsQuery
import mihon.graphql.suwayomi.SuwayomiFetchChapterPagesMutation
import mihon.graphql.suwayomi.SuwayomiFetchExtensionsMutation
import mihon.graphql.suwayomi.SuwayomiFetchMangaAndChaptersMutation
import mihon.graphql.suwayomi.SuwayomiFetchSourceMangaMutation
import mihon.graphql.suwayomi.SuwayomiLibraryQuery
import mihon.graphql.suwayomi.SuwayomiLibraryUpdateStatusQuery
import mihon.graphql.suwayomi.SuwayomiMangaDetailsQuery
import mihon.graphql.suwayomi.SuwayomiSetMangaInLibraryMutation
import mihon.graphql.suwayomi.SuwayomiSourcesQuery
import mihon.graphql.suwayomi.SuwayomiStartDownloaderMutation
import mihon.graphql.suwayomi.SuwayomiStopDownloaderMutation
import mihon.graphql.suwayomi.SuwayomiUpdateChaptersMutation
import mihon.graphql.suwayomi.SuwayomiUpdateExtensionMutation
import mihon.graphql.suwayomi.SuwayomiUpdateLibraryMutation
import mihon.graphql.suwayomi.SuwayomiUpdateMangaCategoriesMutation
import mihon.graphql.suwayomi.type.FetchSourceMangaType
import mihon.graphql.suwayomi.type.UpdateChapterPatchInput
import mihon.graphql.suwayomi.type.UpdateExtensionPatchInput
import mihon.graphql.suwayomi.type.UpdateMangaCategoriesPatchInput
import okhttp3.OkHttpClient

sealed interface SuwayomiError {
    data object NotConfigured : SuwayomiError
    data object Unauthorized : SuwayomiError
    data class Http(val statusCode: Int) : SuwayomiError
    data object Network : SuwayomiError

    /** The server answered with GraphQL errors; [message] is the server-provided text. */
    data class Server(val message: String) : SuwayomiError
    data object InvalidResponse : SuwayomiError
}

sealed interface SuwayomiResult<out T> {
    data class Ok<T>(val value: T) : SuwayomiResult<T>
    data class Err(val error: SuwayomiError) : SuwayomiResult<Nothing>
}

inline fun <T, R> SuwayomiResult<T>.map(transform: (T) -> R): SuwayomiResult<R> = when (this) {
    is SuwayomiResult.Ok -> SuwayomiResult.Ok(transform(value))
    is SuwayomiResult.Err -> this
}

fun <T> SuwayomiResult<T>.getOrNull(): T? = (this as? SuwayomiResult.Ok)?.value

/**
 * The single native client for a Suwayomi server. Every call reads the saved server settings, so a settings change is
 * picked up by the next request. Nothing here logs request or response bodies or the key.
 */
class SuwayomiRepository(
    private val httpClient: OkHttpClient,
    private val serverUrl: () -> String,
    private val apiKey: () -> String,
) {

    private data class Config(val baseUrl: String, val key: String)

    private var cached: Pair<Config, ApolloClient>? = null

    private fun config(): Config? {
        val baseUrl = SuwayomiServerUrl.normalize(serverUrl()) ?: return null
        return Config(baseUrl, apiKey().trim())
    }

    @Synchronized
    private fun apollo(config: Config): ApolloClient {
        cached?.takeIf { it.first == config }?.let { return it.second }
        cached?.second?.close()
        val client = ApolloClient.Builder()
            .serverUrl(SuwayomiServerUrl.graphQlEndpoint(config.baseUrl))
            .okHttpClient(httpClient)
            .apply { if (config.key.isNotEmpty()) addHttpHeader("Authorization", "Bearer ${config.key}") }
            .dispatcher(Dispatchers.IO)
            .httpExposeErrorBody(true)
            .build()
        cached = config to client
        return client
    }

    /** Headers an image request for a server-relative URL needs. */
    fun imageHeaders(): Map<String, String> {
        val key = config()?.key.orEmpty()
        return if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $key")
    }

    /** Turns a server-relative path such as a thumbnail or page URL into an absolute URL. */
    fun resolveUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = config()?.baseUrl ?: return null
        return base + (if (path.startsWith("/")) path else "/$path")
    }

    private suspend fun <D : Operation.Data, T> exec(
        call: (ApolloClient) -> ApolloCall<D>,
        map: (D) -> T,
    ): SuwayomiResult<T> {
        val config = config() ?: return SuwayomiResult.Err(SuwayomiError.NotConfigured)
        return try {
            val response = call(apollo(config)).execute()
            val data = response.data
            when (val exception = response.exception) {
                null -> when {
                    response.hasErrors() -> SuwayomiResult.Err(
                        SuwayomiError.Server(response.errors?.firstOrNull()?.message.orEmpty()),
                    )
                    data == null -> SuwayomiResult.Err(SuwayomiError.InvalidResponse)
                    else -> SuwayomiResult.Ok(map(data))
                }
                is ApolloHttpException -> {
                    exception.body?.close()
                    when (exception.statusCode) {
                        401, 403 -> SuwayomiResult.Err(SuwayomiError.Unauthorized)
                        else -> SuwayomiResult.Err(SuwayomiError.Http(exception.statusCode))
                    }
                }
                is ApolloNetworkException -> SuwayomiResult.Err(SuwayomiError.Network)
                else -> SuwayomiResult.Err(SuwayomiError.InvalidResponse)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NullPointerException) {
            // A nullable mutation payload came back empty.
            SuwayomiResult.Err(SuwayomiError.InvalidResponse)
        } catch (_: Exception) {
            SuwayomiResult.Err(SuwayomiError.Network)
        }
    }

    // region Browse

    suspend fun getSources(): SuwayomiResult<List<SuwayomiSource>> =
        exec({ it.query(SuwayomiSourcesQuery()) }) { data ->
            data.sources.nodes.map {
                SuwayomiSource(it.id, it.displayName, it.lang, it.iconUrl, it.supportsLatest)
            }
        }

    /** Fetches a page of a source. Note that the server also stores the returned manga in its database. */
    suspend fun browse(
        sourceId: String,
        type: SuwayomiBrowseType,
        page: Int,
        query: String? = null,
    ): SuwayomiResult<SuwayomiBrowsePage> {
        val gqlType = when (type) {
            SuwayomiBrowseType.POPULAR -> FetchSourceMangaType.POPULAR
            SuwayomiBrowseType.LATEST -> FetchSourceMangaType.LATEST
            SuwayomiBrowseType.SEARCH -> FetchSourceMangaType.SEARCH
        }
        val mutation = SuwayomiFetchSourceMangaMutation(
            source = sourceId,
            type = gqlType,
            page = page,
            query = Optional.presentIfNotNull(query?.takeIf { it.isNotBlank() }),
        )
        return exec({ it.mutation(mutation) }) { data ->
            SuwayomiBrowsePage(
                mangas = data.fetchSourceManga!!.mangas.map { it.suwayomiMangaCard.toModel() },
                hasNextPage = data.fetchSourceManga!!.hasNextPage,
            )
        }
    }

    // endregion

    // region Library and categories

    suspend fun getLibrary(): SuwayomiResult<List<SuwayomiManga>> =
        exec({ it.query(SuwayomiLibraryQuery()) }) { data ->
            data.mangas.nodes.map { node ->
                node.suwayomiMangaCard.toModel(node.categories.nodes.map { it.id })
            }
        }

    suspend fun getCategories(): SuwayomiResult<List<SuwayomiCategory>> =
        exec({ it.query(SuwayomiCategoriesQuery()) }) { data ->
            data.categories.nodes.map { it.suwayomiCategoryRow.toModel() }
        }

    suspend fun createCategory(name: String): SuwayomiResult<SuwayomiCategory> =
        exec({ it.mutation(SuwayomiCreateCategoryMutation(name)) }) { data ->
            data.createCategory!!.category.suwayomiCategoryRow.toModel()
        }

    suspend fun setInLibrary(mangaId: Int, inLibrary: Boolean): SuwayomiResult<SuwayomiManga> =
        exec({ it.mutation(SuwayomiSetMangaInLibraryMutation(mangaId, inLibrary)) }) { data ->
            data.updateManga!!.manga.suwayomiMangaCard.toModel()
        }

    suspend fun setMangaCategories(mangaId: Int, add: List<Int>, remove: List<Int>): SuwayomiResult<Unit> {
        val patch = UpdateMangaCategoriesPatchInput(
            addToCategories = Optional.presentIfNotNull(add.ifEmpty { null }),
            removeFromCategories = Optional.presentIfNotNull(remove.ifEmpty { null }),
        )
        return exec({ it.mutation(SuwayomiUpdateMangaCategoriesMutation(mangaId, patch)) }) { }
    }

    // endregion

    // region Details, chapters, reader, progress

    suspend fun getMangaDetails(mangaId: Int): SuwayomiResult<SuwayomiMangaDetails> =
        exec({ it.query(SuwayomiMangaDetailsQuery(mangaId)) }) { data ->
            val m = data.manga
            SuwayomiMangaDetails(
                manga = m.suwayomiMangaCard.toModel(m.categories.nodes.map { it.id }),
                author = m.author?.takeIf { it.isNotBlank() },
                artist = m.artist?.takeIf { it.isNotBlank() },
                description = m.description?.takeIf { it.isNotBlank() },
                genre = m.genre,
                status = m.status.rawValue,
                chapters = m.chapters.nodes.map { it.suwayomiChapterRow.toModel() },
            )
        }

    /** Asks the server to re-fetch the manga's chapter list from its source. */
    suspend fun refreshChapters(mangaId: Int, refreshManga: Boolean = false): SuwayomiResult<List<SuwayomiChapter>> =
        exec({ it.mutation(SuwayomiFetchMangaAndChaptersMutation(mangaId, refreshManga, true)) }) { data ->
            data.fetchMangaAndChapters!!.chapters.map { it.suwayomiChapterRow.toModel() }
        }

    suspend fun getChapterPages(chapterId: Int): SuwayomiResult<SuwayomiChapterPages> =
        exec({ it.mutation(SuwayomiFetchChapterPagesMutation(chapterId)) }) { data ->
            val payload = data.fetchChapterPages!!
            SuwayomiChapterPages(
                chapterId = chapterId,
                pageUrls = payload.pages.mapNotNull { resolveUrl(it) },
                lastPageRead = payload.chapter.lastPageRead,
            )
        }

    private suspend fun patchChapters(ids: List<Int>, patch: UpdateChapterPatchInput) =
        exec({ it.mutation(SuwayomiUpdateChaptersMutation(ids, patch)) }) { }

    suspend fun setChaptersRead(chapterIds: List<Int>, read: Boolean): SuwayomiResult<Unit> =
        patchChapters(chapterIds, UpdateChapterPatchInput(isRead = Optional.Present(read)))

    suspend fun setChaptersBookmarked(chapterIds: List<Int>, bookmarked: Boolean): SuwayomiResult<Unit> =
        patchChapters(chapterIds, UpdateChapterPatchInput(isBookmarked = Optional.Present(bookmarked)))

    /** Stores reading progress; reaching the last page marks the chapter read. */
    suspend fun saveProgress(chapterId: Int, pageIndex: Int, pageCount: Int): SuwayomiResult<Unit> {
        val finished = pageCount > 0 && pageIndex >= pageCount - 1
        return patchChapters(
            listOf(chapterId),
            UpdateChapterPatchInput(
                lastPageRead = Optional.Present(pageIndex.coerceAtLeast(0)),
                isRead = if (finished) Optional.Present(true) else Optional.Absent,
            ),
        )
    }

    // endregion

    // region Downloads

    suspend fun getDownloadStatus(): SuwayomiResult<SuwayomiDownloadStatus> =
        exec({ it.query(SuwayomiDownloadStatusQuery()) }) { it.downloadStatus.suwayomiDownloadStatusFields.toModel() }

    suspend fun enqueueDownloads(chapterIds: List<Int>): SuwayomiResult<SuwayomiDownloadStatus> =
        exec({ it.mutation(SuwayomiEnqueueDownloadsMutation(chapterIds)) }) {
            it.enqueueChapterDownloads!!.downloadStatus.suwayomiDownloadStatusFields.toModel()
        }

    suspend fun dequeueDownloads(chapterIds: List<Int>): SuwayomiResult<SuwayomiDownloadStatus> =
        exec({ it.mutation(SuwayomiDequeueDownloadsMutation(chapterIds)) }) {
            it.dequeueChapterDownloads!!.downloadStatus.suwayomiDownloadStatusFields.toModel()
        }

    suspend fun startDownloader(): SuwayomiResult<SuwayomiDownloadStatus> =
        exec({ it.mutation(SuwayomiStartDownloaderMutation()) }) {
            it.startDownloader!!.downloadStatus.suwayomiDownloadStatusFields.toModel()
        }

    suspend fun stopDownloader(): SuwayomiResult<SuwayomiDownloadStatus> =
        exec({ it.mutation(SuwayomiStopDownloaderMutation()) }) {
            it.stopDownloader!!.downloadStatus.suwayomiDownloadStatusFields.toModel()
        }

    suspend fun clearDownloadQueue(): SuwayomiResult<SuwayomiDownloadStatus> =
        exec({ it.mutation(SuwayomiClearDownloaderMutation()) }) {
            it.clearDownloader!!.downloadStatus.suwayomiDownloadStatusFields.toModel()
        }

    // endregion

    // region Extensions

    suspend fun getExtensions(): SuwayomiResult<List<SuwayomiExtension>> =
        exec({ it.query(SuwayomiExtensionsQuery()) }) { data ->
            data.extensions.nodes.map { it.suwayomiExtensionRow.toModel() }
        }

    /** Refreshes the extension list from the configured repositories. */
    suspend fun fetchExtensions(): SuwayomiResult<List<SuwayomiExtension>> =
        exec({ it.mutation(SuwayomiFetchExtensionsMutation()) }) { data ->
            data.fetchExtensions!!.extensions.map { it.suwayomiExtensionRow.toModel() }
        }

    private suspend fun patchExtension(pkgName: String, patch: UpdateExtensionPatchInput) =
        exec({ it.mutation(SuwayomiUpdateExtensionMutation(pkgName, patch)) }) { }

    suspend fun installExtension(pkgName: String) =
        patchExtension(pkgName, UpdateExtensionPatchInput(install = Optional.Present(true)))

    suspend fun updateExtension(pkgName: String) =
        patchExtension(pkgName, UpdateExtensionPatchInput(update = Optional.Present(true)))

    suspend fun uninstallExtension(pkgName: String) =
        patchExtension(pkgName, UpdateExtensionPatchInput(uninstall = Optional.Present(true)))

    // endregion

    // region Library updates

    suspend fun getLibraryUpdateStatus(): SuwayomiResult<SuwayomiUpdateStatus> =
        exec({ it.query(SuwayomiLibraryUpdateStatusQuery()) }) { it.libraryUpdateStatus.suwayomiLibraryUpdateFields.toModel() }

    /** Starts a server-side library update; [categoryIds] empty means every category. */
    suspend fun updateLibrary(categoryIds: List<Int> = emptyList()): SuwayomiResult<SuwayomiUpdateStatus> =
        exec({
            it.mutation(SuwayomiUpdateLibraryMutation(Optional.presentIfNotNull(categoryIds.ifEmpty { null })))
        }) { it.updateLibrary!!.updateStatus.suwayomiLibraryUpdateFields.toModel() }

    // endregion

    @Synchronized
    fun close() {
        cached?.second?.close()
        cached = null
    }
}
