package mihon.suwayomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mihon.suwayomi.auth.OidcPreferences
import mihon.suwayomi.auth.OidcSession

/**
 * Observable result of the last connection test, shared so it survives leaving the settings screen.
 */
@Inject
@SingleIn(AppScope::class)
class SuwayomiConnectionState(
    private val network: NetworkHelper,
    private val auth: OidcSession,
    private val oidcPreferences: OidcPreferences,
) {

    sealed interface State {
        data object Idle : State
        data object Testing : State
        data class Finished(val result: SuwayomiConnectionResult) : State
    }

    val state: StateFlow<State>
        field = MutableStateFlow<State>(State.Idle)

    suspend fun test(serverUrl: String) {
        if (state.value == State.Testing) return
        state.value = State.Testing
        try {
            val result = SuwayomiConnectionTester(network.client, auth) { oidcPreferences.config()?.issuer }.test(serverUrl)
            state.value = State.Finished(result)
        } catch (e: Throwable) {
            // Cancelled: don't leave the UI spinning.
            state.value = State.Idle
            throw e
        }
    }

    fun reset() {
        if (state.value != State.Testing) state.value = State.Idle
    }
}
