package mihon.suwayomi.auth

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** The request was not sent because there is no session. */
class NotSignedInException : IOException("not signed in")

/** A token could not be obtained for a reason other than being signed out or offline. */
class AuthFailedException : IOException("authentication failed")

/**
 * Applies the single auth policy to every Suwayomi request (GraphQL, images, downloads).
 *
 * - `Authorization: Bearer <access token>` is added only when the configured server is https and scheme, host and port
 *   equal it. For an http server (legacy, local) no token is even requested and nothing credential-bearing is sent.
 *   Any Authorization header on any other origin is removed.
 * - While signed in, the header carries the Pocket ID access token and nothing else: the reverse proxy in front of
 *   Suwayomi consumes that header, so the separate Suwayomi server API key is deliberately NOT sent.
 * - Must run on a client with redirects disabled. A 3xx to another origin, or to the issuer, is reported as 401 and
 *   never followed with the token.
 * - On 401 it forces at most one refresh and one retry.
 *
 * Blocks the calling (OkHttp) thread while a token is fetched; never call it from the main thread.
 */
class SuwayomiAuthInterceptor(
    private val auth: SuwayomiAuth,
    private val serverBaseUrl: () -> String?,
    private val issuer: () -> String? = { null },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().removeHeader("Authorization").build()
        val server = serverBaseUrl()?.toHttpUrlOrNull()
        if (server == null || !server.isHttps || !sameOrigin(request.url, server)) return chain.proceed(request)

        val token = token { auth.accessToken() }
        val first = send(chain, request, token, server)
        if (first.code != 401 || first.header(BLOCKED_REDIRECT) != null || request.body?.isOneShot() == true) return first
        first.close()
        val retryToken = token { auth.refreshAfterRejection(token) }
        return send(chain, request, retryToken, server)
    }

    private fun token(block: suspend () -> TokenResult): String = when (val result = runBlocking { block() }) {
        is TokenResult.Token -> result.value
        TokenResult.NotSignedIn -> throw NotSignedInException()
        TokenResult.Network -> throw IOException("could not refresh the session")
        is TokenResult.Failed -> throw AuthFailedException()
    }

    private fun send(chain: Interceptor.Chain, request: Request, token: String, server: HttpUrl): Response {
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        if (response.code !in 300..399 || response.code == 304) return response
        val location = response.header("Location")?.let { request.url.resolve(it) }
        val issuerPrefix = issuer()
        val leaves = location == null || !sameOrigin(location, server) ||
            (issuerPrefix != null && location.toString().startsWith(issuerPrefix))
        if (!leaves) return response
        response.close()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            // A proxy that redirects to a login page does not understand the token; refreshing will not change that.
            .header(BLOCKED_REDIRECT, "1")
            .body("".toResponseBody())
            .build()
    }

    companion object {
        private const val BLOCKED_REDIRECT = "X-Mihon-Blocked-Redirect"

        /** A client derived from [base] with redirects disabled and the auth interceptor first in the chain. */
        fun derive(base: OkHttpClient, interceptor: SuwayomiAuthInterceptor): OkHttpClient =
            base.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .apply { interceptors().add(0, interceptor) }
                .build()
    }
}
