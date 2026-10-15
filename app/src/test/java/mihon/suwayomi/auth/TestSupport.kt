package mihon.suwayomi.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mihon.suwayomi.SecretCipher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Every credential in these tests is a clearly synthetic value generated in-process.
const val ISSUER = "https://auth.test"
const val CLIENT_ID = "test-client-id"
const val AUDIENCE = "https://suwayomi.test"
const val PERMISSION = "suwayomi:access"
const val TEST_SCOPES = "openid profile email groups $PERMISSION"
const val NOW_MS = 1_800_000_000_000L
const val NOW_S = NOW_MS / 1000

private val b64 = Base64.getUrlEncoder().withoutPadding()

fun b64(bytes: ByteArray): String = b64.encodeToString(bytes)

/** Hex-encoding stand-in for the keystore cipher: reversible, and the output never contains the plaintext. */
class FakeCipher(var canDecrypt: Boolean = true) : SecretCipher {
    override fun encrypt(plainText: String) = "enc:" + plainText.toByteArray().joinToString("") { "%02x".format(it) }
    override fun decrypt(encoded: String): String? {
        if (!canDecrypt || !encoded.startsWith("enc:")) return null
        return try {
            val hex = encoded.removePrefix("enc:")
            String(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        } catch (_: Exception) {
            null
        }
    }
}

class TestKey(val kid: String, val pair: KeyPair, val alg: String) {

    fun jwk(): JsonObject = buildJsonObject {
        put("kid", kid)
        put("use", "sig")
        put("alg", alg)
        val pub = pair.public
        if (pub is RSAPublicKey) {
            put("kty", "RSA")
            put("n", b64(unsigned(pub.modulus)))
            put("e", b64(unsigned(pub.publicExponent)))
        } else if (pub is ECPublicKey) {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", b64(unsigned(pub.w.affineX, 32)))
            put("y", b64(unsigned(pub.w.affineY, 32)))
        }
    }

    fun sign(data: ByteArray): ByteArray = if (alg == "RS256") {
        Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
    } else {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
        derToRaw(der)
    }

