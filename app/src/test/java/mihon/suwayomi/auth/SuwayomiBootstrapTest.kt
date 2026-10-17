package mihon.suwayomi.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException

val DEFAULT_SCOPE_LIST = listOf("openid", "profile", "email", "groups", "offline_access", PERMISSION)

/** The document a server publishes; every part can be replaced to build a bad one. */
fun bootstrapDoc(
    server: String = AUDIENCE,
    issuer: String = ISSUER,
    clientId: String = CLIENT_ID,
    resource: String = AUDIENCE,
    scopes: List<String> = DEFAULT_SCOPE_LIST,
    redirect: String = OidcConfig.REDIRECT_URI,
    version: JsonElement = JsonPrimitive(1),
    type: String = "oidc",
    topExtra: Map<String, JsonElement> = emptyMap(),
    authExtra: Map<String, JsonElement> = emptyMap(),
): JsonObject = buildJsonObject {
    put("version", version)
    put("server", server)
    put(
        "authentication",
        buildJsonObject {
            put("type", type)
            put("issuer", issuer)
            put("client_id", clientId)
            put("resource", resource)
            putJsonArray("scopes") { scopes.forEach { add(JsonPrimitive(it)) } }
            put("redirect_uri", redirect)
            authExtra.forEach { (k, v) -> put(k, v) }
        },
    )
    topExtra.forEach { (k, v) -> put(k, v) }
}

/** Serves [body] for the well-known path of any host and counts the requests; other URLs get 404. */
class FakeBootstrapServer(var reply: () -> Response? = { null }) : Interceptor {
    val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
    var code = 200
    var body: String = bootstrapDoc().toString()
    var failNetwork = false
    val headers = mutableMapOf<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        urls += request.url.toString()
        if (failNetwork) throw IOException("down")
        reply()?.let { return it }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body(body.toResponseBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }
}

class SuwayomiBootstrapTest {

    private fun rejected(doc: JsonObject, base: String = AUDIENCE): OidcError =
        shouldThrow<OidcException> { SuwayomiBootstrap.parse(doc, base) }.error

    @Test
    fun `the deployed document is accepted and maps to the oidc settings`() {
        val doc = bootstrapDoc(
            server = "https://manga.aaaaa.news",
            issuer = "https://auth.aaaaa.news",
            clientId = "acea2a2b-1337-4ba0-a050-dfd573f1667a",
            resource = "https://manga.aaaaa.news",
            scopes = listOf("openid", "profile", "email", "groups", "offline_access", "suwayomi.access"),
        )
        val raw = SuwayomiBootstrap.parse(doc, "https://manga.aaaaa.news")
        raw.issuer shouldBe "https://auth.aaaaa.news"
        raw.clientId shouldBe "acea2a2b-1337-4ba0-a050-dfd573f1667a"
        raw.audience shouldBe "https://manga.aaaaa.news"
        raw.scopes shouldBe "openid profile email groups offline_access suwayomi.access"
        raw.offlineAccess shouldBe true
        OidcConfigValidator.validate(raw.issuer, raw.clientId, raw.audience, raw.scopes, raw.offlineAccess)
            .shouldBe(OidcConfigResult.Valid(OidcConfig(raw.issuer, raw.clientId, raw.audience, raw.scopes.split(' '))))
    }

    @Test
    fun `a trailing slash on the configured server is the same server`() {
        SuwayomiBootstrap.parse(bootstrapDoc(), "$AUDIENCE/").clientId shouldBe CLIENT_ID
    }

