package com.arafat.tun2socks.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings persisted as one JSON blob, encrypted with an AES-GCM key held in the Android Keystore. */
class SettingsRepository private constructor(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tun2socks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private fun load(): Settings {
        prefs.getString(KEY_ENC, null)?.let { enc ->
            runCatching { json.decodeFromString<Settings>(SecureBox.decrypt(enc)) }.getOrNull()?.let { return it }
        }
        val legacy = prefs.getString(KEY_LEGACY, null)?.let {
            runCatching { json.decodeFromString<Settings>(it) }.getOrNull()
        } ?: return Settings()
        save(legacy)
        prefs.edit().remove(KEY_LEGACY).apply()
        return legacy
    }

    private fun save(settings: Settings) {
        val enc = SecureBox.encrypt(json.encodeToString(Settings.serializer(), settings))
        prefs.edit().putString(KEY_ENC, enc).apply()
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        save(next)
        _settings.value = next
    }

    /** Panic wipe: erase settings, the Keystore key and downloaded lists. */
    fun wipe(context: Context) {
        prefs.edit().clear().commit()
        SecureBox.deleteKey()
        context.filesDir.resolve("blocklists").deleteRecursively()
        _settings.value = Settings()
    }

    companion object {
        private const val KEY_LEGACY = "settings_json"
        private const val KEY_ENC = "settings_enc"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}

internal object SecureBox {
    private const val ALIAS = "tun2socks_settings"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun deleteKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(data: String): String {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_SIZE))
        return String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE))
    }
}
