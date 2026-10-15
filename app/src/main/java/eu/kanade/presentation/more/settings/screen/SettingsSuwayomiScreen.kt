package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
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
import mihon.suwayomi.auth.OidcConfigField
import mihon.suwayomi.auth.OidcConfigResult
import mihon.suwayomi.auth.OidcPreferences
import mihon.suwayomi.auth.OidcRawConfig
import mihon.suwayomi.auth.OidcSession
import mihon.suwayomi.auth.SignInStart
import mihon.suwayomi.ui.OnResume
import mihon.suwayomi.ui.SuwayomiHomeScreen
import mihon.suwayomi.ui.oidcErrorText
import mihon.suwayomi.ui.openBrowser
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
        val auth = remember { graph.suwayomiAuth }
        val oidcPreferences = remember { graph.oidcPreferences }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow

        var url by remember { mutableStateOf(preferences.serverUrl().get()) }
        // The saved key is deliberately not loaded into the field; an empty field means "keep the stored key".
        var apiKey by remember { mutableStateOf("") }
        var hasStoredKey by remember { mutableStateOf(preferences.hasApiKey()) }
        var saved by remember { mutableStateOf(false) }
        val state by connection.state.collectAsState()
        val authState by auth.state.collectAsState()
        OnResume { auth.syncState() }

        DisposableEffect(Unit) {
            onDispose { connection.reset() }
        }

        val normalizedUrl = SuwayomiServerUrl.normalize(url)
        val testing = state == SuwayomiConnectionState.State.Testing

        // Testing sends the Pocket ID token, so only the saved server may be tested.
        val testable = normalizedUrl != null && normalizedUrl == preferences.serverUrl().get()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OidcSection(auth, oidcPreferences) { openBrowser(context, it) }

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
                supportingText = {
                    Text(
                        stringResource(
                            if (authState.signedIn && hasStoredKey) {
                                MR.strings.suwayomi_api_key_not_sent_warning
                            } else {
                                MR.strings.suwayomi_api_key_summary
                            },
                        ),
                    )
                },
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
                    enabled = testable && !testing,
                    onClick = { scope.launch { connection.test(url) } },
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

            if (authState.signedIn && hasStoredKey) {
                Text(
                    stringResource(MR.strings.suwayomi_api_key_not_sent_warning),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
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
    private fun OidcSection(auth: OidcSession, preferences: OidcPreferences, openUrl: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        val initial = remember { preferences.rawConfig() }
        var issuer by remember { mutableStateOf(initial.issuer) }
        var clientId by remember { mutableStateOf(initial.clientId) }
        var audience by remember { mutableStateOf(initial.audience) }
        var scopes by remember { mutableStateOf(initial.scopes) }
        var offline by remember { mutableStateOf(initial.offlineAccess) }
        var invalid by remember { mutableStateOf<OidcConfigField?>(null) }
        val authState by auth.state.collectAsState()
        val identity = authState.identity

        Text(stringResource(MR.strings.suwayomi_oidc_title), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = issuer,
            onValueChange = { issuer = it; invalid = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(MR.strings.suwayomi_oidc_issuer)) },
            placeholder = { Text(stringResource(MR.strings.suwayomi_oidc_issuer_hint)) },
            isError = invalid == OidcConfigField.Issuer,
            supportingText = {
                if (invalid == OidcConfigField.Issuer) Text(stringResource(MR.strings.suwayomi_oidc_invalid_issuer))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it; invalid = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(MR.strings.suwayomi_oidc_client_id)) },
            isError = invalid == OidcConfigField.ClientId,
            supportingText = {
                if (invalid == OidcConfigField.ClientId) Text(stringResource(MR.strings.suwayomi_oidc_invalid_client_id))
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = audience,
            onValueChange = { audience = it; invalid = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(MR.strings.suwayomi_oidc_audience)) },
            isError = invalid == OidcConfigField.Audience,
            supportingText = {
                if (invalid == OidcConfigField.Audience) Text(stringResource(MR.strings.suwayomi_oidc_invalid_audience))
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = scopes,
            onValueChange = { scopes = it; invalid = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(MR.strings.suwayomi_oidc_scopes)) },
            isError = invalid == OidcConfigField.Scopes,
            supportingText = {
                if (invalid == OidcConfigField.Scopes) Text(stringResource(MR.strings.suwayomi_oidc_invalid_scopes))
            },
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = offline, onCheckedChange = { offline = it })
            Text(stringResource(MR.strings.suwayomi_oidc_offline_access))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    invalid = (auth.saveConfig(OidcRawConfig(issuer, clientId, audience, scopes, offline))
                        as? OidcConfigResult.Invalid)?.field
                },
            ) {
                Text(stringResource(MR.strings.suwayomi_oidc_save))
            }
            if (identity == null) {
                Button(
                    onClick = {
                        invalid = (auth.saveConfig(OidcRawConfig(issuer, clientId, audience, scopes, offline))
                            as? OidcConfigResult.Invalid)?.field
                        if (invalid == null) {
                            scope.launch {
                                val start = auth.beginSignIn()
                                if (start is SignInStart.Ready) openUrl(start.authorizeUrl)
                            }
                        }
                    },
                ) {
                    Text(stringResource(MR.strings.suwayomi_oidc_sign_in))
                }
            }
        }

        if (identity != null) {
            // Subject and e-mail come from the verified ID token; token text is never shown.
            val who = identity.email ?: identity.name ?: identity.subject
            Text(stringResource(MR.strings.suwayomi_oidc_signed_in_as, who), color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { auth.signOut() } }) {
                    Text(stringResource(MR.strings.suwayomi_oidc_sign_out))
                }
                OutlinedButton(
                    onClick = { scope.launch { auth.signOut().endSessionUrl?.let(openUrl) } },
                ) {
                    Text(stringResource(MR.strings.suwayomi_oidc_sign_out_provider))
                }
            }
        } else {
            Text(stringResource(MR.strings.suwayomi_oidc_signed_out), style = MaterialTheme.typography.bodySmall)
        }
        if (authState.signingIn) {
            Text(stringResource(MR.strings.suwayomi_oidc_signing_in), style = MaterialTheme.typography.bodySmall)
        }
        authState.error?.let {
            Text(oidcErrorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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
                        SuwayomiConnectionResult.NotSignedIn -> stringResource(MR.strings.suwayomi_sign_in_required)
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
