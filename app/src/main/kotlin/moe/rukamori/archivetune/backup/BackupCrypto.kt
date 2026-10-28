/*
 * Pixel Notes — password protection for the sign-in part of a backup.
 *
 * Account details (the khinsider sign-in) are written to their own
 * entry in the backup, encrypted with AES-256-GCM. The key comes from the user's password via
 * PBKDF2-HMAC-SHA256 with a random salt, so the file is useless without the password.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    /** Zip entry holding the encrypted account details. */
    const val ACCOUNT_ENTRY = "account.enc"

    /** Marks backups made by Pixel Notes with account details kept out of settings.xml. */
    const val FORMAT_ENTRY = "pixelnotes-backup-format"
    const val FORMAT_VERSION = "2"

    const val MIN_PASSWORD_LENGTH = 8

    private val MAGIC = "PNBK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 210_000

    class WrongPasswordException : Exception("Wrong backup password")

    fun encrypt(
        plain: ByteArray,
        password: CharArray,
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    fun decrypt(
        data: ByteArray,
        password: CharArray,
    ): ByteArray {
        val header = MAGIC.size + SALT_BYTES + IV_BYTES
        require(data.size > header && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Unrecognised encrypted backup data"
        }
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = data.copyOfRange(MAGIC.size + SALT_BYTES, header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(data, header, data.size - header)
        } catch (_: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
