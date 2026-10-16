package mihon.suwayomi

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.network.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import mihon.graphql.suwayomi.SuwayomiAboutServerQuery
import mihon.suwayomi.auth.NotSignedInException
import mihon.suwayomi.auth.SuwayomiAuth
import mihon.suwayomi.auth.SuwayomiAuthInterceptor
import okhttp3.OkHttpClient

sealed interface SuwayomiConnectionResult {
    data class Success(val serverName: String, val version: String) : SuwayomiConnectionResult

    sealed interface Failure : SuwayomiConnectionResult

    /** The server address is empty or isn't a plain http(s) URL. */
    data object InvalidUrl : Failure

    /** There is no Pocket ID session, so nothing was sent. */
    data object NotSignedIn : Failure

    /** The server answered 401/403: the sign-in is missing, expired or not allowed. */
    data object Unauthorized : Failure

    data class HttpError(val statusCode: Int) : Failure

    /** Couldn't reach the server (DNS, refused, timeout, TLS...). */
    data object Unreachable : Failure

    /** Reached something that isn't a Suwayomi GraphQL endpoint. */
    data object InvalidResponse : Failure
}

/**
 * Verifies a server address with a real read-only `aboutServer` query, authorised by the Pocket ID session like every
 * other Suwayomi call. No token or response body is ever logged or put into a result.
 */
class SuwayomiConnectionTester(
    private val client: OkHttpClient,
    private val auth: SuwayomiAuth,
    private val issuer: () -> String? = { null },
) {

    suspend fun test(serverUrl: String): SuwayomiConnectionResult {
        val baseUrl = SuwayomiServerUrl.normalize(serverUrl) ?: return SuwayomiConnectionResult.InvalidUrl
        if (SuwayomiServerUrl.isHttps(baseUrl) && !auth.isSignedIn()) return SuwayomiConnectionResult.NotSignedIn
        val apollo = createApolloClient(baseUrl)
        try {
            val response = apollo.query(SuwayomiAboutServerQuery()).execute()
            val data = response.data
            return when (val exception = response.exception) {
                null -> if (data != null && !response.hasErrors()) {
                    SuwayomiConnectionResult.Success(data.aboutServer.name, data.aboutServer.version)
                } else {
                    // GraphQL-level errors (e.g. an auth failure surfaced as a 200 response).
                    SuwayomiConnectionResult.InvalidResponse
                }
                is ApolloHttpException -> {
                    exception.body?.close()
                    when (exception.statusCode) {
                        401, 403 -> SuwayomiConnectionResult.Unauthorized
                        else -> SuwayomiConnectionResult.HttpError(exception.statusCode)
                    }
                }
                is ApolloNetworkException ->
                    if (exception.causedBy<NotSignedInException>()) {
                        SuwayomiConnectionResult.NotSignedIn
                    } else {
                        SuwayomiConnectionResult.Unreachable
                    }
                else -> SuwayomiConnectionResult.InvalidResponse
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SuwayomiConnectionResult.Unreachable
        } finally {
            apollo.close()
        }
    }

    private fun createApolloClient(baseUrl: String): ApolloClient {
        val authorized = SuwayomiAuthInterceptor.derive(client, SuwayomiAuthInterceptor(auth, { baseUrl }, issuer))
        return ApolloClient.Builder()
            .serverUrl(SuwayomiServerUrl.graphQlEndpoint(baseUrl))
            .okHttpClient(authorized)
            .dispatcher(Dispatchers.IO)
            // Lets us close the error body ourselves instead of leaking the socket.
            .httpExposeErrorBody(true)
            .build()
    }
}

internal inline fun <reified T : Throwable> ApolloNetworkException.causedBy(): Boolean {
    var current: Throwable? = (platformCause as? Throwable) ?: cause
    var depth = 0
    while (current != null && depth++ < 8) {
        if (current is T) return true
        current = current.cause
    }
    return false
}
