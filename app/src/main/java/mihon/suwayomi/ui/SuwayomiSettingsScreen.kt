package mihon.suwayomi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.suwayomi.SuwayomiConnectionSetup
import mihon.suwayomi.SuwayomiImageScale
import mihon.suwayomi.SuwayomiReadingMode
import mihon.suwayomi.SuwayomiServerUrl
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * The app's only settings screen. Everything here is applied: the server form drives discovery and sign-in, the theme
 * drives the app theme and the reader options drive [SuwayomiReaderScreen].
 */
object SuwayomiSettingsScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val graph = LocalContext.current.appGraph
        val setup = graph.suwayomiSetup
        val auth = graph.suwayomiAuth
        val ui = graph.uiPreferences
        val reader = graph.suwayomiReaderPreferences
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        OnResume { auth.syncState() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.suwayomi_settings_title)) },
                    navigationIcon = { TextButton(onClick = navigator::pop) { Text("←") } },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(MR.strings.suwayomi_settings_server)
                ServerForm(setup, graph.suwayomiPreferences.serverUrl().get())

                val authState by auth.state.collectAsState()
                val discovery by setup.state.collectAsState()
                val identity = authState.identity
                if (identity != null) {
                    // Subject and e-mail come from the verified ID token; token text is never shown.
                    val who = identity.email ?: identity.name ?: identity.subject
                    Text(stringResource(MR.strings.suwayomi_oidc_signed_in_as, who), color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { auth.signOut() } }) {
                            Text(stringResource(MR.strings.suwayomi_oidc_sign_out))
                        }
                        OutlinedButton(onClick = { scope.launch { auth.signOut().endSessionUrl?.let { openBrowser(context, it) } } }) {
                            Text(stringResource(MR.strings.suwayomi_oidc_sign_out_provider))
                        }
                    }
                } else {
                    Text(stringResource(MR.strings.suwayomi_oidc_signed_out), style = MaterialTheme.typography.bodySmall)
                    SignInButton(setup, enabled = discovery is SuwayomiConnectionSetup.State.Ready)
                }
                if (authState.signingIn) {
                    Text(stringResource(MR.strings.suwayomi_oidc_signing_in), style = MaterialTheme.typography.bodySmall)
                }
                authState.error?.let { Text(oidcErrorText(it), color = MaterialTheme.colorScheme.error) }

                SectionTitle(MR.strings.suwayomi_settings_display)
                val themeMode by ui.themeMode.collectAsState()
                ChipRow(
                    listOf(
                        ThemeMode.SYSTEM to MR.strings.theme_system,
                        ThemeMode.LIGHT to MR.strings.theme_light,
                        ThemeMode.DARK to MR.strings.theme_dark,
                    ),
                    themeMode,
                ) {
                    ui.themeMode.set(it)
                    setAppCompatDelegateThemeMode(it)
                }
                val amoled by ui.themeDarkAmoled.collectAsState()
                SwitchRow(MR.strings.pref_dark_theme_pure_black, amoled) { ui.themeDarkAmoled.set(it) }

                SectionTitle(MR.strings.suwayomi_settings_reader)
                Text(stringResource(MR.strings.suwayomi_reader_mode), style = MaterialTheme.typography.labelLarge)
                val mode by reader.readingMode.collectAsState()
                ChipRow(
                    listOf(
                        SuwayomiReadingMode.RightToLeft to MR.strings.suwayomi_reader_mode_rtl,
                        SuwayomiReadingMode.LeftToRight to MR.strings.suwayomi_reader_mode_ltr,
                        SuwayomiReadingMode.Vertical to MR.strings.suwayomi_reader_mode_vertical,
                        SuwayomiReadingMode.Webtoon to MR.strings.suwayomi_reader_mode_webtoon,
                    ),
                    mode,
                ) { reader.readingMode.set(it) }
                Text(stringResource(MR.strings.suwayomi_reader_scale), style = MaterialTheme.typography.labelLarge)
                val scale by reader.imageScale.collectAsState()
                ChipRow(
                    listOf(
                        SuwayomiImageScale.Fit to MR.strings.suwayomi_reader_scale_fit,
                        SuwayomiImageScale.FitWidth to MR.strings.suwayomi_reader_scale_width,
                    ),
                    scale,
                ) { reader.imageScale.set(it) }
                val keepOn by reader.keepScreenOn.collectAsState()
                SwitchRow(MR.strings.suwayomi_reader_keep_screen_on, keepOn) { reader.keepScreenOn.set(it) }
                val showNumber by reader.showPageNumber.collectAsState()
                SwitchRow(MR.strings.suwayomi_reader_show_page_number, showNumber) { reader.showPageNumber.set(it) }

                SectionTitle(MR.strings.suwayomi_settings_about)
                Text(stringResource(MR.strings.suwayomi_settings_local_vs_server), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun ServerForm(setup: SuwayomiConnectionSetup, savedUrl: String) {
        var url by remember { mutableStateOf(savedUrl) }
        val discovery by setup.state.collectAsState()
        val normalized = SuwayomiServerUrl.normalize(url)
        val busy = discovery is SuwayomiConnectionSetup.State.Discovering

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(MR.strings.suwayomi_server_url)) },
            placeholder = { Text(stringResource(MR.strings.suwayomi_server_url_hint)) },
            isError = url.isNotBlank() && (normalized == null || !SuwayomiServerUrl.isHttps(normalized)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Button(enabled = !busy && url.isNotBlank(), onClick = { setup.connect(url) }) {
            Text(stringResource(MR.strings.suwayomi_connect))
        }
        val failed = discovery is SuwayomiConnectionSetup.State.Failed || discovery == SuwayomiConnectionSetup.State.InvalidUrl
        Text(
            discoveryStatusText(discovery),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    @Composable
    private fun SectionTitle(title: StringResource) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }

    @Composable
    private fun SwitchRow(title: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(title), Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun <T> ChipRow(options: List<Pair<T, StringResource>>, selected: T, onSelect: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(stringResource(label)) })
            }
        }
    }
}
