package mihon.suwayomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class SuwayomiPreferences(
    private val preferenceStore: PreferenceStore,
    private val cipher: SecretCipher,
) {

    /** Normalized server base URL, or empty when not configured. */
    fun serverUrl() = preferenceStore.getString(Preference.privateKey("suwayomi_server_url"), "")

    // Only ever holds the encrypted form; use getApiKey/setApiKey.
    private fun encryptedApiKey() = preferenceStore.getString(Preference.privateKey("suwayomi_api_key_enc"), "")

    fun getApiKey(): String {
        val stored = encryptedApiKey().get()
        if (stored.isEmpty()) return ""
        return cipher.decrypt(stored) ?: ""
    }

    fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        encryptedApiKey().set(if (trimmed.isEmpty()) "" else cipher.encrypt(trimmed))
    }

    fun hasApiKey(): Boolean = encryptedApiKey().get().isNotEmpty()

    fun clear() {
        serverUrl().delete()
        encryptedApiKey().delete()
    }
}
