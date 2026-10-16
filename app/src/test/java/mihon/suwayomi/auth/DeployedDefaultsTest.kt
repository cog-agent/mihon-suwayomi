package mihon.suwayomi.auth

import io.kotest.matchers.shouldBe
import mihon.suwayomi.SuwayomiPreferences
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class DeployedDefaultsTest {

    @Test
    fun `fresh install is configured for the deployed server`() {
        val config = OidcPreferences(InMemoryPreferenceStore(), FakeCipher()).config()!!

        config.issuer shouldBe "https://auth.aaaaa.news"
        config.clientId shouldBe "acea2a2b-1337-4ba0-a050-dfd573f1667a"
        config.audience shouldBe "https://manga.aaaaa.news"
        config.scopeParameter shouldBe "openid profile email groups offline_access suwayomi.access"
        config.apiPermissions shouldBe listOf("suwayomi.access")
        SuwayomiPreferences(InMemoryPreferenceStore(), FakeCipher()).serverUrl().get() shouldBe
            "https://manga.aaaaa.news"
    }

    @Test
    fun `saved settings override the defaults`() {
        val prefs = OidcPreferences(InMemoryPreferenceStore(), FakeCipher())
        prefs.saveConfig(OidcRawConfig("https://id.test", "other", "https://api.test", "openid api.use", false))

        prefs.config()!!.issuer shouldBe "https://id.test"
        prefs.config()!!.clientId shouldBe "other"
    }
}
