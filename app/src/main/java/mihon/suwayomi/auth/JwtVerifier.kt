package mihon.suwayomi.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class Jwk(val kid: String?, val kty: String, val use: String?, val alg: String?, val key: PublicKey, val curve: String?)

/** A JWT whose signature has been verified. */
class VerifiedJwt(val header: JsonObject, val claims: JsonObject)

internal object JwksParser {

    private val decoder = Base64.getUrlDecoder()

    /** Keys of unsupported or malformed kinds are skipped. */
    fun parse(doc: JsonObject): List<Jwk> {
        val keys = doc["keys"] as? JsonArray ?: throw OidcException(OidcError.DiscoveryFailed)
        return keys.mapNotNull { (it as? JsonObject)?.let(::parseKey) }
    }

    private fun parseKey(k: JsonObject): Jwk? = try {
        val kty = k.string("kty") ?: return null
        when (kty) {
            "RSA" -> {
                val n = BigInteger(1, decoder.decode(k.string("n") ?: return null))
                val e = BigInteger(1, decoder.decode(k.string("e") ?: return null))
                if (n.bitLength() < 2048) return null
                Jwk(k.string("kid"), kty, k.string("use"), k.string("alg"), KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)), null)
            }
            "EC" -> {
                val crv = k.string("crv") ?: return null
                val curveName = when (crv) {
                    "P-256" -> "secp256r1"
                    "P-384" -> "secp384r1"
                    else -> return null
                }
                val x = BigInteger(1, decoder.decode(k.string("x") ?: return null))
                val y = BigInteger(1, decoder.decode(k.string("y") ?: return null))
                val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(curveName)) }
                    .getParameterSpec(ECParameterSpec::class.java)
                val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
                Jwk(k.string("kid"), kty, k.string("use"), k.string("alg"), key, crv)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Signature and claim checks for compact JWTs. Only an allow-list of asymmetric algorithms is accepted, so `none` and
 * the HMAC family can never be used, and the key type must match the algorithm, which rules out key-confusion attacks
 * where a public key is used as an HMAC secret.
 */
internal class JwtVerifier(private val nowSeconds: () -> Long, private val skewSeconds: Long = 60) {

    private val decoder = Base64.getUrlDecoder()
    private val json = Json { isLenient = false }

    private class Alg(val jca: String, val kty: String, val curve: String? = null, val rawSigBytes: Int = 0)

    private val algorithms = mapOf(
        "RS256" to Alg("SHA256withRSA", "RSA"),
        "RS384" to Alg("SHA384withRSA", "RSA"),
        "RS512" to Alg("SHA512withRSA", "RSA"),
        "ES256" to Alg("SHA256withECDSA", "EC", "P-256", 64),
        "ES384" to Alg("SHA384withECDSA", "EC", "P-384", 96),
    )

    fun looksLikeJwt(token: String): Boolean {
        val parts = token.split('.')
        if (parts.size != 3) return false
        return try {
            json.parseToJsonElement(String(decoder.decode(parts[0]), Charsets.UTF_8)) is JsonObject
        } catch (_: Exception) {
            false
        }
    }