    @Test
    fun `a path prefix must match exactly`() {
        val base = "$AUDIENCE/manga"
        SuwayomiBootstrap.parse(bootstrapDoc(server = base, resource = AUDIENCE), base).audience shouldBe AUDIENCE
        rejected(bootstrapDoc(server = AUDIENCE), base) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(server = "$AUDIENCE/other"), base) shouldBe OidcError.BootstrapInvalid
    }

    @Test
    fun `unsupported versions are rejected`() {
        for (v in listOf(JsonPrimitive(2), JsonPrimitive(0), JsonPrimitive("1"), JsonPrimitive(1.5), JsonPrimitive(true))) {
            rejected(bootstrapDoc(version = v)) shouldBe OidcError.BootstrapInvalid
        }
    }

    @Test
    fun `another server origin is rejected`() {
        rejected(bootstrapDoc(server = "https://evil.test")) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(server = "http://suwayomi.test")) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(server = "https://suwayomi.test:8443")) shouldBe OidcError.BootstrapInvalid
    }

    @Test
    fun `secret and unknown members are rejected anywhere in the document`() {
        rejected(bootstrapDoc(topExtra = mapOf("client_secret" to JsonPrimitive("x")))) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(authExtra = mapOf("client_secret" to JsonPrimitive("x")))) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(authExtra = mapOf("api_key" to JsonPrimitive("x")))) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(topExtra = mapOf("extra" to JsonPrimitive(1)))) shouldBe OidcError.BootstrapInvalid
    }

    @Test
    fun `only the exact app redirect is accepted`() {
        for (r in listOf("https://suwayomi.test/cb", "app.mihon.suwayomi:/other", "app.mihon.suwayomi:/oauth2redirect/", "")) {
            rejected(bootstrapDoc(redirect = r)) shouldBe OidcError.BootstrapInvalid
        }
    }

    @Test
    fun `issuer and resource must be https`() {
        rejected(bootstrapDoc(issuer = "http://auth.test")) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(issuer = "https://user:pw@auth.test")) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(issuer = "https://auth.test/?x=1")) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(resource = "http://suwayomi.test")) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(resource = "https://other.test")) shouldBe OidcError.BootstrapInvalid
    }

    @Test
    fun `scopes need openid and an api permission`() {
        rejected(bootstrapDoc(scopes = listOf("profile", PERMISSION))) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(scopes = listOf("openid", "profile", "groups"))) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(scopes = emptyList())) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(scopes = listOf("openid", "bad scope"))) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(scopes = listOf("openid", PERMISSION, PERMISSION))) shouldBe OidcError.BootstrapInvalid
    }

    @Test
    fun `offline access follows the advertised scopes`() {
        val raw = SuwayomiBootstrap.parse(bootstrapDoc(scopes = listOf("openid", PERMISSION)), AUDIENCE)
        raw.offlineAccess shouldBe false
    }

    @Test
    fun `type and client id are required`() {
        rejected(bootstrapDoc(type = "oauth2-proxy")) shouldBe OidcError.BootstrapInvalid
        rejected(bootstrapDoc(clientId = "")) shouldBe OidcError.BootstrapInvalid
        rejected(JsonObject(bootstrapDoc().filterKeys { it != "authentication" })) shouldBe OidcError.BootstrapInvalid
    }

    // region fetch

    private val server = FakeBootstrapServer()
    private val client = SuwayomiBootstrapClient(OidcHttp(OkHttpClient.Builder().addInterceptor(server).build()))

    private fun fetchError(base: String = AUDIENCE): OidcError =
        shouldThrow<OidcException> { runBlocking { client.fetch(base) } }.error

    @Test
    fun `fetch reads the well known document of the exact server origin`() {
        val raw = runBlocking { client.fetch(AUDIENCE) }
        raw.clientId shouldBe CLIENT_ID
        server.urls.single() shouldBe "$AUDIENCE/.well-known/suwayomi-client"
    }

    @Test
    fun `fetch keeps a path prefix`() {
        server.body = bootstrapDoc(server = "$AUDIENCE/m").toString()
        runBlocking { client.fetch("$AUDIENCE/m") }
        server.urls.single() shouldBe "$AUDIENCE/m/.well-known/suwayomi-client"
    }

    @Test
    fun `plain http is never fetched`() {
        fetchError("http://suwayomi.test") shouldBe OidcError.BootstrapInvalid
        server.urls.size shouldBe 0
    }

    @Test
    fun `a missing document means the server does not support discovery`() {
        server.code = 404
        fetchError() shouldBe OidcError.BootstrapUnavailable
    }

    @Test
    fun `redirects are not followed`() {
        server.code = 302
        server.headers["Location"] = "https://evil.test/.well-known/suwayomi-client"
        fetchError() shouldBe OidcError.BootstrapUnavailable
        server.urls.size shouldBe 1
    }

    @Test
    fun `server errors and network failures are retryable errors`() {
        server.code = 503
        fetchError() shouldBe OidcError.Network
        server.code = 200
        server.failNetwork = true
        fetchError() shouldBe OidcError.Network
    }

    @Test
    fun `malformed and oversized bodies are rejected`() {
        server.body = "not json"
        fetchError() shouldBe OidcError.BootstrapInvalid
        server.body = "[1,2]"
        fetchError() shouldBe OidcError.BootstrapInvalid
        server.body = bootstrapDoc().toString().dropLast(1) + ",\"pad\":\"" + "x".repeat(20_000) + "\"}"
        fetchError() shouldBe OidcError.BootstrapInvalid
    }

    @Test
    fun `invalid content from a 200 reply is rejected`() {
        server.body = bootstrapDoc(version = JsonPrimitive(2)).toString()
        fetchError() shouldBe OidcError.BootstrapInvalid
        server.body = bootstrapDoc(server = "https://evil.test").toString()
        fetchError() shouldBe OidcError.BootstrapInvalid
    }

    // endregion
}
