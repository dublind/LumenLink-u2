package cl.ti3042.lumenlink.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    const val ITERATIONS = 210_000
    // Una sal distinta evita que dos cuentas tengan el mismo hash por usar la misma clave.
    fun salt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun hash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    // Compara los derivados sin detenerse en el primer byte diferente.
    fun matches(password: CharArray, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(password, salt), expected)
    fun validUser(value: String): Boolean = value.matches(Regex("[a-zA-Z0-9._-]{3,32}"))
    fun validPassword(value: String): Boolean = value.length in 6..128
}
