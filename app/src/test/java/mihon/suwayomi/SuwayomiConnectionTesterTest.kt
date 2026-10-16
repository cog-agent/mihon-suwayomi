package mihon.suwayomi

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import mihon.suwayomi.auth.FakeAuth
import org.junit.jupiter.api.Test
import java.io.IOException

class SuwayomiConnectionTesterTest {

    private val requests = mutableListOf<Request>()
    private var auth = FakeAuth()

    private fun tester(respond: (Request) -> Response): SuwayomiConnectionTester {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += chain.request()
                    respond(chain.request())
                },
            )
            .build()
        return SuwayomiConnectionTester(client, auth)
    }

    private fun Request.reply(code: Int, body: String): Response = Response.Builder()
        .request(this)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    @Test
    fun `successful about query returns server name and version`() = runTest {
        val tester = tester { it.reply(200, ABOUT_OK) }

        tester.test("https://suwayomi.test:4567/") shouldBe
            SuwayomiConnectionResult.Success("Suwayomi-Server", "v2.4.2366")

        requests.single().url.toString() shouldBe "https://suwayomi.test:4567/api/graphql"
        requests.single().method shouldBe "POST"
    }

    @Test
    fun `reverse proxy path prefix is preserved`() = runTest {
        tester { it.reply(200, ABOUT_OK) }.test("https://example.test/suwayomi")

        requests.single().url.toString() shouldBe "https://example.test/suwayomi/api/graphql"
    }

    @Test
    fun `the Pocket ID access token is sent as bearer token`() = runTest {
        tester { it.reply(200, ABOUT_OK) }.test("https://suwayomi.test:4567")

        requests.single().header("Authorization") shouldBe "Bearer fake-access-token-1"
    }

    @Test
    fun `an http server is tested without any token or token request`() = runTest {
        tester { it.reply(200, ABOUT_OK) }.test("http://suwayomi.test:4567")

        requests.single().header("Authorization") shouldBe null
        auth.tokenRequests.get() shouldBe 0
    }

    @Test
    fun `nothing is sent while signed out`() = runTest {
        auth = FakeAuth(token = null)
        tester { it.reply(200, ABOUT_OK) }.test("https://suwayomi.test:4567") shouldBe SuwayomiConnectionResult.NotSignedIn

        requests.size shouldBe 0
    }

    @Test
    fun `401 and 403 are reported as unauthorized`() = runTest {
        for (code in listOf(401, 403)) {
            tester { it.reply(code, "{}") }.test("https://suwayomi.test:4567") shouldBe
                SuwayomiConnectionResult.Unauthorized
        }
    }

    @Test
    fun `other http failures keep the status code`() = runTest {
        tester { it.reply(502, "bad gateway") }.test("https://suwayomi.test:4567") shouldBe
            SuwayomiConnectionResult.HttpError(502)
    }

    @Test
    fun `io failure is unreachable`() = runTest {
        tester { throw IOException("connection refused") }.test("https://suwayomi.test:4567") shouldBe
            SuwayomiConnectionResult.Unreachable
    }

    @Test
    fun `non graphql body is an invalid response`() = runTest {
        tester { it.reply(200, "<html>nope</html>") }.test("https://suwayomi.test:4567") shouldBe
            SuwayomiConnectionResult.InvalidResponse
    }

    @Test
    fun `graphql errors are an invalid response`() = runTest {
        tester { it.reply(200, """{"errors":[{"message":"nope"}],"data":null}""") }
            .test("https://suwayomi.test:4567") shouldBe SuwayomiConnectionResult.InvalidResponse
    }

    @Test
    fun `invalid url never hits the network`() = runTest {
        tester { it.reply(200, ABOUT_OK) }.test("not a url") shouldBe SuwayomiConnectionResult.InvalidUrl
        requests.size shouldBe 0
    }

    private companion object {
        const val ABOUT_OK =
            """{"data":{"aboutServer":{"name":"Suwayomi-Server","version":"v2.4.2366","buildType":"Stable"}}}"""
    }
}
