package mihon.suwayomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.Constants
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class SuwayomiRoutingTest {

    @Test
    fun `launcher shortcuts select remote tabs`() {
        SuwayomiRouting.tabForAction(Constants.SHORTCUT_LIBRARY) shouldBe SuwayomiTab.Library
        SuwayomiRouting.tabForAction(Constants.SHORTCUT_UPDATES) shouldBe SuwayomiTab.Updates
        SuwayomiRouting.tabForAction(Constants.SHORTCUT_SOURCES) shouldBe SuwayomiTab.Browse
        SuwayomiRouting.tabForAction(Constants.SHORTCUT_EXTENSIONS) shouldBe SuwayomiTab.Extensions
        SuwayomiRouting.tabForAction(Constants.SHORTCUT_DOWNLOADS) shouldBe SuwayomiTab.Downloads
    }

    @Test
    fun `local-only and unknown actions have no destination`() {
        SuwayomiRouting.tabForAction(Constants.SHORTCUT_HISTORY) shouldBe null
        SuwayomiRouting.tabForAction(Constants.SHORTCUT_MANGA) shouldBe null
        SuwayomiRouting.tabForAction("android.intent.action.MAIN") shouldBe null
        SuwayomiRouting.tabForAction(null) shouldBe null
    }

    @Test
    fun `tab order matches the tab row`() {
        SuwayomiTab.entries.map { it.name } shouldBe listOf("Library", "Browse", "Downloads", "Extensions", "Updates")
    }

    @Test
    fun `a requested tab is delivered once`() {
        SuwayomiRouting.request(SuwayomiTab.Updates)
        SuwayomiRouting.requestedTab.value shouldBe SuwayomiTab.Updates
        SuwayomiRouting.consume()
        SuwayomiRouting.requestedTab.value shouldBe null
    }

    @Test
    fun `the app root is the remote client and not the local home`() {
        // The root screen and the activity launch no local home, onboarding or updater.
        val main = java.io.File("src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt").readText()
        main.contains("screen = SuwayomiHomeScreen") shouldBe true
        for (local in listOf("screen = HomeScreen", "OnboardingScreen(", "ShowOnboarding()", "CheckForUpdates()", "RestoreBackupScreen(")) {
            main.contains(local) shouldBe false
        }
    }
}

class SuwayomiReaderPreferencesTest {

    @Test
    fun `defaults`() {
        val prefs = SuwayomiReaderPreferences(InMemoryPreferenceStore())
        prefs.readingMode.get() shouldBe SuwayomiReadingMode.RightToLeft
        prefs.imageScale.get() shouldBe SuwayomiImageScale.Fit
        prefs.keepScreenOn.get() shouldBe true
        prefs.showPageNumber.get() shouldBe true
    }

    @Test
    fun `values can be changed and read back`() {
        // InMemoryPreferenceStore hands out detached preferences, so this checks one instance's set/get only.
        val prefs = SuwayomiReaderPreferences(InMemoryPreferenceStore())
        prefs.readingMode.set(SuwayomiReadingMode.Webtoon)
        prefs.imageScale.set(SuwayomiImageScale.FitWidth)
        prefs.keepScreenOn.set(false)
        prefs.readingMode.get() shouldBe SuwayomiReadingMode.Webtoon
        prefs.imageScale.get() shouldBe SuwayomiImageScale.FitWidth
        prefs.keepScreenOn.get() shouldBe false
    }

    @Test
    fun `reading modes map to distinct layouts`() {
        with(SuwayomiReadingMode.RightToLeft) { listOf(horizontal, reversed, continuous) shouldBe listOf(true, true, false) }
        with(SuwayomiReadingMode.LeftToRight) { listOf(horizontal, reversed, continuous) shouldBe listOf(true, false, false) }
        with(SuwayomiReadingMode.Vertical) { listOf(horizontal, reversed, continuous) shouldBe listOf(false, false, false) }
        with(SuwayomiReadingMode.Webtoon) { listOf(horizontal, reversed, continuous) shouldBe listOf(false, false, true) }
    }

    @Test
    fun `the reader screen reads every retained option`() {
        val reader = java.io.File("src/main/java/mihon/suwayomi/ui/SuwayomiReaderScreen.kt").readText()
        for (option in listOf("readingMode", "imageScale", "keepScreenOn", "showPageNumber")) {
            reader.contains("prefs.$option") shouldBe true
        }
    }
}
