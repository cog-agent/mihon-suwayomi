package mihon.suwayomi.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Who is signed in, read from the verified ID token. Never contains token text. */
data class OidcIdentity(val subject: String, val email: String?, val name: String?)

data class AuthState(
    val identity: OidcIdentity? = null,
    /** A browser sign-in was started and has not come back yet. */
    val signingIn: Boolean = false,
    val error: OidcError? = null,
) {
    val signedIn: Boolean get() = identity != null
}

sealed interface TokenResult {
    class Token(val value: String) : TokenResult {
        override fun toString() = "Token"
    }

    data object NotSignedIn : TokenResult
    data object Network : TokenResult
    data class Failed(val error: OidcError) : TokenResult
}

sealed interface SignInStart {
    data class Ready(val authorizeUrl: String) : SignInStart
    data class Failed(val error: OidcError) : SignInStart
}

sealed interface RedirectResult {
    /** Not for us, or no matching pending request. State was left untouched. */
    data class Ignored(val reason: OidcError) : RedirectResult
    data object SignedIn : RedirectResult
    data class Failed(val error: OidcError) : RedirectResult
}

data class SignOutResult(val endSessionUrl: String?)

/**
 * The one source of Suwayomi credentials. The GraphQL client, the image requests and the connection test all go
 * through it; the ID token never leaves the auth package.
 */
interface SuwayomiAuth {
    val state: StateFlow<AuthState>

    /** True when a session exists locally. No I/O. */
    fun isSignedIn(): Boolean

    /** The current access token if it is still valid, else null. No I/O and no refresh. */
    fun cachedAccessToken(): String?

    /** A valid access token, refreshing first if it expires within a minute. */
    suspend fun accessToken(): TokenResult

    /** Called after the server answered 401 to [rejected]: refreshes once (shared with concurrent callers). */
    suspend fun refreshAfterRejection(rejected: String): TokenResult
}

/**
 * OIDC authorization-code + PKCE public client. State lives in memory (pending request) and in one encrypted blob
 * (session). Nothing here holds an Activity.
 */
