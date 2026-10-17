package mihon.suwayomi.auth

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        },
    )
}

internal class JsonReply(val code: Int, val body: JsonObject?)

/**
 * HTTP for provider calls. Redirects are never followed and nothing here adds credentials: the code exchange and
 * refresh are public-client requests carrying `client_id` only.
 */
internal class OidcHttp(base: OkHttpClient) {

    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private val json = Json { isLenient = false }

    suspend fun getJson(url: String): JsonObject {
        val reply = execute(Request.Builder().url(url).header("Accept", "application/json").get().build())
        if (reply.code !in 200..299) throw OidcException(OidcError.DiscoveryFailed)
        return reply.body ?: throw OidcException(OidcError.DiscoveryFailed)
    }

    /** Reply of a GET with a small body limit; the caller decides what each status means. */
    suspend fun getJsonReply(url: String, maxBytes: Long): JsonReply =
        execute(Request.Builder().url(url).header("Accept", "application/json").get().build(), maxBytes)

    /** Posts a form. The reply body is parsed leniently: a non-JSON body gives a null [JsonReply.body]. */
    suspend fun postForm(url: String, form: Map<String, String>): JsonReply {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).header("Accept", "application/json").post(body).build())
    }

    private suspend fun execute(request: Request, maxBytes: Long = MAX_BODY_BYTES): JsonReply {
        if (!request.url.isHttps) throw OidcException(OidcError.DiscoveryInsecure)
        val response = client.newCall(request).awaitResponse()
        response.use { r ->
            val source = r.body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) throw OidcException(OidcError.ProviderError)
            val text = source.readUtf8()
            val parsed = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            }
            return JsonReply(r.code, parsed)
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1L shl 20
    }
}
