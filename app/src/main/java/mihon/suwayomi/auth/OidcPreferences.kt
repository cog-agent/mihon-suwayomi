package mihon.suwayomi.auth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.suwayomi.SecretCipher
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The stored sign-in. The ID token itself is not kept, only the identity read from it after verification. Serialised
 * to one JSON blob that is encrypted as a whole.
 */
@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String?,
    val accessExpiresAtMs: Long,
    val issuer: String,
    val clientId: String,
    /** The API resource the access token was issued for. */
    val audience: String = "",
    val scope: String,
    val subject: String,
    val email: String?,
    val name: String?,
    val idTokenExpiresAtSec: Long?,
) {
    // Keep secrets out of logs if an instance is ever printed.
    override fun toString() = "StoredSession(issuer=$issuer, subject=$subject)"
}

/** Raw, unvalidated settings as shown in the settings form. */
data class OidcRawConfig(
    val issuer: String,
    val clientId: String,
    val audience: String,
    val scopes: String,
    val offlineAccess: Boolean,
)

@Inject
@SingleIn(AppScope::class)
class OidcPreferences(
    preferenceStore: PreferenceStore,
    private val cipher: SecretCipher,
) {
    private val issuer = preferenceStore.getString(Preference.privateKey("suwayomi_oidc_issuer"), "")
    private val clientId = preferenceStore.getString(Preference.privateKey("suwayomi_oidc_client_id"), "")
    private val audience = preferenceStore.getString(Preference.privateKey("suwayomi_oidc_audience"), "")
    private val scopes = preferenceStore.getString(Preference.privateKey("suwayomi_oidc_scopes"), OidcConfig.DEFAULT_SCOPES)
    private val offlineAccess = preferenceStore.getBoolean(Preference.privateKey("suwayomi_oidc_offline_access"), false)

    // Encrypted JSON blob; backup-excluded through the private key prefix.
    private val sessionBlob = preferenceStore.getString(Preference.privateKey("suwayomi_oidc_session_enc"), "")

    private val json = Json { ignoreUnknownKeys = true }
    private var decrypted: Pair<String, StoredSession?>? = null

    fun rawConfig() = OidcRawConfig(issuer.get(), clientId.get(), audience.get(), scopes.get(), offlineAccess.get())

    /** The validated configuration, or null when nothing valid is saved. */
    fun config(): OidcConfig? {
        val raw = rawConfig()
        return (OidcConfigValidator.validate(raw.issuer, raw.clientId, raw.audience, raw.scopes, raw.offlineAccess)
            as? OidcConfigResult.Valid)?.config
    }

    /**
     * Saves the settings after validating them. Changing the issuer, client id, resource or API permissions drops the
     * stored session, because those tokens were issued for something else.
     */
    @Synchronized
    fun saveConfig(raw: OidcRawConfig): OidcConfigResult {
        val result = OidcConfigValidator.validate(raw.issuer, raw.clientId, raw.audience, raw.scopes, raw.offlineAccess)
        if (result !is OidcConfigResult.Valid) return result
        val before = config()
        val config = result.config
        if (config.sessionKey != before?.sessionKey) clearSession()
        issuer.set(config.issuer)
        clientId.set(config.clientId)
        audience.set(config.audience)
        scopes.set(raw.scopes.trim())
        offlineAccess.set(raw.offlineAccess)
        return result
    }

    /** The stored session if it decrypts, parses and belongs to the current issuer and client id. */
    @Synchronized
    fun loadSession(): StoredSession? {
        val stored = sessionBlob.get()
        if (stored.isEmpty()) return null
        val session = if (decrypted?.first == stored) {
            decrypted!!.second
        } else {
            val parsed = try {
                cipher.decrypt(stored)?.let { json.decodeFromString<StoredSession>(it) }
            } catch (_: Exception) {
                null
            }
            decrypted = stored to parsed
            // Unreadable blob (lost key, restored backup, corruption): signed out, and no point keeping it.
            if (parsed == null) sessionBlob.delete()
            parsed
        }
        val config = config() ?: return null
        return session?.takeIf {
            it.issuer == config.issuer && it.clientId == config.clientId && it.audience == config.audience &&
                it.scope.split(' ').containsAll(config.apiPermissions)
        }
    }

    @Synchronized
    fun saveSession(session: StoredSession) {
        val blob = cipher.encrypt(json.encodeToString(session))
        // A single preference write, so a reader sees either the old or the new tokens, never a mix.
        sessionBlob.set(blob)
        decrypted = blob to session
    }

    @Synchronized
    internal fun rawSessionBlob(): String = sessionBlob.get()

    /** For tests: replaces the stored blob as a restart with different storage contents would. */
    @Synchronized
    internal fun setRawSessionBlob(value: String) {
        sessionBlob.set(value)
        decrypted = null
    }

    @Synchronized
    fun clearSession() {
        sessionBlob.delete()
        decrypted = null
    }
}
