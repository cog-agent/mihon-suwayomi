package mihon.suwayomi.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class SuwayomiAuthInterceptorTest {

    private val auth = FakeAuth()
    private val seen = mutableListOf<Request>()
    private var respond: (Request) -> Response = { ok(it) }

    private fun ok(request: Request, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

    private val client: OkHttpClient = SuwayomiAuthInterceptor.derive(
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    seen += chain.request()
                    respond(chain.request())
                },
            )
            .build(),
        SuwayomiAuthInterceptor(auth, { SERVER }, { ISSUER }),
    )

    private fun get(url: String, authorization: String? = null): Response = client.newCall(
        Request.Builder().url(url).apply { authorization?.let { header("Authorization", it) } }.build(),
    ).execute()

    @Test
    fun `the token is added on the exact server origin`() {
        get("$SERVER/api/graphql").use { it.code shouldBe 200 }
        seen.single().header("Authorization") shouldBe "Bearer fake-access-token-1"
    }

    @Test
    fun `a path prefix on the server url does not change the origin check`() {
        get("$SERVER/some/other/path.png").close()
        seen.single().header("Authorization") shouldBe "Bearer fake-access-token-1"
    }

    @Test
    fun `no token goes to other hosts ports or schemes`() {
        listOf(
            "https://other.test/a",
            "https://suwayomi.test:8443/a",
            "http://suwayomi.test/a",
            "https://suwayomi.test.evil.test/a",
            "https://evil.test/https://suwayomi.test/a",
        ).forEach { get(it).close() }
        seen.size shouldBe 5
        seen.all { it.header("Authorization") == null } shouldBe true
    }

    @Test
    fun `a caller supplied Authorization is stripped everywhere, including on the server`() {
        get("https://other.test/a", authorization = "Bearer fake-caller-token").close()
        seen.last().header("Authorization") shouldBe null
        get("$SERVER/a", authorization = "Bearer fake-caller-token").close()
        seen.last().header("Authorization") shouldBe "Bearer fake-access-token-1"
    }

    @Test
    fun `a redirect to another origin is reported as 401 and not followed`() {
        respond = { ok(it, 302, mapOf("Location" to "https://evil.test/steal")) }
        get("$SERVER/api/graphql").use { it.code shouldBe 401 }
        seen.size shouldBe 1
        seen.none { it.url.host == "evil.test" } shouldBe true
    }

    @Test
    fun `a redirect to the issuer login page is reported as 401`() {
        respond = { ok(it, 302, mapOf("Location" to "$ISSUER/login?next=x")) }
        get("$SERVER/api/graphql").use { it.code shouldBe 401 }
        seen.size shouldBe 1
    }

    @Test
    fun `a relative same-origin redirect is not followed either`() {
        respond = { ok(it, 307, mapOf("Location" to "/elsewhere")) }
        get("$SERVER/a").use { it.code shouldBe 307 }
        seen.size shouldBe 1
    }

    @Test
    fun `a 401 triggers exactly one refresh and one retry`() {
        var calls = 0
        respond = { if (++calls == 1) ok(it, 401) else ok(it) }
        get("$SERVER/a").use { it.code shouldBe 200 }
        auth.refreshes.get() shouldBe 1
        seen.map { it.header("Authorization") } shouldBe
            listOf("Bearer fake-access-token-1", "Bearer fake-access-token-2")
    }

    @Test
    fun `a second 401 is returned without more retries`() {
        respond = { ok(it, 401) }
        get("$SERVER/a").use { it.code shouldBe 401 }
        auth.refreshes.get() shouldBe 1
        seen.size shouldBe 2
    }

    @Test
    fun `off-origin 401 does not refresh`() {
        respond = { ok(it, 401) }
        get("https://other.test/a").use { it.code shouldBe 401 }
        auth.refreshes.get() shouldBe 0
    }

    @Test
    fun `not signed in fails before any network call`() {
        auth.token = null
        shouldThrow<NotSignedInException> { get("$SERVER/a") }
        seen.size shouldBe 0
    }

    @Test
    fun `a failed refresh after 401 surfaces as signed out`() {
        auth.refreshedToken = null
        respond = { ok(it, 401) }
        shouldThrow<NotSignedInException> { get("$SERVER/a") }
        seen.size shouldBe 1
    }

    @Test
    fun `the derived client never follows redirects`() {
        client.followRedirects shouldBe false
        client.followSslRedirects shouldBe false
    }

    @Test
    fun `a refresh that cannot reach the provider is a network error, not a sign-out`() {
        val offline = object : SuwayomiAuth by auth {
            override suspend fun accessToken(): TokenResult = TokenResult.Network
        }
        val c = SuwayomiAuthInterceptor.derive(
            OkHttpClient.Builder().addInterceptor(Interceptor { seen += it.request(); respond(it.request()) }).build(),
            SuwayomiAuthInterceptor(offline, { SERVER }),
        )
        shouldThrow<java.io.IOException> { c.newCall(Request.Builder().url("$SERVER/a").build()).execute() }
        seen.size shouldBe 0
        runBlocking { offline.accessToken() } shouldBe TokenResult.Network
    }

    private companion object {
        const val SERVER = "https://suwayomi.test"
    }

    private fun clientFor(server: String, vararg hops: (Request) -> Response): OkHttpClient {
        var i = 0
        return SuwayomiAuthInterceptor.derive(
            OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        seen += chain.request()
                        hops[minOf(i++, hops.lastIndex)](chain.request())
                    },
                )
                .build(),
            SuwayomiAuthInterceptor(auth, { server }, { ISSUER }),
        )
    }

    @Test
    fun `an http server origin gets no token and no token request`() {
        val http = "http://suwayomi.test"
        clientFor(http, { ok(it) }).newCall(Request.Builder().url("$http/api/graphql").build()).execute().close()
        seen.single().header("Authorization") shouldBe null
        auth.tokenRequests.get() shouldBe 0
    }

    @Test
    fun `a caller supplied Authorization is stripped on an http server`() {
        val http = "http://suwayomi.test"
        clientFor(http, { ok(it) }).newCall(
            Request.Builder().url("$http/a").header("Authorization", "Bearer fake-caller-token").build(),
        ).execute().close()
        seen.single().header("Authorization") shouldBe null
    }

    @Test
    fun `an https server redirecting to http on the same host is not followed with the token`() {
        respond = { ok(it, 301, mapOf("Location" to "http://suwayomi.test/a")) }
        get("$SERVER/a").use { it.code shouldBe 401 }
        seen.size shouldBe 1
        seen.none { it.url.scheme == "http" } shouldBe true
    }

    @Test
    fun `an http server redirecting to https gets no token on either hop`() {
        val http = "http://suwayomi.test"
        clientFor(http, { ok(it, 302, mapOf("Location" to "https://suwayomi.test/a")) }, { ok(it) })
            .newCall(Request.Builder().url("$http/a").build()).execute().use { it.code shouldBe 302 }
        seen.all { it.header("Authorization") == null } shouldBe true
        auth.tokenRequests.get() shouldBe 0
    }
}
