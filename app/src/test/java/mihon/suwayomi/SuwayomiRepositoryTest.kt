package mihon.suwayomi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import mihon.suwayomi.auth.FakeAuth
import mihon.suwayomi.auth.SuwayomiAuth
import org.junit.jupiter.api.Test

/**
 * Runs the repository against canned responses. No network and no real server: every mutation here is only ever seen
 * by the fake interceptor, so no user library is touched.
 */
class SuwayomiRepositoryTest {

    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var responseCode = 200
    private var responseBody = "{}"

    private fun repository(
        url: String = "https://suwayomi.test:4567",
        auth: SuwayomiAuth = FakeAuth(),
    ): SuwayomiRepository {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message("test")
                        .body(responseBody.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return SuwayomiRepository(client, serverUrl = { url }, auth = auth)
    }

    private fun manga(id: Int, inLibrary: Boolean = false, extra: String = "") =
        """{"__typename":"MangaType","id":$id,"sourceId":"77","title":"Title $id","thumbnailUrl":"/api/v1/manga/$id/thumbnail",""" +
            """"inLibrary":$inLibrary,"unreadCount":3,"downloadCount":1$extra}"""

    private fun chapter(id: Int, order: Int, read: Boolean = false) =
        """{"__typename":"ChapterType","id":$id,"mangaId":1,"name":"Ch $id","chapterNumber":$id.0,"scanlator":"","uploadDate":"1700000000000",""" +
            """"sourceOrder":$order,"isRead":$read,"isBookmarked":false,"isDownloaded":false,"lastPageRead":2,"pageCount":10}"""

    @Test
    fun `unconfigured server fails without any request`() = runTest {
        val result = repository(url = "").getSources()

        result shouldBe SuwayomiResult.Err(SuwayomiError.NotConfigured)
        requests.size shouldBe 0
    }

    @Test
    fun `sources are parsed and requested from the graphql endpoint`() = runTest {
        responseBody = """{"data":{"sources":{"nodes":[
            {"__typename":"SourceType","id":"123","name":"Alpha","displayName":"Alpha (EN)","lang":"en","iconUrl":"/i/1","supportsLatest":true,"isConfigurable":false}
        ]}}}"""

        val result = repository().getSources()

        result shouldBe SuwayomiResult.Ok(listOf(SuwayomiSource("123", "Alpha (EN)", "en", "/i/1", true)))
        requests.single().url.toString() shouldBe "https://suwayomi.test:4567/api/graphql"
    }

    @Test
    fun `browse sends source type and page and parses the result`() = runTest {
        responseBody = """{"data":{"fetchSourceManga":{"__typename":"FetchSourceMangaPayload","hasNextPage":true,"mangas":[${manga(5)}]}}}"""

        val result = repository().browse("123", SuwayomiBrowseType.SEARCH, page = 2, query = "berserk")

        val page = (result as SuwayomiResult.Ok).value
        page.hasNextPage shouldBe true
        page.mangas.single().title shouldBe "Title 5"
        page.mangas.single().sourceId shouldBe "77"
        bodies.single() shouldContain "\"SEARCH\""
        bodies.single() shouldContain "\"page\":2"
        bodies.single() shouldContain "berserk"
    }

    @Test
    fun `blank search query is not sent`() = runTest {
        responseBody = """{"data":{"fetchSourceManga":{"__typename":"FetchSourceMangaPayload","hasNextPage":false,"mangas":[]}}}"""

        repository().browse("123", SuwayomiBrowseType.POPULAR, page = 1, query = "  ")

        bodies.single() shouldNotContain "\"query\":\"  \""
        bodies.single() shouldContain "\"POPULAR\""
    }

    @Test
    fun `library lists manga with their category ids`() = runTest {
        responseBody = """{"data":{"mangas":{"nodes":[${
            manga(1, true, ""","categories":{"nodes":[{"__typename":"CategoryType","id":4},{"__typename":"CategoryType","id":9}]}""")
        }]}}}"""

        val library = (repository().getLibrary() as SuwayomiResult.Ok).value

        library.single().categoryIds shouldBe listOf(4, 9)
        library.single().inLibrary shouldBe true
        library.single().unreadCount shouldBe 3
    }

    @Test
    fun `adding to library sends the inLibrary patch`() = runTest {
        responseBody = """{"data":{"updateManga":{"__typename":"UpdateMangaPayload","manga":${manga(7, true)}}}}"""

        val result = repository().setInLibrary(7, true)

        (result as SuwayomiResult.Ok).value.inLibrary shouldBe true
        bodies.single() shouldContain "\"id\":7"
        bodies.single() shouldContain "\"inLibrary\":true"
    }

    @Test
    fun `manga details carry chapters and metadata`() = runTest {
        responseBody = """{"data":{"manga":${
            manga(
                1,
                true,
                ""","author":"A","artist":"","description":"About","genre":["Action"],"status":"ONGOING","realUrl":null,""" +
                    """"categories":{"nodes":[]},"chapters":{"nodes":[${chapter(10, 2)},${chapter(11, 1)}]}""",
            )
        }}}"""

        val details = (repository().getMangaDetails(1) as SuwayomiResult.Ok).value

        details.author shouldBe "A"
        details.artist shouldBe null
        details.status shouldBe "ONGOING"
        details.genre shouldBe listOf("Action")
        details.chapters.map { it.id } shouldBe listOf(10, 11)
        details.chapters.first().uploadDate shouldBe 1_700_000_000_000L
        details.chapters.first().scanlator shouldBe null
    }

    @Test
    fun `chapter pages are resolved to absolute urls`() = runTest {
        responseBody = """{"data":{"fetchChapterPages":{"__typename":"FetchChapterPagesPayload",
            "pages":["/api/v1/manga/1/chapter/2/page/0","https://cdn.test/p1.jpg"],
            "chapter":{"__typename":"ChapterType","id":2,"pageCount":2,"lastPageRead":1}}}}"""

        val pages = (repository().getChapterPages(2) as SuwayomiResult.Ok).value

        pages.pageUrls shouldBe listOf("https://suwayomi.test:4567/api/v1/manga/1/chapter/2/page/0", "https://cdn.test/p1.jpg")
        pages.lastPageRead shouldBe 1
    }

    @Test
    fun `progress on the last page also marks the chapter read`() = runTest {
        responseBody = """{"data":{"updateChapters":{"__typename":"UpdateChaptersPayload","chapters":[]}}}"""

        repository().saveProgress(chapterId = 3, pageIndex = 9, pageCount = 10)

        bodies.single() shouldContain "\"lastPageRead\":9"
        bodies.single() shouldContain "\"isRead\":true"
    }

    @Test
    fun `progress mid chapter leaves the read flag alone`() = runTest {
        responseBody = """{"data":{"updateChapters":{"__typename":"UpdateChaptersPayload","chapters":[]}}}"""

        repository().saveProgress(chapterId = 3, pageIndex = 4, pageCount = 10)

        bodies.single() shouldContain "\"lastPageRead\":4"
        bodies.single() shouldNotContain "isRead\":"
    }

    @Test
    fun `category membership patch only sends the changed side`() = runTest {
        responseBody = """{"data":{"updateMangaCategories":{"__typename":"UpdateMangaCategoriesPayload","manga":{"__typename":"MangaType","id":1}}}}"""

        repository().setMangaCategories(1, add = listOf(5), remove = emptyList())

        bodies.single() shouldContain "\"addToCategories\":[5]"
        bodies.single() shouldNotContain "removeFromCategories\":"
    }

    @Test
    fun `download status maps queue and downloader state`() = runTest {
        responseBody = """{"data":{"downloadStatus":{"__typename":"DownloadStatus","state":"STARTED","queue":[
            {"__typename":"DownloadType","position":0,"progress":0.5,"state":"DOWNLOADING","tries":0,
             "chapter":{"__typename":"ChapterType","id":8,"name":"Ch 8"},
             "manga":{"__typename":"MangaType","id":1,"title":"Title"}}
        ]}}}"""

        val status = (repository().getDownloadStatus() as SuwayomiResult.Ok).value

        status.running shouldBe true
        status.queue.single() shouldBe
            SuwayomiDownload(8, "Ch 8", 1, "Title", "DOWNLOADING", 0.5f)
    }

    @Test
    fun `enqueue sends the chapter ids`() = runTest {
        responseBody = """{"data":{"enqueueChapterDownloads":{"__typename":"EnqueueChapterDownloadsPayload","downloadStatus":
            {"__typename":"DownloadStatus","state":"STOPPED","queue":[]}}}}"""

        val status = (repository().enqueueDownloads(listOf(1, 2)) as SuwayomiResult.Ok).value

        status.running shouldBe false
        bodies.single() shouldContain "\"ids\":[1,2]"
    }

    @Test
    fun `extensions install patch is sent for the package`() = runTest {
        responseBody = """{"data":{"updateExtension":{"__typename":"UpdateExtensionPayload","extension":null}}}"""

        val result = repository().installExtension("eu.kanade.tachiyomi.extension.en.test")

        result.shouldBeInstanceOf<SuwayomiResult.Ok<*>>()
        bodies.single() shouldContain "\"install\":true"
        bodies.single() shouldContain "eu.kanade.tachiyomi.extension.en.test"
    }

    @Test
    fun `library update status is mapped`() = runTest {
        responseBody = """{"data":{"libraryUpdateStatus":{"__typename":"LibraryUpdateStatus","jobsInfo":
            {"__typename":"UpdaterJobsInfoType","isRunning":true,"totalJobs":10,"finishedJobs":4,"skippedMangasCount":1,"skippedCategoriesCount":0}}}}"""

        val status = (repository().getLibraryUpdateStatus() as SuwayomiResult.Ok).value

        status shouldBe SuwayomiUpdateStatus(true, 10, 4, 1)
    }

    @Test
    fun `the pocket id access token is sent as the only bearer credential`() = runTest {
        responseBody = """{"data":{"sources":{"nodes":[]}}}"""

        repository(auth = FakeAuth(token = "fake-access-token-1")).getSources()

        requests.single().headers("Authorization") shouldBe listOf("Bearer fake-access-token-1")
    }

    @Test
    fun `not signed in is a typed error and sends nothing`() = runTest {
        val repo = repository(auth = FakeAuth(token = null))

        repo.getSources() shouldBe SuwayomiResult.Err(SuwayomiError.NotSignedIn)
        repo.browse("1", SuwayomiBrowseType.POPULAR, 1) shouldBe SuwayomiResult.Err(SuwayomiError.NotSignedIn)
        repo.enqueueDownloads(listOf(1)) shouldBe SuwayomiResult.Err(SuwayomiError.NotSignedIn)
        repo.getChapterPages(1) shouldBe SuwayomiResult.Err(SuwayomiError.NotSignedIn)
        requests.size shouldBe 0
    }

    @Test
    fun `a session that disappears mid-request is reported as not signed in`() = runTest {
        val auth = FakeAuth(token = "fake-access-token-1", refreshedToken = null)
        responseCode = 401

        repository(auth = auth).getSources() shouldBe SuwayomiResult.Err(SuwayomiError.NotSignedIn)
        requests.size shouldBe 1
    }

    @Test
    fun `only the https server origin gets the authorised call factory`() {
        val repo = repository()
        (repo.authorizedCallFactory("https://suwayomi.test:4567/a.png") != null) shouldBe true
        repo.authorizedCallFactory("https://other.test/a.png") shouldBe null
        repo.authorizedCallFactory("http://suwayomi.test:4567/a.png") shouldBe null
        repo.authorizedCallFactory(null) shouldBe null
    }

    @Test
    fun `an http server never gets a call factory token request or credential`() = runTest {
        val auth = FakeAuth()
        val repo = repository(url = "http://suwayomi.test:4567", auth = auth)
        repo.authorizedCallFactory("http://suwayomi.test:4567/a.png") shouldBe null

        repo.getSources()

        requests.single().header("Authorization") shouldBe null
        auth.tokenRequests.get() shouldBe 0
    }

    @Test
    fun `an http server works without a session and still carries no credentials`() = runTest {
        val auth = FakeAuth(token = null)
        repository(url = "http://suwayomi.test:4567", auth = auth).getSources()

        requests.single().header("Authorization") shouldBe null
        auth.tokenRequests.get() shouldBe 0
    }

    @Test
    fun `401 is reported as unauthorized`() = runTest {
        responseCode = 401

        repository().getSources() shouldBe SuwayomiResult.Err(SuwayomiError.Unauthorized)
    }

    @Test
    fun `graphql errors surface the server message`() = runTest {
        responseBody = """{"data":null,"errors":[{"message":"Source not found"}]}"""

        repository().browse("1", SuwayomiBrowseType.POPULAR, 1) shouldBe
            SuwayomiResult.Err(SuwayomiError.Server("Source not found"))
    }

    @Test
    fun `a null mutation payload is an invalid response`() = runTest {
        responseBody = """{"data":{"fetchSourceManga":null}}"""

        repository().browse("1", SuwayomiBrowseType.POPULAR, 1) shouldBe
            SuwayomiResult.Err(SuwayomiError.InvalidResponse)
    }

    @Test
    fun `resolveUrl and image call factory follow the saved settings`() {
        val repo = repository(url = "https://example.test/suwayomi/", auth = FakeAuth(token = "k"))

        repo.resolveUrl("/api/v1/manga/1/thumbnail") shouldBe "https://example.test/suwayomi/api/v1/manga/1/thumbnail"
        repo.resolveUrl("https://other.test/a.png") shouldBe "https://other.test/a.png"
        repo.resolveUrl(null) shouldBe null
        val own = "https://example.test/suwayomi/api/v1/manga/1/thumbnail"
        (repo.authorizedCallFactory(own) != null) shouldBe true
        repo.authorizedCallFactory("https://example.test.evil.test/a.png") shouldBe null
        repo.authorizedCallFactory("http://example.test/suwayomi/a.png") shouldBe null
    }
}
