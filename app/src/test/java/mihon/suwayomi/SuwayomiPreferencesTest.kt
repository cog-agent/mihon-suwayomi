package mihon.suwayomi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

class SuwayomiPreferencesTest {

    // Reversible stand-in so the tests can tell plaintext from stored data without the Android Keystore.
    private class FakeCipher(var canDecrypt: Boolean = true) : SecretCipher {
        override fun encrypt(plainText: String) = "enc:" + plainText.reversed()
        override fun decrypt(encoded: String) = if (canDecrypt) encoded.removePrefix("enc:").reversed() else null
    }

    private val store = InMemoryPreferenceStore()
    private val cipher = FakeCipher()
    private val preferences = SuwayomiPreferences(store, cipher)

    @Test
    fun `api key round trips and is not stored in plaintext`() {
        preferences.setApiKey("  fake-key-for-tests ")

        preferences.getApiKey() shouldBe "fake-key-for-tests"
        preferences.hasApiKey() shouldBe true
        val raw = store.getString(Preference.privateKey("suwayomi_api_key_enc"), "").get()
        raw shouldNotBe "fake-key-for-tests"
        raw.contains("fake-key-for-tests") shouldBe false
    }

    @Test
    fun `blank key clears the stored key`() {
        preferences.setApiKey("fake-key-for-tests")
        preferences.setApiKey("   ")

        preferences.hasApiKey() shouldBe false
        preferences.getApiKey() shouldBe ""
    }

    @Test
    fun `undecryptable key reads as empty`() {
        preferences.setApiKey("fake-key-for-tests")
        cipher.canDecrypt = false

        preferences.getApiKey() shouldBe ""
    }

    @Test
    fun `clear removes url and key`() {
        preferences.serverUrl().set("http://suwayomi.test:4567")
        preferences.setApiKey("fake-key-for-tests")

        preferences.clear()

        preferences.serverUrl().get() shouldBe ""
        preferences.hasApiKey() shouldBe false
    }

    @Test
    fun `settings are private so backups skip them`() {
        Preference.isPrivate(Preference.privateKey("suwayomi_server_url")) shouldBe true
    }
}
