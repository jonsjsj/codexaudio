package no.bellaybestia.audex.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (RFC 7636) helpers for the ABS native-OIDC mobile flow. ABS requires the
 * S256 method for the mobile redirect (verified against ABS 2.35.1), so only
 * S256 is implemented here.
 */
object Pkce {

    private val random = SecureRandom()
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /** A high-entropy code verifier (RFC 7636 §4.1: 43–128 URL-safe chars). */
    fun newVerifier(): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, FLAGS)
    }

    /** BASE64URL(SHA-256(verifier)) — the `code_challenge` for method S256. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, FLAGS)
    }

    /** Opaque anti-forgery `state` value. */
    fun newState(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, FLAGS)
    }
}
