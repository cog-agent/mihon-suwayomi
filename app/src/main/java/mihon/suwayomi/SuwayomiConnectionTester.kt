package mihon.suwayomi

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.network.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import mihon.graphql.suwayomi.SuwayomiAboutServerQuery
import okhttp3.OkHttpClient

sealed interface SuwayomiConnectionResult {
    data class Success(val serverName: String, val version: String) : SuwayomiConnectionResult

    sealed interface Failure : SuwayomiConnectionResult

    /** The server address is empty or isn't a plain http(s) URL. */
    data object InvalidUrl : Failure

    /** The server answered 401/403: the API key is missing, wrong or expired. */
    data object Unauthorized : Failure

    data class HttpError(val statusCode: Int) : Failure

    /** Couldn't reach the server (DNS, refused, timeout, TLS...). */
    data object Unreachable : Failure

    /** Reached something that isn't a Suwayomi GraphQL endpoint. */
    data object InvalidResponse : Failure
}

/**
 * Verifies a server address and API key with a real read-only `aboutServer` query.
 * Neither the key nor any response body is ever logged or put into a result.
 */
class SuwayomiConnectionTester(private val client: OkHttpClient) {

    suspend fun test(serverUrl: String, apiKey: String): SuwayomiConnectionResult {
        val baseUrl = SuwayomiServerUrl.normalize(serverUrl) ?: return SuwayomiConnectionResult.InvalidUrl
        val apollo = createApolloClient(baseUrl, apiKey)
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
                is ApolloNetworkException -> SuwayomiConnectionResult.Unreachable
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

    private fun createApolloClient(baseUrl: String, apiKey: String): ApolloClient {
        val token = apiKey.trim()
        val okHttp = if (token.isEmpty()) {
            client
        } else {
            client.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
                }
                .build()
        }
        return ApolloClient.Builder()
            .serverUrl(SuwayomiServerUrl.graphQlEndpoint(baseUrl))
            .okHttpClient(okHttp)
            .dispatcher(Dispatchers.IO)
            // Lets us close the error body ourselves instead of leaking the socket.
            .httpExposeErrorBody(true)
            .build()
    }
}
