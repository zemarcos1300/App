package com.veil.launcher

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Cofre cifrado (AES-GCM com chave do Android Keystore).
 * É um singleton para que a interface, a tela de bloqueio e o serviço
 * em segundo plano compartilhem o mesmo estado.
 */
internal class SecureStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("veil_vault", Context.MODE_PRIVATE)
    private val alias = "veil_aes_key"
    private var cache: JSONObject? = null

    companion object {
        @Volatile private var instance: SecureStore? = null

        fun get(context: Context): SecureStore =
            instance ?: synchronized(this) {
                instance ?: SecureStore(context.applicationContext).also { instance = it }
            }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    private fun readData(): JSONObject {
        cache?.let { return it }
        val encoded = prefs.getString("vault_blob", null)
        if (encoded == null) {
            return JSONObject().also { cache = it }
        }
        return try {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val ivSize = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8)).also { cache = it }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun writeData(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = byteArrayOf(iv.size.toByte()) + iv + encrypted
        prefs.edit().putString(
            "vault_blob",
            android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP)
        ).apply()
        cache = data
    }

    @Synchronized fun configured(): Boolean = readData().has("hash")

    @Synchronized fun setCredentials(pin: String, gesture: String, symbols: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive("$pin|$gesture|$symbols", salt)
        val data = readData()
        data.put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
        data.put("hash", android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))
        data.put("failures", 0)
        data.put("lock_until", 0L)
        writeData(data)
    }

    @Synchronized fun verify(pin: String, gesture: String, symbols: String): Boolean {
        val data = readData()
        val until = data.optLong("lock_until", 0L)
        if (System.currentTimeMillis() < until) return false
        val saltText = data.optString("salt", "")
        val hashText = data.optString("hash", "")
        if (saltText.isBlank() || hashText.isBlank()) return false
        val salt = android.util.Base64.decode(saltText, android.util.Base64.NO_WRAP)
        val expected = android.util.Base64.decode(hashText, android.util.Base64.NO_WRAP)
        val actual = derive("$pin|$gesture|$symbols", salt)
        val valid = MessageDigest.isEqual(expected, actual)
        if (valid) {
            data.put("failures", 0)
            data.put("lock_until", 0L)
        } else {
            val failures = data.optInt("failures", 0) + 1
            data.put("failures", failures)
            if (failures >= 5) {
                data.put("lock_until", System.currentTimeMillis() + 30_000L)
                data.put("failures", 0)
            }
        }
        writeData(data)
        return valid
    }

    @Synchronized fun remainingLockMs(): Long =
        (readData().optLong("lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    @Synchronized fun protectedApps(): Set<String> {
        val array = readData().optJSONArray("protected") ?: JSONArray()
        return (0 until array.length()).map { array.optString(it) }.toSet()
    }

    @Synchronized fun setProtected(packageName: String, enabled: Boolean) {
        val set = protectedApps().toMutableSet()
        if (enabled) set.add(packageName) else set.remove(packageName)
        val data = readData()
        data.put("protected", JSONArray(set.toList()))
        writeData(data)
    }

    @Synchronized fun lockOnUnlock(): Boolean = readData().optBoolean("lock_on_unlock", false)

    @Synchronized fun setLockOnUnlock(value: Boolean) {
        val data = readData()
        data.put("lock_on_unlock", value)
        writeData(data)
    }

    /** Segundos até exigir a senha de novo depois de sair de um app protegido (0 = ao sair). */
    @Synchronized fun graceSeconds(): Int = readData().optInt("grace", 0)

    @Synchronized fun setGraceSeconds(value: Int) {
        val data = readData()
        data.put("grace", value)
        writeData(data)
    }

    @Synchronized fun clearCredentials() {
        val data = readData()
        data.remove("hash"); data.remove("salt")
        data.put("failures", 0); data.put("lock_until", 0L)
        writeData(data)
    }

    private fun derive(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, 210_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }
}