    /** @throws OidcException with [OidcError.UnknownSigningKey] when the `kid` is not in [keys]. */
    fun verifySignature(token: String, keys: List<Jwk>): VerifiedJwt {
        val parts = token.split('.')
        if (parts.size != 3) throw invalid()
        val header: JsonObject
        val claims: JsonObject
        val signature: ByteArray
        try {
            header = json.parseToJsonElement(String(decoder.decode(parts[0]), Charsets.UTF_8)) as? JsonObject ?: throw invalid()
            claims = json.parseToJsonElement(String(decoder.decode(parts[1]), Charsets.UTF_8)) as? JsonObject ?: throw invalid()
            signature = decoder.decode(parts[2])
        } catch (e: OidcException) {
            throw e
        } catch (_: Exception) {
            throw invalid()
        }
        if ("crit" in header) throw invalid()
        val algName = header.string("alg") ?: throw invalid()
        val alg = algorithms[algName] ?: throw invalid()

        val kid = header.string("kid")
        val byKid = if (kid != null) keys.filter { it.kid == kid } else keys
        if (kid != null && byKid.isEmpty()) throw OidcException(OidcError.UnknownSigningKey)
        val candidates = byKid.filter {
            it.kty == alg.kty && (it.use == null || it.use == "sig") && (it.alg == null || it.alg == algName) &&
                (alg.curve == null || it.curve == alg.curve)
        }
        val jwk = (if (kid != null) candidates.firstOrNull() else candidates.singleOrNull()) ?: throw invalid()

        val sigBytes = if (alg.kty == "EC") {
            if (signature.size != alg.rawSigBytes) throw invalid()
            rawToDer(signature)
        } else {
            signature
        }
        val ok = try {
            Signature.getInstance(alg.jca).run {
                initVerify(jwk.key)
                update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
                verify(sigBytes)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) throw invalid()
        return VerifiedJwt(header, claims)
    }

    fun validateIdToken(jwt: VerifiedJwt, issuer: String, clientId: String, nonce: String?) {
        val c = jwt.claims
        if (c.string("iss") != issuer) throw invalid()
        val audiences = audiences(c) ?: throw invalid()
        if (clientId !in audiences) throw invalid()
        val azp = c.string("azp")
        if (audiences.size > 1 && azp == null) throw invalid()
        if (azp != null && azp != clientId) throw invalid()
        if (c.string("sub").isNullOrEmpty()) throw invalid()
        checkTimes(c, requireIat = true)
        if (nonce != null && c.string("nonce") != nonce) throw invalid()
    }

    /**
     * Pocket ID access tokens are signed JWTs (RFC 9068): `aud` is the requested API resource and `scope` lists the
     * granted permissions. All of issuer, audience, expiry and every permission in [requiredPermissions] must hold.
     * `scope` is read as a space-delimited string (RFC 9068) or an array of strings.
     */
    fun validateAccessToken(jwt: VerifiedJwt, issuer: String, expectedAudience: String, requiredPermissions: List<String>) {
        val c = jwt.claims
        if (c.string("iss") != issuer) throw invalid()
        if (expectedAudience !in (audiences(c) ?: throw invalid())) throw invalid()
        if (c.string("sub").isNullOrEmpty()) throw invalid()
        checkTimes(c, requireIat = false)
        val granted = scopeClaim(c) ?: throw OidcException(OidcError.InsufficientScope)
        if (!granted.containsAll(requiredPermissions)) throw OidcException(OidcError.InsufficientScope)
    }

    fun scopeClaim(c: JsonObject): List<String>? = when (val scope = c["scope"]) {
        is JsonPrimitive -> if (scope.isString) scope.content.split(' ').filter { it.isNotEmpty() } else null
        is JsonArray -> scope.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        else -> null
    }

    private fun audiences(c: JsonObject): List<String>? = when (val aud = c["aud"]) {
        is JsonPrimitive -> if (aud.isString) listOf(aud.content) else null
        is JsonArray -> aud.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        else -> null
    }

    private fun checkTimes(c: JsonObject, requireIat: Boolean) {
        val now = nowSeconds()
        val exp = c.number("exp") ?: throw invalid()
        if (exp + skewSeconds < now) throw invalid()
        val iat = c.number("iat")
        if (iat == null && requireIat) throw invalid()
        if (iat != null && iat - skewSeconds > now) throw invalid()
        val nbf = c.number("nbf")
        if (nbf != null && nbf - skewSeconds > now) throw invalid()
    }

    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun invalid() = OidcException(OidcError.InvalidToken)

    /** JOSE ECDSA signatures are `R || S`; the JCA wants a DER sequence. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i] == 0.toByte()) i++
            val v = b.copyOfRange(i, b.size)
            return if (v[0] < 0) byteArrayOf(0) + v else v
        }
        val half = raw.size / 2
        val r = int(raw.copyOfRange(0, half))
        val s = int(raw.copyOfRange(half, raw.size))
        val len = 2 + r.size + 2 + s.size
        val lenBytes = if (len < 128) byteArrayOf(len.toByte()) else byteArrayOf(0x81.toByte(), len.toByte())
        return byteArrayOf(0x30) + lenBytes + byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
    }
}

