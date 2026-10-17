package mihon.suwayomi.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.suwayomi.SuwayomiServerUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Client bootstrap: the public, non-secret client registration a Suwayomi server publishes at
 * `<server>/.well-known/suwayomi-client`. Standard OIDC discovery cannot supply the public client id, the API resource
 * or the API permission, so this document is what lets the user enter only the server address.
 *
 * ```
 * {"version":1,"server":"https://host","authentication":{"type":"oidc","issuer":"https://idp",
 *  "client_id":"…","resource":"https://host","scopes":["openid",…,"api.permission"],
 *  "redirect_uri":"app.mihon.suwayomi:/oauth2redirect"}}
 * ```
 *
 * Parsing is strict on purpose: unknown members (which is where a secret would be), a wrong version, another origin,
 * another redirect URI or a scope set without an API permission all reject the whole document.
 */
object SuwayomiBootstrap {

    const val PATH = "/.well-known/suwayomi-client"
    const val VERSION = 1
    const val MAX_BYTES = 16L * 1024

    private val TOP_KEYS = setOf("version", "server", "authentication")
    private val AUTH_KEYS = setOf("type", "issuer", "client_id", "resource", "scopes", "redirect_uri")

    /** The document URL for [serverBase] (a normalized https base, a path prefix is kept). */
    fun documentUrl(serverBase: String): String = serverBase.trimEnd('/') + PATH

    /**
     * @param serverBase the configured server, normalized (see [SuwayomiServerUrl.normalize]).
     * @return the validated settings, ready to be saved.
     * @throws OidcException [OidcError.BootstrapInvalid] when anything is off.
     */
    fun parse(doc: JsonObject, serverBase: String): OidcRawConfig {
        val server = serverBase.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: invalid()
        if (!doc.keys.all { it in TOP_KEYS }) invalid()

        val version = (doc["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull
        if (version != VERSION.toString()) invalid()

        // The advertised server must be exactly the configured one: same origin and the same path prefix.
        val advertised = doc.string("server") ?: invalid()
        if (SuwayomiServerUrl.normalize(advertised) != serverBase.trimEnd('/')) invalid()

        val auth = doc["authentication"] as? JsonObject ?: invalid()
        if (!auth.keys.all { it in AUTH_KEYS }) invalid()
        if (auth.string("type") != "oidc") invalid()
        if (auth.string("redirect_uri") != OidcConfig.REDIRECT_URI) invalid()

        val resource = auth.string("resource") ?: invalid()
        val resourceUrl = resource.toHttpUrlOrNull() ?: invalid()
        if (!resourceUrl.isHttps || resourceUrl.username.isNotEmpty() || resourceUrl.password.isNotEmpty()) invalid()
        if (resourceUrl.query != null || resourceUrl.fragment != null) invalid()
        // A token for another host's API would be useless here and sending the user to sign in for it is a trap.
        if (!sameOrigin(resourceUrl, server)) invalid()

        val scopes = (auth["scopes"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() }
            ?: invalid()
        if (scopes.size > MAX_SCOPES || scopes.toSet().size != scopes.size) invalid()
        // Each member is one RFC 6749 scope-token; a member with a space would otherwise be split into several scopes.
        if (scopes.any { it.isEmpty() || it.any { c -> c.code !in 0x21..0x7E || c == '"' || c == '\\' } }) invalid()

        val raw = OidcRawConfig(
            issuer = auth.string("issuer") ?: invalid(),
            clientId = auth.string("client_id") ?: invalid(),
            audience = resource,
            scopes = scopes.joinToString(" "),
            offlineAccess = OidcConfig.OFFLINE_ACCESS in scopes,
        )
        // Shared rules: https issuer without credentials, openid present, at least one API permission, token syntax.
        if (OidcConfigValidator.validate(raw.issuer, raw.clientId, raw.audience, raw.scopes, raw.offlineAccess)
            !is OidcConfigResult.Valid
        ) {
            invalid()
        }
        return raw
    }

    private const val MAX_SCOPES = 32

    private fun invalid(): Nothing = throw OidcException(OidcError.BootstrapInvalid)
}

internal class SuwayomiBootstrapClient(private val http: OidcHttp) {

    /** Fetches and validates the document. No redirect is followed and plain http is refused. */
    suspend fun fetch(serverBase: String): OidcRawConfig {
        val url: HttpUrl = SuwayomiBootstrap.documentUrl(serverBase).toHttpUrlOrNull()
            ?: throw OidcException(OidcError.BootstrapInvalid)
        if (!url.isHttps) throw OidcException(OidcError.BootstrapInvalid)
        val reply = try {
            http.getJsonReply(url.toString(), SuwayomiBootstrap.MAX_BYTES)
        } catch (e: OidcException) {
            // Oversize bodies surface as ProviderError from the shared transport.
            throw if (e.error == OidcError.ProviderError) OidcException(OidcError.BootstrapInvalid) else e
        } catch (e: java.io.IOException) {
            throw OidcException(OidcError.Network, cause = e)
        }
        return when {
            reply.code == 404 || reply.code in 300..399 -> throw OidcException(OidcError.BootstrapUnavailable)
            reply.code !in 200..299 -> throw OidcException(OidcError.Network)
            else -> SuwayomiBootstrap.parse(reply.body ?: throw OidcException(OidcError.BootstrapInvalid), serverBase)
        }
    }
}
