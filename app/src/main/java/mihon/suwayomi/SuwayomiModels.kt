package mihon.suwayomi

import mihon.graphql.suwayomi.fragment.SuwayomiCategoryRow
import mihon.graphql.suwayomi.fragment.SuwayomiChapterRow
import mihon.graphql.suwayomi.fragment.SuwayomiDownloadStatusFields
import mihon.graphql.suwayomi.fragment.SuwayomiExtensionRow
import mihon.graphql.suwayomi.fragment.SuwayomiLibraryUpdateFields
import mihon.graphql.suwayomi.fragment.SuwayomiMangaCard
import mihon.graphql.suwayomi.type.DownloaderState

data class SuwayomiSource(
    val id: String,
    val name: String,
    val lang: String,
    val iconUrl: String,
    val supportsLatest: Boolean,
)

data class SuwayomiManga(
    val id: Int,
    val sourceId: String,
    val title: String,
    val thumbnailUrl: String?,
    val inLibrary: Boolean,
    val unreadCount: Int,
    val downloadCount: Int,
    val categoryIds: List<Int> = emptyList(),
)

data class SuwayomiChapter(
    val id: Int,
    val mangaId: Int,
    val name: String,
    val chapterNumber: Double,
    val scanlator: String?,
    val uploadDate: Long,
    val sourceOrder: Int,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val isDownloaded: Boolean,
    val lastPageRead: Int,
    val pageCount: Int,
)

data class SuwayomiMangaDetails(
    val manga: SuwayomiManga,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: List<String>,
    val status: String,
    val chapters: List<SuwayomiChapter>,
)

data class SuwayomiCategory(val id: Int, val name: String, val order: Int, val isDefault: Boolean)

data class SuwayomiBrowsePage(val mangas: List<SuwayomiManga>, val hasNextPage: Boolean)

data class SuwayomiDownload(
    val chapterId: Int,
    val chapterName: String,
    val mangaId: Int,
    val mangaTitle: String,
    val state: String,
    val progress: Float,
)

data class SuwayomiDownloadStatus(val running: Boolean, val queue: List<SuwayomiDownload>)

data class SuwayomiExtension(
    val pkgName: String,
    val name: String,
    val lang: String,
    val versionName: String,
    val iconUrl: String,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
    val isObsolete: Boolean,
)

data class SuwayomiUpdateStatus(
    val isRunning: Boolean,
    val totalJobs: Int,
    val finishedJobs: Int,
    val skippedMangas: Int,
)

/** The pages of a chapter as absolute URLs, plus the progress the server last stored. */
data class SuwayomiChapterPages(val chapterId: Int, val pageUrls: List<String>, val lastPageRead: Int)

enum class SuwayomiBrowseType { POPULAR, LATEST, SEARCH }

internal fun SuwayomiMangaCard.toModel(categoryIds: List<Int> = emptyList()) = SuwayomiManga(
    id = id,
    sourceId = sourceId,
    title = title,
    thumbnailUrl = thumbnailUrl,
    inLibrary = inLibrary,
    unreadCount = unreadCount,
    downloadCount = downloadCount,
    categoryIds = categoryIds,
)

internal fun SuwayomiChapterRow.toModel() = SuwayomiChapter(
    id = id,
    mangaId = mangaId,
    name = name,
    chapterNumber = chapterNumber,
    scanlator = scanlator?.takeIf { it.isNotBlank() },
    uploadDate = uploadDate.toLongOrNull() ?: 0L,
    sourceOrder = sourceOrder,
    isRead = isRead,
    isBookmarked = isBookmarked,
    isDownloaded = isDownloaded,
    lastPageRead = lastPageRead,
    pageCount = pageCount,
)

internal fun SuwayomiCategoryRow.toModel() = SuwayomiCategory(
    id = id,
    name = name,
    order = order,
    isDefault = default,
)

internal fun SuwayomiExtensionRow.toModel() = SuwayomiExtension(
    pkgName = pkgName,
    name = name,
    lang = lang,
    versionName = versionName,
    iconUrl = iconUrl,
    isInstalled = isInstalled,
    hasUpdate = hasUpdate,
    isObsolete = isObsolete,
)

internal fun SuwayomiDownloadStatusFields.toModel() = SuwayomiDownloadStatus(
    running = state == DownloaderState.STARTED,
    queue = queue.map {
        SuwayomiDownload(
            chapterId = it.chapter.id,
            chapterName = it.chapter.name,
            mangaId = it.manga.id,
            mangaTitle = it.manga.title,
            state = it.state.rawValue,
            progress = it.progress,
        )
    },
)

internal fun SuwayomiLibraryUpdateFields.toModel() = SuwayomiUpdateStatus(
    isRunning = jobsInfo.isRunning,
    totalJobs = jobsInfo.totalJobs,
    finishedJobs = jobsInfo.finishedJobs,
    skippedMangas = jobsInfo.skippedMangasCount,
)
