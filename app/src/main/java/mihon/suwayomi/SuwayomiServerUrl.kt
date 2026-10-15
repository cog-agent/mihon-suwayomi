package mihon.suwayomi

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation of the user-entered Suwayomi server address.
 */
object SuwayomiServerUrl {

    /**
     * @return the base URL without a trailing slash (a reverse-proxy path prefix is kept), or null if [input] isn't an
     * http(s) URL, or embeds credentials, a query or a fragment.
     */
    fun normalize(input: String): String? {
        val url = input.trim().toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/')
    }

    /** Only an https server may receive the Pocket ID token. */
    fun isHttps(baseUrl: String): Boolean = baseUrl.toHttpUrlOrNull()?.isHttps == true

    fun graphQlEndpoint(baseUrl: String): String = "${baseUrl.trimEnd('/')}/api/graphql"
}
