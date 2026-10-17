package mihon.suwayomi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.Constants

/** Top-level destinations of the app, in navigation order. */
enum class SuwayomiTab { Library, Browse, Downloads, Extensions, Updates }

/**
 * Maps launcher shortcuts and intents onto the Suwayomi top-level tabs. Anything that belonged to the local Mihon
 * library (history, local manga ids) has no destination and is ignored.
 */
object SuwayomiRouting {

    fun tabForAction(action: String?): SuwayomiTab? = when (action) {
        Constants.SHORTCUT_LIBRARY -> SuwayomiTab.Library
        Constants.SHORTCUT_UPDATES -> SuwayomiTab.Updates
        Constants.SHORTCUT_SOURCES -> SuwayomiTab.Browse
        Constants.SHORTCUT_EXTENSIONS -> SuwayomiTab.Extensions
        Constants.SHORTCUT_DOWNLOADS -> SuwayomiTab.Downloads
        else -> null
    }

    val requestedTab: StateFlow<SuwayomiTab?>
        field = MutableStateFlow<SuwayomiTab?>(null)

    fun request(tab: SuwayomiTab) {
        requestedTab.value = tab
    }

    fun consume() {
        requestedTab.value = null
    }
}
