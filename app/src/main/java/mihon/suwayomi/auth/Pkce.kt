package mihon.suwayomi.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Pkce {

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** Random URL-safe string; 32 bytes encode to 43 characters, the minimum RFC 7636 allows for a verifier. */
    fun randomToken(random: SecureRandom, bytes: Int = 32): String {
        require(bytes >= 32)
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return encoder.encodeToString(raw)
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))` without padding. */
    fun challengeS256(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
