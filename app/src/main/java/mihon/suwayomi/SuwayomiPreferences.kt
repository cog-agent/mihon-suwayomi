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
    private val serverUrl = preferenceStore.getString(Preference.privateKey("suwayomi_server_url"), "")

    fun serverUrl() = serverUrl

    // Only ever holds the encrypted form; use getApiKey/setApiKey.
    private val encryptedApiKey = preferenceStore.getString(Preference.privateKey("suwayomi_api_key_enc"), "")

    // Keystore decryption is slow and the key is read for every request, so keep the last result.
    private var decrypted: Pair<String, String>? = null

    @Synchronized
    fun getApiKey(): String {
        val stored = encryptedApiKey.get()
        if (stored.isEmpty()) return ""
        decrypted?.takeIf { it.first == stored }?.let { return it.second }
        val plain = cipher.decrypt(stored) ?: return ""
        decrypted = stored to plain
        return plain
    }

    fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        encryptedApiKey.set(if (trimmed.isEmpty()) "" else cipher.encrypt(trimmed))
    }

    /**
     * Saves the server. A stored key belongs to the server it was entered for, so changing the URL without entering a
     * new key drops the old one rather than sending it to a different host.
     */
    fun saveServer(normalizedUrl: String, apiKey: String) {
        val changed = serverUrl.get() != normalizedUrl
        serverUrl.set(normalizedUrl)
        if (apiKey.isNotBlank() || changed) setApiKey(apiKey)
    }

    fun hasApiKey(): Boolean = encryptedApiKey.get().isNotEmpty()

    fun clear() {
        serverUrl.delete()
        encryptedApiKey.delete()
    }
}
