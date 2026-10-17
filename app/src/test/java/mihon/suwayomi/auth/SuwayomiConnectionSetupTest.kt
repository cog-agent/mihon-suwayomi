package mihon.suwayomi.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.suwayomi.SuwayomiConnectionSetup
import mihon.suwayomi.SuwayomiConnectionSetup.State
import mihon.suwayomi.SuwayomiPreferences
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val OTHER = "https://other.test"

class SuwayomiConnectionSetupTest {

    private val provider = FakeProvider(TestKey.rsa("k1"))
    private val bootstrap = FakeBootstrapServer()
    private val gate = CountDownLatch(1)
    private var hold: String? = null

    // Routes by host: the two servers publish their registration, everything else is the identity provider.
    private val router = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val server = when (host) {
            "suwayomi.test" -> AUDIENCE
            "other.test" -> OTHER
            else -> null
        }
        when {
            server == null -> provider.intercept(chain)
            request.url.encodedPath != SuwayomiBootstrap.PATH -> bootstrapResponse(chain, 404, "")
            else -> {
                if (host == hold) gate.await(5, TimeUnit.SECONDS)
                bootstrapResponse(chain, bootstrap.code, bootstrapDoc(server = server, resource = server).toString())
            }
        }
    }

    private fun bootstrapResponse(chain: Interceptor.Chain, code: Int, body: String): Response {
        bootstrap.urls += chain.request().url.toString()
        return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val store = InMemoryPreferenceStore()
    private val cipher = FakeCipher()
    private val preferences = SuwayomiPreferences(store, cipher)
    private val oidcPrefs = OidcPreferences(store, cipher)
    private val client = OkHttpClient.Builder().addInterceptor(router).build()
    private val session = OidcSession(oidcPrefs, client, CoroutineScope(Job()), clock = { NOW_MS })
    private val setup = SuwayomiConnectionSetup(preferences, oidcPrefs, session, client, CoroutineScope(Dispatchers.IO + Job()))

    private fun settled(): State = runBlocking {
        withTimeout(10_000) { setup.state.first { it !is State.Discovering } }
    }

    private fun signedInSession() {
        oidcPrefs.saveSession(
            StoredSession(
                accessToken = "access", refreshToken = "refresh", accessExpiresAtMs = NOW_MS + 3_600_000, issuer = ISSUER,
                clientId = CLIENT_ID, audience = AUDIENCE, scope = "openid $PERMISSION", subject = "s", email = null,
                name = null, idTokenExpiresAtSec = null,
            ),
        )
    }

    @Test
    fun `entering only the server address discovers and saves a coherent config`() {
        preferences.serverUrl().set(AUDIENCE)
        setup.connect(AUDIENCE)
        settled() shouldBe State.Ready(AUDIENCE)
        val config = oidcPrefs.config()!!
        config.issuer shouldBe ISSUER
        config.clientId shouldBe CLIENT_ID
        config.audience shouldBe AUDIENCE
        config.apiPermissions shouldBe listOf(PERMISSION)
        preferences.discoveredServer().get() shouldBe AUDIENCE
    }

    @Test
    fun `first use discovers automatically and a second call does not refetch`() {
        preferences.serverUrl().set(AUDIENCE)
        setup.ensureDiscovered()
        settled() shouldBe State.Ready(AUDIENCE)
        val fetches = bootstrap.urls.count { it.endsWith(SuwayomiBootstrap.PATH) }
        setup.ensureDiscovered()
        bootstrap.urls.count { it.endsWith(SuwayomiBootstrap.PATH) } shouldBe fetches
        setup.state.value shouldBe State.Ready(AUDIENCE)
    }

    @Test
    fun `sign in is refused until discovery succeeded`() {
        preferences.serverUrl().set(AUDIENCE)
        runBlocking { setup.beginSignIn() } shouldBe SignInStart.Failed(OidcError.NotConfigured)
        setup.connect(AUDIENCE)
        settled()
        runBlocking { setup.beginSignIn() }.shouldBeInstanceOf<SignInStart.Ready>()
    }

    @Test
    fun `a server without a document gives a clear retryable error and no sign-in`() {
        preferences.serverUrl().set(AUDIENCE)
        bootstrap.code = 404
        setup.connect(AUDIENCE)
        settled() shouldBe State.Failed(AUDIENCE, OidcError.BootstrapUnavailable)
        runBlocking { setup.beginSignIn() } shouldBe SignInStart.Failed(OidcError.NotConfigured)
        bootstrap.code = 200
        setup.connect(AUDIENCE)
        settled() shouldBe State.Ready(AUDIENCE)
    }

    @Test
    fun `an identity provider that fails validation is not saved`() {
        preferences.serverUrl().set(AUDIENCE)
        provider.discovery = kotlinx.serialization.json.JsonObject(
            provider.discovery.toMutableMap().apply { remove("code_challenge_methods_supported") },
        )
        setup.connect(AUDIENCE)
        settled() shouldBe State.Failed(AUDIENCE, OidcError.PkceUnsupported)
        preferences.discoveredServer().get() shouldBe ""
    }

    @Test
    fun `a non-https or invalid address is refused without a request`() {
        setup.connect("http://suwayomi.test")
        setup.state.value shouldBe State.InvalidUrl
        setup.connect("not a url")
        setup.state.value shouldBe State.InvalidUrl
        bootstrap.urls.size shouldBe 0
        preferences.serverUrl().get() shouldBe SuwayomiPreferences.DEPLOYED_SERVER_URL
    }

    @Test
    fun `changing the server discards the session before contacting the new origin`() {
        preferences.serverUrl().set(AUDIENCE)
        setup.connect(AUDIENCE)
        settled()
        signedInSession()
        session.syncState()
        session.isSignedIn() shouldBe true

        setup.connect(OTHER)
        // Wiped synchronously, before discovery of the new server has even answered.
        session.isSignedIn() shouldBe false
        preferences.serverUrl().get() shouldBe OTHER
        settled() shouldBe State.Ready(OTHER)
        oidcPrefs.config()!!.audience shouldBe OTHER
        session.isSignedIn() shouldBe false
    }

    @Test
    fun `a stale discovery for the previous server cannot overwrite the new one`() {
        preferences.serverUrl().set(AUDIENCE)
        hold = "suwayomi.test"
        setup.connect(AUDIENCE)
        setup.state.value shouldBe State.Discovering(AUDIENCE)
        setup.connect(OTHER)
        gate.countDown()
        settled() shouldBe State.Ready(OTHER)
        Thread.sleep(300)
        setup.state.value shouldBe State.Ready(OTHER)
        preferences.serverUrl().get() shouldBe OTHER
        preferences.discoveredServer().get() shouldBe OTHER
        oidcPrefs.config()!!.audience shouldBe OTHER
    }

    @Test
    fun `a previously discovered server is ready without a request after restart`() {
        preferences.serverUrl().set(AUDIENCE)
        setup.connect(AUDIENCE)
        settled()
        val seen = bootstrap.urls.size
        val restarted = SuwayomiConnectionSetup(preferences, oidcPrefs, session, client, CoroutineScope(Dispatchers.IO + Job()))
        restarted.state.value shouldBe State.Ready(AUDIENCE)
        restarted.ensureDiscovered()
        bootstrap.urls.size shouldBe seen
    }

    @Test
    fun `the default deployed server needs a discovery before sign in`() {
        // Defaults alone are not trusted: the marker is only set by a successful lookup.
        SuwayomiConnectionSetup(preferences, oidcPrefs, session, client, CoroutineScope(Job())).state.value shouldBe State.Idle
    }
}
