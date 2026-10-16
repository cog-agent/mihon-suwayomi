package mihon.suwayomi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import mihon.suwayomi.SuwayomiError
import mihon.suwayomi.SuwayomiRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
    data class Failed(val error: SuwayomiError) : Loadable<Nothing>
}

@Composable
fun errorText(error: SuwayomiError): String = when (error) {
    SuwayomiError.NotConfigured -> stringResource(MR.strings.suwayomi_not_configured)
    SuwayomiError.NotSignedIn -> stringResource(MR.strings.suwayomi_sign_in_required)
    SuwayomiError.Unauthorized -> stringResource(MR.strings.suwayomi_error_unauthorized)
    is SuwayomiError.Http -> stringResource(MR.strings.suwayomi_error_http, error.statusCode)
    SuwayomiError.Network -> stringResource(MR.strings.suwayomi_error_unreachable)
    is SuwayomiError.Server -> stringResource(MR.strings.suwayomi_error_server, error.message)
    SuwayomiError.InvalidResponse -> stringResource(MR.strings.suwayomi_error_invalid_response)
}

/** Shows a spinner, an error with retry, or [content] depending on [state]. */
@Composable
fun <T> LoadableContent(state: Loadable<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Loadable.Failed -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(errorText(state.error), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text(stringResource(MR.strings.suwayomi_retry)) }
        }
        is Loadable.Loaded -> content(state.value)
    }
}

/** Loads [path] (a server-relative or absolute URL) with the server credentials attached. */
@Composable
fun SuwayomiImage(
    repository: SuwayomiRepository,
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val url = repository.resolveUrl(path)
    val context = LocalContext.current
    val callFactory = repository.authorizedCallFactory(url)
    val request = ImageRequest.Builder(context)
        .data(url)
        .apply {
            // The token is added by the interceptor of this redirect-disabled client, never passed through Coil.
            if (callFactory != null) fetcherFactory(OkHttpNetworkFetcherFactory(callFactory = { callFactory }))
        }
        .build()
    AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = contentScale)
}
