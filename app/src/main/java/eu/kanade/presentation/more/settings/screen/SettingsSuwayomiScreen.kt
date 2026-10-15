package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.suwayomi.ui.SuwayomiHomeScreen
import mihon.suwayomi.SuwayomiConnectionResult
import mihon.suwayomi.SuwayomiConnectionState
import mihon.suwayomi.SuwayomiServerUrl
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsSuwayomiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_suwayomi

    @Composable
    override fun getPreferences(): List<Preference> {
        return listOf(
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(MR.strings.pref_category_suwayomi),
                content = { ConnectionForm() },
            ),
        )
    }

    @Composable
    private fun ConnectionForm() {
        val graph = LocalContext.current.appGraph
        val preferences = remember { graph.suwayomiPreferences }
        val connection = remember { graph.suwayomiConnectionState }
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow

        var url by remember { mutableStateOf(preferences.serverUrl().get()) }
        // The saved key is deliberately not loaded into the field; an empty field means "keep the stored key".
        var apiKey by remember { mutableStateOf("") }
        var hasStoredKey by remember { mutableStateOf(preferences.hasApiKey()) }
        var saved by remember { mutableStateOf(false) }
        val state by connection.state.collectAsState()

        DisposableEffect(Unit) {
            onDispose { connection.reset() }
        }

        val normalizedUrl = SuwayomiServerUrl.normalize(url)
        val testing = state == SuwayomiConnectionState.State.Testing

        // The stored key is only valid for the stored server, so never pair it with a different URL.
        fun effectiveKey() = apiKey.ifBlank {
            if (normalizedUrl == preferences.serverUrl().get()) preferences.getApiKey() else ""
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    saved = false
                    connection.reset()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(MR.strings.suwayomi_server_url)) },
                placeholder = { Text(stringResource(MR.strings.suwayomi_server_url_hint)) },
                isError = url.isNotBlank() && normalizedUrl == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    saved = false
                    connection.reset()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(MR.strings.suwayomi_api_key)) },
                supportingText = { Text(stringResource(MR.strings.suwayomi_api_key_summary)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = normalizedUrl != null && !testing,
                    onClick = {
                        preferences.saveServer(normalizedUrl!!, apiKey)
                        apiKey = ""
                        hasStoredKey = preferences.hasApiKey()
                        url = normalizedUrl
                        saved = true
                    },
                ) {
                    Text(stringResource(MR.strings.action_save))
                }
                OutlinedButton(
                    enabled = normalizedUrl != null && !testing,
                    onClick = { scope.launch { connection.test(url, effectiveKey()) } },
                ) {
                    Text(stringResource(MR.strings.suwayomi_test_connection))
                }
                OutlinedButton(
                    enabled = !testing && (hasStoredKey || preferences.serverUrl().get().isNotEmpty()),
                    onClick = {
                        preferences.clear()
                        url = ""
                        apiKey = ""
                        hasStoredKey = false
                        saved = false
                        connection.reset()
                    },
                ) {
                    Text(stringResource(MR.strings.suwayomi_clear))
                }
            }

            Button(
                enabled = preferences.serverUrl().get().isNotEmpty(),
                onClick = { navigator.push(SuwayomiHomeScreen) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.strings.suwayomi_open))
            }

            if (saved) {
                Text(stringResource(MR.strings.suwayomi_saved), style = MaterialTheme.typography.bodySmall)
            }
            StatusText(state)
        }
    }

    @Composable
    private fun StatusText(state: SuwayomiConnectionState.State) {
        when (state) {
            SuwayomiConnectionState.State.Idle -> Unit
            SuwayomiConnectionState.State.Testing -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                Text(stringResource(MR.strings.suwayomi_testing_connection))
            }
            is SuwayomiConnectionState.State.Finished -> {
                val result = state.result
                val isError = result is SuwayomiConnectionResult.Failure
                Text(
                    text = when (result) {
                        is SuwayomiConnectionResult.Success ->
                            stringResource(MR.strings.suwayomi_connected, result.serverName, result.version)
                        SuwayomiConnectionResult.InvalidUrl -> stringResource(MR.strings.suwayomi_error_invalid_url)
                        SuwayomiConnectionResult.Unauthorized -> stringResource(MR.strings.suwayomi_error_unauthorized)
                        is SuwayomiConnectionResult.HttpError ->
                            stringResource(MR.strings.suwayomi_error_http, result.statusCode)
                        SuwayomiConnectionResult.Unreachable -> stringResource(MR.strings.suwayomi_error_unreachable)
                        SuwayomiConnectionResult.InvalidResponse ->
                            stringResource(MR.strings.suwayomi_error_invalid_response)
                    },
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
