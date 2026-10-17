package mihon.suwayomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

enum class SuwayomiReadingMode(
    /** Pages are laid out left to right / right to left (true) or top to bottom (false). */
    val horizontal: Boolean,
    /** Page order is reversed (right-to-left reading). */
    val reversed: Boolean,
    /** All pages in one scrolling column instead of one page at a time. */
    val continuous: Boolean,
) {
    RightToLeft(horizontal = true, reversed = true, continuous = false),
    LeftToRight(horizontal = true, reversed = false, continuous = false),
    Vertical(horizontal = false, reversed = false, continuous = false),
    Webtoon(horizontal = false, reversed = false, continuous = true),
}

enum class SuwayomiImageScale { Fit, FitWidth }

/** Options of the native reader. They are read by [mihon.suwayomi.ui.SuwayomiReaderScreen] and by nothing else. */
@Inject
@SingleIn(AppScope::class)
class SuwayomiReaderPreferences(preferenceStore: PreferenceStore) {
    val readingMode = preferenceStore.getEnum("suwayomi_reader_mode", SuwayomiReadingMode.RightToLeft)
    val imageScale = preferenceStore.getEnum("suwayomi_reader_scale", SuwayomiImageScale.Fit)
    val keepScreenOn = preferenceStore.getBoolean("suwayomi_reader_keep_screen_on", true)
    val showPageNumber = preferenceStore.getBoolean("suwayomi_reader_show_page_number", true)
}
