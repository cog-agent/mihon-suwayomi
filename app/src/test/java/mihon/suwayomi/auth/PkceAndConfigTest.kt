package mihon.suwayomi.auth

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class PkceAndConfigTest {

    @Test
    fun `S256 challenge matches the RFC 7636 appendix B vector`() {
        Pkce.challengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") shouldBe
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }

    @Test
    fun `random tokens are long url safe and distinct`() {
        val random = SecureRandom()
        val a = Pkce.randomToken(random)
        val b = Pkce.randomToken(random)
        (a.length >= 43) shouldBe true
        (a != b) shouldBe true
        a.all { it.isLetterOrDigit() || it == '-' || it == '_' } shouldBe true
    }

    private fun validate(
        issuer: String = ISSUER,
        client: String = CLIENT_ID,
        audience: String = "https://api.test",
        scopes: String = "openid profile email groups api:use",
        offline: Boolean = false,
    ) = OidcConfigValidator.validate(issuer, client, audience, scopes, offline)

    @Test
    fun `valid config keeps the issuer exactly as entered`() {
        val config = (validate(issuer = " https://auth.test/ ") as OidcConfigResult.Valid).config
        config.issuer shouldBe "https://auth.test/"
        config.scopes shouldContainExactly listOf("openid", "profile", "email", "groups", "api:use")
        config.audience shouldBe "https://api.test"
    }

    @Test
    fun `issuer must be https without credentials query or fragment`() {
        listOf(
            "http://auth.test", "", "auth.test", "https://user:pw@auth.test", "https://auth.test?x=1",
            "https://auth.test#f", "ftp://auth.test", "https://auth.test?",
        ).forEach {
            validate(issuer = it) shouldBe OidcConfigResult.Invalid(OidcConfigField.Issuer)
        }
    }

    @Test
    fun `client id audience and scopes are validated`() {
        validate(client = "") shouldBe OidcConfigResult.Invalid(OidcConfigField.ClientId)
        validate(client = "a b") shouldBe OidcConfigResult.Invalid(OidcConfigField.ClientId)
        validate(audience = "a b") shouldBe OidcConfigResult.Invalid(OidcConfigField.Audience)
        validate(scopes = "profile email api:use") shouldBe OidcConfigResult.Invalid(OidcConfigField.Scopes)
        validate(scopes = "openid \"x\"") shouldBe OidcConfigResult.Invalid(OidcConfigField.Scopes)
    }

    @Test
    fun `offline_access is added only when configured and is not a required scope`() {
        val without = (validate() as OidcConfigResult.Valid).config
        without.scopes.contains("offline_access") shouldBe false

        val with = (validate(offline = true) as OidcConfigResult.Valid).config
        with.scopes.contains("offline_access") shouldBe true
        with.requiredScopes.contains("offline_access") shouldBe false
        with.requiredScopes.contains("openid") shouldBe true
    }

    @Test
    fun `audience and an API permission are required`() {
        (validate(audience = " https://api.test ") as OidcConfigResult.Valid).config.audience shouldBe "https://api.test"
        validate(audience = " ") shouldBe OidcConfigResult.Invalid(OidcConfigField.Audience)
        validate(scopes = "openid profile email groups") shouldBe OidcConfigResult.Invalid(OidcConfigField.Scopes)
        validate(scopes = "openid offline_access") shouldBe OidcConfigResult.Invalid(OidcConfigField.Scopes)
        (validate() as OidcConfigResult.Valid).config.apiPermissions shouldContainExactly listOf("api:use")
    }
}
