package mihon.suwayomi.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import mihon.suwayomi.auth.OidcError
import mihon.suwayomi.auth.OidcSession
import mihon.suwayomi.auth.SignInStart
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun oidcErrorText(error: OidcError): String = stringResource(
    when (error) {
        OidcError.NotConfigured, OidcError.InvalidConfig -> MR.strings.suwayomi_oidc_error_not_configured
        OidcError.DiscoveryFailed -> MR.strings.suwayomi_oidc_error_discovery
        OidcError.DiscoveryIssuerMismatch -> MR.strings.suwayomi_oidc_error_issuer_mismatch
        OidcError.DiscoveryInsecure -> MR.strings.suwayomi_oidc_error_insecure
        OidcError.PkceUnsupported -> MR.strings.suwayomi_oidc_error_pkce
        OidcError.Network -> MR.strings.suwayomi_oidc_error_network
        OidcError.NoPendingRequest, OidcError.StateMismatch, OidcError.RequestExpired ->
            MR.strings.suwayomi_oidc_error_request
        OidcError.AccessDenied -> MR.strings.suwayomi_oidc_error_denied
        OidcError.ProviderError -> MR.strings.suwayomi_oidc_error_provider
        OidcError.TokenExchangeFailed, OidcError.InvalidToken, OidcError.UnknownSigningKey ->
            MR.strings.suwayomi_oidc_error_token
        OidcError.InsufficientScope -> MR.strings.suwayomi_oidc_error_scope
        OidcError.InvalidGrant -> MR.strings.suwayomi_oidc_error_grant
    },
)

/** Runs [onResume] each time the hosting activity comes back to the foreground, e.g. from the sign-in browser. */
@Composable
fun OnResume(onResume: () -> Unit) {
    val owner = LocalContext.current as? LifecycleOwner ?: return
    val current by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) current() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** A button that starts the browser sign-in. No Activity is retained beyond the click. */
@Composable
fun SignInButton(auth: OidcSession, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Button(
        modifier = modifier,
        onClick = {
            scope.launch {
                val start = auth.beginSignIn()
                if (start is SignInStart.Ready) openBrowser(context, start.authorizeUrl)
            }
        },
    ) {
        Text(stringResource(MR.strings.suwayomi_oidc_sign_in))
    }
}

fun openBrowser(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
        )
    } catch (_: ActivityNotFoundException) {
        // No browser installed; the settings screen keeps showing the signed-out state.
    }
}

/** Shown instead of Suwayomi content while there is no session. */
@Composable
fun SignInRequired(auth: OidcSession, error: OidcError?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(MR.strings.suwayomi_sign_in_required), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(MR.strings.suwayomi_sign_in_required_summary))
        if (error != null) Text(oidcErrorText(error), color = MaterialTheme.colorScheme.error)
        SignInButton(auth)
    }
}
