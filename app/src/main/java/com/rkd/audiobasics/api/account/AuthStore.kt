package com.rkd.audiobasics.api.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the account session encrypted on disk.
 *
 * - The AES-256-GCM key lives in the Android Keystore and never leaves it.
 * - The file lives in noBackupFilesDir, so cloud backups and device transfers never carry it
 *   (the app has allowBackup=true, and a copied cookie must not travel to another device).
 * - Writes go to a temp file first and are renamed over the old one, so a kill halfway
 *   through a save can never leave a half-written session behind.
 *
 * File layout: [1 byte format version][12 byte IV][ciphertext + GCM tag].
 */
internal class AuthStore(context: Context) {

    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val tmp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
    private val lock = Any()

    /**
     * Returns the stored session, or null when there is none OR it can't be read.
     * An unreadable file is deliberately NOT deleted: a transient Keystore error must not
     * silently destroy a good session. The next successful sign-in overwrites it anyway.
     */
    fun load(): AccountSession? = synchronized(lock) {
        if (!file.exists()) return null
        try {
            val blob = file.readBytes()
            require(blob.size > 1 + IV_BYTES + TAG_BYTES) { "stored session is too short" }
            require(blob[0] == FORMAT_VERSION) { "unknown stored session format ${blob[0]}" }
            val iv = blob.copyOfRange(1, 1 + IV_BYTES)
            val ciphertext = blob.copyOfRange(1 + IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            AccountSession.fromJson(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Stored account session could not be read; treating as signed out")
            null
        }
    }

    /** True only if the session is fully encrypted and in place on disk. */
    fun save(session: AccountSession): Boolean = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
            val ciphertext = cipher.doFinal(session.toJson().toByteArray(Charsets.UTF_8))

            val out = ByteArray(1 + iv.size + ciphertext.size)
            out[0] = FORMAT_VERSION
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ciphertext, 0, out, 1 + iv.size, ciphertext.size)

            tmp.writeBytes(out)
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "could not move the new session into place" }
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not save the account session")
            tmp.delete()
            false
        }
    }

    fun clear() = synchronized(lock) {
        file.delete()
        tmp.delete()
        Unit
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "Account"
        const val FILE_NAME = "account.bin"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "audiobasics_account_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
    }
}
