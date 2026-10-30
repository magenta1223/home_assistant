package com.homeassistant.adapter.outbound.identity

import com.homeassistant.application.port.output.identity.PasswordHash
import com.homeassistant.application.port.output.identity.PasswordHasher
import com.homeassistant.domain.identity.Password
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 encoded as `pbkdf2-sha256$iterations$salt$hash`, so the cost can rise without a migration. */
class Pbkdf2PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
) : PasswordHasher {
    override fun hash(password: Password): PasswordHash {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val derived = derive(password, salt, iterations)
        return PasswordHash(
            listOf(SCHEME, iterations, encoder.encodeToString(salt), encoder.encodeToString(derived)).joinToString("$"),
        )
    }

    override fun matches(password: Password, hash: PasswordHash): Boolean {
        val (scheme, storedIterations, salt, derived) = hash.encoded.split("$")
        require(scheme == SCHEME) { "Unsupported password hash scheme: $scheme" }
        val expected = decoder.decode(derived)
        return MessageDigest.isEqual(expected, derive(password, decoder.decode(salt), storedIterations.toInt()))
    }

    private fun derive(password: Password, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(ALGORITHM)
            .generateSecret(PBEKeySpec(password.value.toCharArray(), salt, iterations, HASH_BITS))
            .encoded

    private companion object {
        const val SCHEME = "pbkdf2-sha256"
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS = 600_000
        const val SALT_BYTES = 16
        const val HASH_BITS = 256
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
    }
}
