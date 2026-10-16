package mihon.suwayomi.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ProviderMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    val revocationEndpoint: String?,
    val endSessionEndpoint: String?,
)

internal class OidcDiscovery(private val http: OidcHttp) {

    suspend fun fetch(issuer: String): ProviderMetadata {
        val issuerUrl = OidcConfigValidator.parseIssuer(issuer) ?: throw OidcException(OidcError.InvalidConfig)
        val doc = http.getJson(issuer.trimEnd('/') + "/.well-known/openid-configuration")
        return parse(doc, issuer, issuerUrl)
    }

    internal fun parse(doc: JsonObject, issuer: String, issuerUrl: HttpUrl): ProviderMetadata {
        // Exact string comparison on purpose: no trailing-slash or case normalisation.
        if (doc.string("issuer") != issuer) throw OidcException(OidcError.DiscoveryIssuerMismatch)

        val methods = (doc["code_challenge_methods_supported"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods == null || "S256" !in methods) throw OidcException(OidcError.PkceUnsupported)

        fun required(name: String): String {
            val value = doc.string(name) ?: throw OidcException(OidcError.DiscoveryFailed)
            return value.takeIf { sameOriginHttps(it, issuerUrl) } ?: throw OidcException(OidcError.DiscoveryInsecure)
        }

        // Optional endpoints that fail the origin rule are dropped instead of failing the whole sign-in.
        fun optional(name: String): String? = doc.string(name)?.takeIf { sameOriginHttps(it, issuerUrl) }

        return ProviderMetadata(
            issuer = issuer,
            authorizationEndpoint = required("authorization_endpoint"),
            tokenEndpoint = required("token_endpoint"),
            jwksUri = required("jwks_uri"),
            revocationEndpoint = optional("revocation_endpoint"),
            endSessionEndpoint = optional("end_session_endpoint"),
        )
    }

    private fun sameOriginHttps(value: String, issuerUrl: HttpUrl): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.username.isEmpty() && url.password.isEmpty() && sameOrigin(url, issuerUrl)
    }
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
