package mihon.suwayomi.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validated provider settings. There are no defaults for the issuer or client id: they come from the user.
 *
 * [issuer] is kept exactly as entered (trimmed): the discovery document must repeat it character for character.
 */
data class OidcConfig(
    val issuer: String,
    val clientId: String,
    /** API resource, sent as `resource` and required in the `aud` of the signed JWT access token. */
    val audience: String,
    /** Scopes to request, `openid` always included. */
    val scopes: List<String>,
) {
    /** Scopes that must be granted. `offline_access` only controls refresh tokens, so it is not required. */
    val requiredScopes: List<String> get() = scopes.filter { it != OFFLINE_ACCESS }

    /** Permissions of the API: every requested scope that is not an OIDC standard one. Required in the token's `scope`. */
    val apiPermissions: List<String> get() = scopes.filter { it !in STANDARD_SCOPES }

    /** Tokens issued under a different key are not valid for this configuration. */
    val sessionKey: List<Any> get() = listOf(issuer, clientId, audience, apiPermissions.toSet())

    val scopeParameter: String get() = scopes.joinToString(" ")

    companion object {
        const val OPENID = "openid"
        const val OFFLINE_ACCESS = "offline_access"
        val STANDARD_SCOPES = setOf(OPENID, "profile", "email", "groups", OFFLINE_ACCESS)
        const val DEFAULT_SCOPES = "openid profile email groups"
        const val REDIRECT_URI = "app.mihon.suwayomi:/oauth2redirect"
    }
}

enum class OidcConfigField { Issuer, ClientId, Audience, Scopes }

sealed interface OidcConfigResult {
    data class Valid(val config: OidcConfig) : OidcConfigResult
    data class Invalid(val field: OidcConfigField) : OidcConfigResult
}

object OidcConfigValidator {

    fun validate(
        issuer: String,
        clientId: String,
        audience: String,
        scopes: String,
        offlineAccess: Boolean,
    ): OidcConfigResult {
        val issuerTrimmed = issuer.trim()
        if (parseIssuer(issuerTrimmed) == null) return OidcConfigResult.Invalid(OidcConfigField.Issuer)

        val client = clientId.trim()
        if (client.isEmpty() || client.length > 256 || client.any { it.isWhitespace() || it.isISOControl() }) {
            return OidcConfigResult.Invalid(OidcConfigField.ClientId)
        }

        val aud = audience.trim()
        if (aud.isEmpty() || aud.length > 512 || aud.any { it.isWhitespace() || it.isISOControl() }) {
            return OidcConfigResult.Invalid(OidcConfigField.Audience)
        }

        val tokens = scopes.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().toMutableList()
        // RFC 6749 scope-token: printable ASCII except space, double quote and backslash.
        if (tokens.any { t -> t.any { it.code !in 0x21..0x7E || it == '"' || it == '\\' } }) {
            return OidcConfigResult.Invalid(OidcConfigField.Scopes)
        }
        if (OidcConfig.OPENID !in tokens) return OidcConfigResult.Invalid(OidcConfigField.Scopes)
        // Without an API permission the access token proves nothing about Suwayomi access.
        if (tokens.none { it !in OidcConfig.STANDARD_SCOPES }) return OidcConfigResult.Invalid(OidcConfigField.Scopes)
        if (offlineAccess && OidcConfig.OFFLINE_ACCESS !in tokens) tokens += OidcConfig.OFFLINE_ACCESS

        return OidcConfigResult.Valid(OidcConfig(issuerTrimmed, client, aud, tokens))
    }

    /** https only, no credentials, query or fragment. */
    fun parseIssuer(issuer: String): HttpUrl? {
        val url = issuer.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (issuer.contains('?') || issuer.contains('#')) return null
        return url
    }
}

internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
