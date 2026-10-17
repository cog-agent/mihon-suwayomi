package mihon.suwayomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mihon.suwayomi.auth.OidcError
import mihon.suwayomi.auth.OidcException
import mihon.suwayomi.auth.OidcHttp
import mihon.suwayomi.auth.OidcPreferences
import mihon.suwayomi.auth.OidcSession
import mihon.suwayomi.auth.SignInStart
import mihon.suwayomi.auth.SuwayomiBootstrapClient
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Turns "a server address" into a usable, signed-out-or-in client: fetches the server's client registration, checks
 * the identity provider, and saves the coherent result. The user never types an issuer, client id or scope.
 *
 * Changing the server is atomic from the credentials' point of view: the old session is discarded and the new address
 * saved before any request goes to the new origin, any discovery still running for the previous address is cancelled
 * and cannot write its result afterwards.
 */
class SuwayomiConnectionSetup(
    private val preferences: SuwayomiPreferences,
    private val oidcPreferences: OidcPreferences,
    private val auth: OidcSession,
    httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {

    sealed interface State {
        data object Idle : State
        data class Discovering(val server: String) : State
        data class Ready(val server: String) : State
        data object InvalidUrl : State
        data class Failed(val server: String, val error: OidcError) : State
    }

    private val bootstrap = SuwayomiBootstrapClient(OidcHttp(httpClient))
    private val lock = Any()
    private var job: Job? = null
    private var generation = 0L

    val state: StateFlow<State>
        field = MutableStateFlow<State>(initialState())

    private fun initialState(): State =
        currentServer()?.takeIf { isDiscovered(it) }?.let { State.Ready(it) } ?: State.Idle

    private fun currentServer(): String? = SuwayomiServerUrl.normalize(preferences.serverUrl().get())

    private fun isDiscovered(server: String): Boolean =
        preferences.discoveredServer().get() == server && oidcPreferences.config() != null

    /** Applies an address typed by the user; always fetches the registration again (this is also the retry). */
    fun connect(input: String) {
        start(input, retry = true)
    }

    /** First use and every app start: discovers for the saved server unless that is already done or running. */
    fun ensureDiscovered() {
        start(preferences.serverUrl().get(), retry = false)
    }

    /** Sign-in is only offered for a server whose registration was fetched and validated. */
    suspend fun beginSignIn(): SignInStart {
        val server = currentServer()
        if (server == null || !isDiscovered(server)) return SignInStart.Failed(OidcError.NotConfigured)
        return auth.beginSignIn()
    }

    private fun start(input: String, retry: Boolean) {
        synchronized(lock) {
            val base = SuwayomiServerUrl.normalize(input)
            // Only https servers can receive a token, so only they are worth discovering.
            if (base == null || !SuwayomiServerUrl.isHttps(base)) {
                job?.cancel()
                generation++
                state.value = State.InvalidUrl
                return
            }
            val current = state.value
            if (!retry) {
                if (current is State.Discovering && current.server == base) return
                if (preferences.serverUrl().get() == base && isDiscovered(base)) {
                    state.value = State.Ready(base)
                    return
                }
            }
            job?.cancel()
            val gen = ++generation
            if (preferences.serverUrl().get() != base) {
                // Credentials and settings of the previous server never reach the new one.
                auth.discardSession()
                preferences.saveServer(base, "")
                preferences.discoveredServer().delete()
            }
            state.value = State.Discovering(base)
            job = scope.launch { discover(base, gen) }
        }
    }

    private suspend fun discover(base: String, gen: Long) {
        val raw = try {
            bootstrap.fetch(base).also { auth.verifyProvider(it.issuer) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcException) {
            return fail(base, gen, e.error)
        } catch (_: IOException) {
            return fail(base, gen, OidcError.Network)
        }
        synchronized(lock) {
            if (generation != gen || preferences.serverUrl().get() != base) return
            if (auth.saveConfig(raw) !is mihon.suwayomi.auth.OidcConfigResult.Valid) {
                state.value = State.Failed(base, OidcError.BootstrapInvalid)
                return
            }
            preferences.discoveredServer().set(base)
            state.value = State.Ready(base)
        }
    }

    private fun fail(base: String, gen: Long, error: OidcError) {
        synchronized(lock) {
            if (generation == gen) state.value = State.Failed(base, error)
        }
    }
}