class OidcSession(
    private val prefs: OidcPreferences,
    httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : SuwayomiAuth {

    private class Pending(
        val state: String,
        val nonce: String,
        val verifier: String,
        val createdAtMs: Long,
        val config: OidcConfig,
        val metadata: ProviderMetadata,
    )

    private val http = OidcHttp(httpClient)
    private val discovery = OidcDiscovery(http)
    private val verifier = JwtVerifier({ clock() / 1000 })

    private val pendingLock = Any()
    private var pending: Pending? = null

    // Guards session writes against a refresh or exchange that started before a logout or a newer login.
    private val storeLock = Any()
    private var epoch = 0L

    private val refreshMutex = Mutex()

    private val cacheLock = Any()
    private var metadataCache: Pair<Long, ProviderMetadata>? = null
    private var jwksCache: Pair<String, List<Jwk>>? = null

    override val state: StateFlow<AuthState>
        field = MutableStateFlow(AuthState(identity = prefs.loadSession()?.identity()))

    private fun StoredSession.identity() = OidcIdentity(subject, email, name)

    private fun publish(error: OidcError? = null, signingIn: Boolean? = null) {
        val identity = prefs.loadSession()?.identity()
        val current = state.value
        state.value = AuthState(identity, signingIn ?: (current.signingIn && identity == null), error)
    }

    /** Re-reads the stored session; call when returning to the app. */
    fun syncState() = publish(error = state.value.error)

    // region Config

    /** Saves the settings; a changed issuer, client id, resource or API permission signs out and cancels any pending sign-in. */
    fun saveConfig(raw: OidcRawConfig): OidcConfigResult = synchronized(storeLock) {
        val before = prefs.config()
        val result = prefs.saveConfig(raw)
        if (result is OidcConfigResult.Valid) {
            if (before?.sessionKey != result.config.sessionKey) {
                epoch++
                synchronized(pendingLock) { pending = null }
                synchronized(cacheLock) {
                    metadataCache = null
                    jwksCache = null
                }
            }
            publish(signingIn = false)
        }
        result
    }

    /**
     * Drops the stored session and any pending sign-in without any network call, and invalidates in-flight refreshes.
     * Used when the server changes: the old server's credentials must be gone before the new one is contacted.
     */
    fun discardSession() = synchronized(storeLock) {
        epoch++
        synchronized(pendingLock) { pending = null }
        synchronized(cacheLock) {
            metadataCache = null
            jwksCache = null
        }
        prefs.clearSession()
        publish(signingIn = false)
    }

    /** Checks that [issuer] publishes valid OIDC discovery (exact issuer, S256, same-origin https endpoints). */
    suspend fun verifyProvider(issuer: String) {
        fetchMetadata(issuer, forceFresh = true)
    }

    // endregion

    // region Sign-in

    /** Starts a sign-in. A previous unfinished attempt is cancelled. */
    suspend fun beginSignIn(): SignInStart {
        val config = prefs.config() ?: return SignInStart.Failed(OidcError.NotConfigured)
        val meta = try {
            fetchMetadata(config.issuer, forceFresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcException) {
            publish(error = e.error, signingIn = false)
            return SignInStart.Failed(e.error)
        } catch (_: Exception) {
            publish(error = OidcError.DiscoveryFailed, signingIn = false)
            return SignInStart.Failed(OidcError.DiscoveryFailed)
        }

        val request = Pending(
            state = Pkce.randomToken(random),
            nonce = Pkce.randomToken(random),
            verifier = Pkce.randomToken(random),
            createdAtMs = clock(),
            config = config,
            metadata = meta,
        )
        val url = meta.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", OidcConfig.REDIRECT_URI)
            .addQueryParameter("scope", config.scopeParameter)
            .addQueryParameter("state", request.state)
            .addQueryParameter("nonce", request.nonce)
            .addQueryParameter("code_challenge", Pkce.challengeS256(request.verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("resource", config.audience)
            .build()
        synchronized(pendingLock) { pending = request }
        publish(signingIn = true)
        return SignInStart.Ready(url.toString())
    }

    /** Fire-and-forget entry for the redirect activity; runs on the app scope so it outlives the activity. */
    fun handleRedirectAsync(redirectUrl: String) {
        scope.launch { handleRedirect(redirectUrl) }
    }

    /**
     * Handles the redirect back from the browser. Idempotent: the pending request is consumed on first use, so a
     * replayed redirect finds nothing and is ignored.
     */
    suspend fun handleRedirect(redirectUrl: String): RedirectResult {
        val parsed = parseRedirect(redirectUrl) ?: return RedirectResult.Ignored(OidcError.NoPendingRequest)
        if (parsed.duplicateParameter) return RedirectResult.Ignored(OidcError.StateMismatch)
        val params = parsed.params
        val state = params["state"]

        val request: Pending
        synchronized(pendingLock) {
            val current = pending ?: return RedirectResult.Ignored(OidcError.NoPendingRequest)
            // A wrong or missing state may be a stray intent from another app: leave the real request alone.
            if (state == null || !MessageDigest.isEqual(current.state.toByteArray(), state.toByteArray())) {
                return RedirectResult.Ignored(OidcError.StateMismatch)
            }
            pending = null
            request = current
        }

        if (clock() - request.createdAtMs > REQUEST_TTL_MS) return fail(OidcError.RequestExpired)

        val providerError = params["error"]
        if (providerError != null) {
            return fail(if (providerError == "access_denied") OidcError.AccessDenied else OidcError.ProviderError)
        }
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return fail(OidcError.ProviderError)

        return try {
            completeSignIn(request, code)
            RedirectResult.SignedIn
        } catch (e: CancellationException) {
            publish(signingIn = false)
            throw e
        } catch (e: OidcException) {
            fail(e.error)
        } catch (_: Exception) {
            fail(OidcError.Network)
        }
    }

    private fun fail(error: OidcError): RedirectResult.Failed {
        publish(error = error, signingIn = false)
        return RedirectResult.Failed(error)
    }

    /** Null unless this is our redirect URI. Duplicate parameters are rejected. */
    internal class ParsedRedirect(val params: Map<String, String>, val duplicateParameter: Boolean)

    internal fun parseRedirect(redirectUrl: String): ParsedRedirect? {
        val uri = try {
            URI(redirectUrl)
        } catch (_: Exception) {
            return null
        }
        val expected = URI(OidcConfig.REDIRECT_URI)
        if (uri.scheme != expected.scheme || uri.host != null || uri.rawAuthority != null || uri.path != expected.path) {
            return null
        }
        val result = HashMap<String, String>()
        var duplicate = false
        for (pair in uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
            val name = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            if (result.put(name, value) != null) duplicate = true
        }
        return ParsedRedirect(result, duplicate)
    }

    private suspend fun completeSignIn(p: Pending, code: String) {
        val form = buildMap {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", OidcConfig.REDIRECT_URI)
            put("client_id", p.config.clientId)
            put("code_verifier", p.verifier)
            put("resource", p.config.audience)
        }
        val reply = http.postForm(p.metadata.tokenEndpoint, form)
        val body = reply.body
        if (reply.code !in 200..299 || body == null) throw OidcException(OidcError.TokenExchangeFailed)

        val tokens = parseTokenResponse(body)
        val idToken = tokens.idToken ?: throw OidcException(OidcError.InvalidToken)

        val id = withJwks(p.metadata.jwksUri) { keys ->
            verifier.verifySignature(idToken, keys).also {
                verifier.validateIdToken(it, p.config.issuer, p.config.clientId, p.nonce)
            }
        }
        val (accessExpiry, granted) = verifyAccessToken(tokens, p.config, p.metadata.jwksUri)

        val session = StoredSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accessExpiresAtMs = accessExpiry,
            issuer = p.config.issuer,
            clientId = p.config.clientId,
            audience = p.config.audience,
            scope = granted.joinToString(" "),
            subject = id.claims.string("sub")!!,
            email = id.claims.string("email"),
            name = id.claims.string("name"),
            idTokenExpiresAtSec = (id.claims["exp"] as? JsonPrimitive)?.longOrNull,
        )
        synchronized(storeLock) {
            // The settings may have changed while the browser was open.
            if (prefs.config()?.sessionKey != p.config.sessionKey) {
                throw OidcException(OidcError.InvalidConfig)
            }
            epoch++
            prefs.saveSession(session)
        }
        publish(signingIn = false)
    }

    private class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        val scope: String?,
        val expiresInSec: Long?,
    )

    private fun parseTokenResponse(body: JsonObject): TokenResponse {
        val access = body.string("access_token")?.takeIf { it.isNotEmpty() } ?: throw OidcException(OidcError.InvalidToken)
        if (!"Bearer".equals(body.string("token_type"), ignoreCase = true)) throw OidcException(OidcError.InvalidToken)
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        return TokenResponse(access, body.string("refresh_token")?.takeIf { it.isNotEmpty() }, body.string("id_token"), body.string("scope"), expiresIn)
    }

    /**
     * The access token must be a signed JWT for the configured resource carrying every required permission in its own
     * `scope` claim; anything else is rejected. Returns the expiry in epoch milliseconds and the granted scopes.
     */
    private suspend fun verifyAccessToken(tokens: TokenResponse, config: OidcConfig, jwksUri: String): Pair<Long, List<String>> {
        if (!verifier.looksLikeJwt(tokens.accessToken)) throw OidcException(OidcError.InvalidToken)
        val jwt = withJwks(jwksUri) { keys ->
            verifier.verifySignature(tokens.accessToken, keys).also {
                verifier.validateAccessToken(it, config.issuer, config.audience, config.apiPermissions)
            }
        }
        val exp = (jwt.claims["exp"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
            ?: throw OidcException(OidcError.InvalidToken)
        val byExpiresIn = tokens.expiresInSec?.let { clock() + it * 1000 }
        return (byExpiresIn?.let { minOf(it, exp) } ?: exp) to verifier.scopeClaim(jwt.claims).orEmpty()
    }

    // endregion

    // region Tokens

    override fun isSignedIn(): Boolean = prefs.loadSession() != null

    override fun cachedAccessToken(): String? =
        prefs.loadSession()?.takeIf { it.accessExpiresAtMs > clock() }?.accessToken

    override suspend fun accessToken(): TokenResult = obtain(rejected = null)

    override suspend fun refreshAfterRejection(rejected: String): TokenResult = obtain(rejected)

    private fun usable(s: StoredSession, rejected: String?): Boolean =
        s.accessToken != rejected && s.accessExpiresAtMs - clock() > REFRESH_MARGIN_MS

    private suspend fun obtain(rejected: String?): TokenResult {
        val first = prefs.loadSession() ?: return TokenResult.NotSignedIn
        if (usable(first, rejected)) return TokenResult.Token(first.accessToken)
        return refreshMutex.withLock {
            // Single flight: whoever got the lock first already refreshed for everyone waiting.
            val s = prefs.loadSession() ?: return@withLock TokenResult.NotSignedIn
            if (usable(s, rejected)) return@withLock TokenResult.Token(s.accessToken)
            // Once the exchange has started it runs to completion, so a rotated refresh token is never lost.
            withContext(NonCancellable) { refresh(s, rejected) }
        }
    }

    private suspend fun refresh(s: StoredSession, rejected: String?): TokenResult {
        val stillValid = rejected == null && s.accessExpiresAtMs > clock()
        val refreshToken = s.refreshToken
        if (refreshToken == null) {
            if (stillValid) return TokenResult.Token(s.accessToken)
            wipe(OidcError.InvalidGrant)
            return TokenResult.NotSignedIn
        }
        val startEpoch = synchronized(storeLock) { epoch }
        val config = prefs.config() ?: return TokenResult.NotSignedIn

        try {
            val meta = fetchMetadata(config.issuer)
            val form = buildMap {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("client_id", config.clientId)
                put("resource", config.audience)
            }
            val reply = http.postForm(meta.tokenEndpoint, form)
            val body = reply.body
            if (reply.code !in 200..299 || body == null) {
                return when {
                    reply.code in 400..401 && body?.string("error") == "invalid_grant" -> {
                        wipeIf(startEpoch, OidcError.InvalidGrant)
                        TokenResult.NotSignedIn
                    }
                    reply.code >= 500 || reply.code == 429 -> networkResult(s, stillValid)
                    else -> TokenResult.Failed(OidcError.TokenExchangeFailed)
                }
            }
            val tokens = parseTokenResponse(body)
            var email = s.email
            var name = s.name
            var idExp = s.idTokenExpiresAtSec
            tokens.idToken?.let { idToken ->
                val id = withJwks(meta.jwksUri) { keys ->
                    verifier.verifySignature(idToken, keys).also {
                        verifier.validateIdToken(it, config.issuer, config.clientId, nonce = null)
                    }
                }
                if (id.claims.string("sub") != s.subject) throw OidcException(OidcError.InvalidToken)
                email = id.claims.string("email")
                name = id.claims.string("name")
                idExp = (id.claims["exp"] as? JsonPrimitive)?.longOrNull
            }
            val (expiry, granted) = verifyAccessToken(tokens, config, meta.jwksUri)
            val updated = s.copy(
                accessToken = tokens.accessToken,
                // A rotated refresh token replaces the old one in the same write as the new access token.
                refreshToken = tokens.refreshToken ?: s.refreshToken,
                accessExpiresAtMs = expiry,
                scope = granted.joinToString(" "),
                email = email,
                name = name,
                idTokenExpiresAtSec = idExp,
            )
            val committed = synchronized(storeLock) {
                if (epoch != startEpoch) {
                    false
                } else {
                    prefs.saveSession(updated)
                    true
                }
            }
            // Signed out or re-configured while the request was in flight: the result is discarded.
            if (!committed) return TokenResult.NotSignedIn
            publish()
            return TokenResult.Token(updated.accessToken)
        } catch (e: OidcException) {
            return if (e.error == OidcError.Network || e.error == OidcError.DiscoveryFailed) {
                networkResult(s, stillValid)
            } else {
                TokenResult.Failed(e.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            return networkResult(s, stillValid)
        }
    }

    private fun networkResult(s: StoredSession, stillValid: Boolean): TokenResult =
        // The session is kept. A token that has not expired yet can still be used for this request.
        if (stillValid) TokenResult.Token(s.accessToken) else TokenResult.Network

    private fun wipe(error: OidcError?) = synchronized(storeLock) {
        epoch++
        prefs.clearSession()
        publish(error = error)
    }

    private fun wipeIf(expectedEpoch: Long, error: OidcError) = synchronized(storeLock) {
        if (epoch == expectedEpoch) {
            epoch++
            prefs.clearSession()
            publish(error = error)
        }
    }

    // endregion

    // region Sign-out

    /**
     * Wipes the local session first, then tries to revoke the refresh token. Revocation failures are ignored.
     * Returns the provider's end-session URL if it has one; the caller may open it in the browser. No
     * `id_token_hint` is sent because the ID token is not kept.
     */
    suspend fun signOut(): SignOutResult {
        val session = prefs.loadSession()
        val config = prefs.config()
        synchronized(pendingLock) { pending = null }
        wipe(null)
        publish(signingIn = false)
        if (session == null || config == null) return SignOutResult(null)

        val meta = try {
            fetchMetadata(config.issuer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SignOutResult(null)
        }
        val refreshToken = session.refreshToken
        val revocation = meta.revocationEndpoint
        if (refreshToken != null && revocation != null) {
            try {
                http.postForm(
                    revocation,
                    mapOf("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to config.clientId),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort only.
            }
        }
        val endSession = meta.endSessionEndpoint?.toHttpUrl()?.newBuilder()
            ?.addQueryParameter("client_id", config.clientId)?.build()?.toString()
        return SignOutResult(endSession)
    }

    // endregion

    // region Provider documents

    private suspend fun fetchMetadata(issuer: String, forceFresh: Boolean = false): ProviderMetadata {
        if (!forceFresh) {
            synchronized(cacheLock) {
                metadataCache?.takeIf { clock() - it.first < METADATA_TTL_MS && it.second.issuer == issuer }
                    ?.let { return it.second }
            }
        }
        val meta = try {
            discovery.fetch(issuer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcException) {
            throw e
        } catch (e: java.io.IOException) {
            throw OidcException(OidcError.Network, cause = e)
        }
        synchronized(cacheLock) { metadataCache = clock() to meta }
        return meta
    }

    private suspend fun <T> withJwks(jwksUri: String, block: (List<Jwk>) -> T): T {
        val keys = jwks(jwksUri, refetch = false)
        return try {
            block(keys)
        } catch (e: OidcException) {
            // Keys rotate: one refetch for an unknown kid, and no more.
            if (e.error != OidcError.UnknownSigningKey) throw e
            block(jwks(jwksUri, refetch = true))
        }
    }

    private suspend fun jwks(uri: String, refetch: Boolean): List<Jwk> {
        if (!refetch) synchronized(cacheLock) { jwksCache?.takeIf { it.first == uri }?.let { return it.second } }
        val keys = try {
            JwksParser.parse(http.getJson(uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcException) {
            throw e
        } catch (e: java.io.IOException) {
            throw OidcException(OidcError.Network, cause = e)
        }
        synchronized(cacheLock) { jwksCache = uri to keys }
        return keys
    }

    // endregion

    private companion object {
        const val REQUEST_TTL_MS = 10 * 60 * 1000L
        const val REFRESH_MARGIN_MS = 60 * 1000L
        const val METADATA_TTL_MS = 60 * 60 * 1000L
    }
}
