package mihon.suwayomi.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

class OidcDiscoveryTest {

    private val provider = FakeProvider(TestKey.rsa("k1"))
    private val discovery = OidcDiscovery(OidcHttp(OkHttpClient.Builder().addInterceptor(provider).build()))

    private fun errorOf(issuer: String = ISSUER): OidcError =
        shouldThrow<OidcException> { runBlocking { discovery.fetch(issuer) } }.error

    private fun with(vararg changes: Pair<String, JsonPrimitive?>) {
        provider.discovery = JsonObject(
            provider.discovery.toMutableMap().apply {
                changes.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) }
            },
        )
    }

    @Test
    fun `valid document is accepted and read from the well known path`() {
        val meta = runBlocking { discovery.fetch(ISSUER) }
        meta.tokenEndpoint shouldBe "$ISSUER/api/oidc/token"
        provider.requests.single().url.toString() shouldBe "$ISSUER/.well-known/openid-configuration"
    }

    @Test
    fun `issuer must match exactly, including a trailing slash`() {
        with("issuer" to JsonPrimitive("$ISSUER/"))
        errorOf() shouldBe OidcError.DiscoveryIssuerMismatch
        with("issuer" to JsonPrimitive("https://evil.test"))
        errorOf() shouldBe OidcError.DiscoveryIssuerMismatch
        with("issuer" to JsonPrimitive(ISSUER))
        // The configured side is not normalised either.
        provider.discovery = JsonObject(provider.discovery + ("issuer" to JsonPrimitive(ISSUER)))
        errorOf("$ISSUER/") shouldBe OidcError.DiscoveryIssuerMismatch
    }

    @Test
    fun `required endpoints must be https`() {
        with("token_endpoint" to JsonPrimitive("http://auth.test/token"))
        errorOf() shouldBe OidcError.DiscoveryInsecure
    }

    @Test
    fun `required endpoints must be on the issuer origin`() {
        listOf("authorization_endpoint", "token_endpoint", "jwks_uri").forEach { name ->
            provider.discovery = provider.defaultDiscovery()
            with(name to JsonPrimitive("https://evil.test/x"))
            errorOf() shouldBe OidcError.DiscoveryInsecure
            provider.discovery = provider.defaultDiscovery()
            with(name to JsonPrimitive("https://auth.test:8443/x"))
            errorOf() shouldBe OidcError.DiscoveryInsecure
        }
    }

    @Test
    fun `missing endpoint fails`() {
        with("jwks_uri" to null)
        errorOf() shouldBe OidcError.DiscoveryFailed
    }

    @Test
    fun `S256 must be advertised`() {
        provider.discovery = JsonObject(
            provider.discovery + ("code_challenge_methods_supported" to JsonArray(listOf(JsonPrimitive("plain")))),
        )
        errorOf() shouldBe OidcError.PkceUnsupported
        provider.discovery = JsonObject(provider.discovery - "code_challenge_methods_supported")
        errorOf() shouldBe OidcError.PkceUnsupported
    }

    @Test
    fun `optional endpoints on another origin are dropped`() {
        with("revocation_endpoint" to JsonPrimitive("https://evil.test/revoke"))
        with("end_session_endpoint" to JsonPrimitive("http://auth.test/end"))
        val meta = runBlocking { discovery.fetch(ISSUER) }
        meta.revocationEndpoint shouldBe null
        meta.endSessionEndpoint shouldBe null
    }

    @Test
    fun `redirects are not followed`() {
        provider.discoveryReply = Reply(302, "", mapOf("Location" to "https://evil.test/.well-known/openid-configuration"))
        errorOf() shouldBe OidcError.DiscoveryFailed
        provider.requests.size shouldBe 1
    }

    @Test
    fun `non https issuer is rejected without a request`() {
        errorOf("http://auth.test") shouldBe OidcError.InvalidConfig
        provider.requests.size shouldBe 0
    }
}