    companion object {
        fun rsa(kid: String, bits: Int = 2048) = TestKey(
            kid,
            KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair(),
            "RS256",
        )

        fun ec(kid: String) = TestKey(
            kid,
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
            "ES256",
        )
    }
}

private fun unsigned(v: BigInteger, size: Int? = null): ByteArray {
    var b = v.toByteArray()
    if (b.size > 1 && b[0] == 0.toByte()) b = b.copyOfRange(1, b.size)
    return if (size != null && b.size < size) ByteArray(size - b.size) + b else b
}

private fun derToRaw(der: ByteArray): ByteArray {
    var i = 2
    if (der[1].toInt() and 0x80 != 0) i += 1
    val rLen = der[i + 1].toInt()
    val r = der.copyOfRange(i + 2, i + 2 + rLen)
    i += 2 + rLen
    val sLen = der[i + 1].toInt()
    val s = der.copyOfRange(i + 2, i + 2 + sLen)
    fun fix(x: ByteArray): ByteArray {
        val t = if (x.size > 32) x.copyOfRange(x.size - 32, x.size) else x
        return ByteArray(32 - t.size) + t
    }
    return fix(r) + fix(s)
}

fun jsonOf(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

fun jwtHeader(key: TestKey, alg: String = key.alg, kid: String? = key.kid): JsonObject = buildJsonObject {
    put("alg", alg)
    put("typ", "JWT")
    if (kid != null) put("kid", kid)
}

fun signJwt(key: TestKey, claims: JsonObject, header: JsonObject = jwtHeader(key)): String {
    val input = b64(header.toString().toByteArray()) + "." + b64(claims.toString().toByteArray())
    return input + "." + b64(key.sign(input.toByteArray(Charsets.US_ASCII)))
}

fun idClaims(
    nonce: String?,
    issuer: String = ISSUER,
    aud: JsonElement = JsonPrimitive(CLIENT_ID),
    exp: Long = NOW_S + 3600,
    iat: Long = NOW_S,
    extra: Map<String, JsonElement> = emptyMap(),
): JsonObject = buildJsonObject {
    put("iss", issuer)
    put("aud", aud)
    put("sub", "user-subject-1")
    put("email", "user@example.test")
    put("name", "Test User")
    put("iat", iat)
    put("exp", exp)
    if (nonce != null) put("nonce", nonce)
    extra.forEach { (k, v) -> put(k, v) }
}

fun jsonArrayOf(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

class Reply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** The identity provider as an OkHttp interceptor: nothing touches the network. */
class FakeProvider(var key: TestKey) : Interceptor {

    val requests = CopyOnWriteArrayList<Request>()
    val forms = CopyOnWriteArrayList<Map<String, String>>()
    val refreshCalls = AtomicInteger()
    val jwksCalls = AtomicInteger()
    val revokeCalls = AtomicInteger()

    var discovery: JsonObject = defaultDiscovery()
    var extraKeys: List<TestKey> = emptyList()
    var failNetwork = false
    var tokenDelayMs = 0L
    var lastNonce: String? = null
    var revokeCode = 200

    /** Builds the ID token for a code exchange. */
    var idTokenBuilder: (nonce: String?) -> String = { nonce -> signJwt(key, idClaims(nonce)) }
    var clockSeconds: () -> Long = { NOW_S }
    var accessTokenBuilder: (n: Int) -> String = { n -> jwt(n) }
    var scopeInResponse: String? = TEST_SCOPES

    /** A signed access token in the shape Pocket ID issues (RFC 9068): `aud` is the resource, `scope` the permissions. */
    fun jwt(
        n: Int,
        aud: JsonElement = JsonPrimitive(AUDIENCE),
        scope: JsonElement? = JsonPrimitive(TEST_SCOPES),
        exp: Long = clockSeconds() + 3600,
        issuer: String = ISSUER,
        signer: TestKey = key,
    ): String = signJwt(signer, buildJsonObject {
        put("iss", issuer)
        put("aud", aud)
        put("sub", "user-subject-1")
        put("jti", "access-$n")
        put("iat", clockSeconds())
        put("exp", exp)
        if (scope != null) put("scope", scope)
    })
    var refreshReply: ((Map<String, String>) -> Reply)? = null
    var discoveryReply: Reply? = null
    var noRefreshToken = false

    private val issued = AtomicInteger()

    fun defaultDiscovery(): JsonObject = buildJsonObject {
        put("issuer", ISSUER)
        put("authorization_endpoint", "$ISSUER/authorize")
        put("token_endpoint", "$ISSUER/api/oidc/token")
        put("jwks_uri", "$ISSUER/.well-known/jwks.json")
        put("revocation_endpoint", "$ISSUER/api/oidc/revoke")
        put("end_session_endpoint", "$ISSUER/api/oidc/end-session")
        putJsonArray("code_challenge_methods_supported") { add(JsonPrimitive("S256")) }
    }

    fun tokenBody(refresh: String?, idToken: String?, n: Int): String = buildJsonObject {
        put("access_token", accessTokenBuilder(n))
        put("token_type", "Bearer")
        put("expires_in", 3600)
        if (refresh != null) put("refresh_token", refresh)
        if (idToken != null) put("id_token", idToken)
        scopeInResponse?.let { put("scope", it) }
    }.toString()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        if (failNetwork) throw IOException("fake network down")
        val url = request.url.toString().substringBefore('?')
        val form = request.body?.let { body ->
            Buffer().also { body.writeTo(it) }.readUtf8().split('&').filter { it.isNotEmpty() }.associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        }
        if (form != null) forms += form
        val reply = when {
            url.endsWith("/.well-known/openid-configuration") -> discoveryReply ?: Reply(200, discovery.toString())
            url.endsWith("/jwks.json") -> {
                jwksCalls.incrementAndGet()
                Reply(200, buildJsonObject { put("keys", JsonArray((listOf(key) + extraKeys).map { it.jwk() })) }.toString())
            }
            url.endsWith("/api/oidc/token") -> {
                if (tokenDelayMs > 0) Thread.sleep(tokenDelayMs)
                when (form?.get("grant_type")) {
                    "authorization_code" -> {
                        val n = issued.incrementAndGet()
                        Reply(200, tokenBody(if (noRefreshToken) null else "fake-refresh-token-$n", idTokenBuilder(lastNonce), n))
                    }
                    "refresh_token" -> {
                        refreshCalls.incrementAndGet()
                        refreshReply?.invoke(form) ?: run {
                            val n = issued.incrementAndGet()
                            Reply(200, tokenBody("fake-refresh-token-$n", null, n))
                        }
                    }
                    else -> Reply(400, """{"error":"unsupported_grant_type"}""")
                }
            }
            url.endsWith("/api/oidc/revoke") -> {
                revokeCalls.incrementAndGet()
                Reply(revokeCode, "")
            }
            else -> Reply(404, "{}")
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message("test")
            .apply { reply.headers.forEach { (k, v) -> header(k, v) } }
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

class AuthEnv(
    val key: TestKey = TestKey.rsa("key-1"),
    configure: Boolean = true,
    offline: Boolean = false,
) {
    // The deployed server is the default, so "not configured" means the user saved an unusable issuer.
    val store = InMemoryPreferenceStore(
        if (configure) {
            emptySequence()
        } else {
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    tachiyomi.core.common.preference.Preference.privateKey("suwayomi_oidc_issuer"),
                    "invalid",
                    "",
                ),
            )
        },
    )
    val cipher = FakeCipher()
    val prefs = OidcPreferences(store, cipher)
    val provider = FakeProvider(key)
    var now = NOW_MS
        set(value) {
            field = value
            provider.clockSeconds = { value / 1000 }
        }
    val session = OidcSession(
        prefs = prefs,
        httpClient = OkHttpClient.Builder().addInterceptor(provider).build(),
        scope = CoroutineScope(Job()),
        clock = { now },
    )

    init {
        if (configure) {
            session.saveConfig(OidcRawConfig(ISSUER, CLIENT_ID, AUDIENCE, TEST_SCOPES, offline))
        }
    }

    class Started(val url: okhttp3.HttpUrl) {
        val state get() = url.queryParameter("state")!!
        val nonce get() = url.queryParameter("nonce")!!
    }

    suspend fun begin(): Started {
        val start = session.beginSignIn() as SignInStart.Ready
        val started = Started(start.authorizeUrl.toHttpUrl())
        provider.lastNonce = started.nonce
        return started
    }

    fun redirect(state: String, code: String = "fake-auth-code") = "${OidcConfig.REDIRECT_URI}?code=$code&state=$state"

    suspend fun signIn(): RedirectResult {
        val s = begin()
        return session.handleRedirect(redirect(s.state))
    }

    fun rawBlob(): String = prefs.rawSessionBlob()
}

/** A fake token source for interceptor and repository tests. */
class FakeAuth(
    var token: String? = "fake-access-token-1",
    var refreshedToken: String? = "fake-access-token-2",
) : SuwayomiAuth {
    val refreshes = AtomicInteger()
    val tokenRequests = AtomicInteger()
    override val state = kotlinx.coroutines.flow.MutableStateFlow(AuthState())
    override fun isSignedIn() = token != null
    override fun cachedAccessToken() = token
    override suspend fun accessToken(): TokenResult = tokenRequests.incrementAndGet().let {
        token?.let { TokenResult.Token(it) } ?: TokenResult.NotSignedIn
    }

    override suspend fun refreshAfterRejection(rejected: String): TokenResult {
        refreshes.incrementAndGet()
        val next = refreshedToken ?: return TokenResult.NotSignedIn.also { token = null }
        token = next
        return TokenResult.Token(next)
    }
}
