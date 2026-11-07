package com.nexus.ai.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings storage. API keys live in EncryptedSharedPreferences (Android Keystore).
 * If the Keystore is unavailable, keys are kept in memory only - never written in plain text.
 */
class Prefs(ctx: Context) {

    private val plain: SharedPreferences = ctx.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences? = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "nexus_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    val secureStorageAvailable: Boolean get() = secure != null
    private val memoryKeys = HashMap<String, String>()

    private fun getSecret(name: String): String = secure?.getString(name, "") ?: memoryKeys[name].orEmpty()
    private fun putSecret(name: String, v: String) {
        if (secure != null) secure.edit().putString(name, v.trim()).apply() else memoryKeys[name] = v.trim()
    }

    // ---- AI providers ----
    /** "gemini" or "grok" */
    var provider: String
        get() = plain.getString("provider", "gemini") ?: "gemini"
        set(v) = plain.edit().putString("provider", v).apply()

    var geminiKey: String
        get() = getSecret("gemini_key")
        set(v) = putSecret("gemini_key", v)

    var grokKey: String
        get() = getSecret("grok_key")
        set(v) = putSecret("grok_key", v)

    // New pref names on purpose: old builds stored a model name that Google has since retired.
    var geminiModel: String
        get() = plain.getString("gemini_model_v2", "") ?: ""
        set(v) = plain.edit().putString("gemini_model_v2", v.trim()).apply()

    var grokModel: String
        get() = plain.getString("grok_model_v2", "") ?: ""
        set(v) = plain.edit().putString("grok_model_v2", v.trim()).apply()

    fun keyFor(p: String) = if (p == "grok") grokKey else geminiKey
    fun modelFor(p: String) = if (p == "grok") grokModel else geminiModel
    fun setModel(p: String, m: String) { if (p == "grok") grokModel = m else geminiModel = m }

    // ---- Personality / voice ----
    var modeId: String
        get() = plain.getString("mode", "jarvis") ?: "jarvis"
        set(v) = plain.edit().putString("mode", v).apply()

    var ttsVoice: String
        get() = plain.getString("tts_voice", "") ?: ""
        set(v) = plain.edit().putString("tts_voice", v).apply()

    var speechRate: Float
        get() = plain.getFloat("rate", 1.0f)
        set(v) = plain.edit().putFloat("rate", v).apply()

    var speechPitch: Float
        get() = plain.getFloat("pitch", 1.1f)
        set(v) = plain.edit().putFloat("pitch", v).apply()

    var speechVolume: Float
        get() = plain.getFloat("volume", 1.0f)
        set(v) = plain.edit().putFloat("volume", v).apply()

    var sttLanguage: String
        get() = plain.getString("stt_lang", "hi-IN") ?: "hi-IN"
        set(v) = plain.edit().putString("stt_lang", v).apply()

    var ttsLatinLanguage: String
        get() = plain.getString("tts_latin", "en-IN") ?: "en-IN"
        set(v) = plain.edit().putString("tts_latin", v).apply()

    var speakReplies: Boolean
        get() = plain.getBoolean("speak", true)
        set(v) = plain.edit().putBoolean("speak", v).apply()

    var continuousConversation: Boolean
        get() = plain.getBoolean("continuous", false)
        set(v) = plain.edit().putBoolean("continuous", v).apply()

    var wakeWord: Boolean
        get() = plain.getBoolean("wake_word", false)
        set(v) = plain.edit().putBoolean("wake_word", v).apply()

    var memoryEnabled: Boolean
        get() = plain.getBoolean("memory_on", true)
        set(v) = plain.edit().putBoolean("memory_on", v).apply()

    var userName: String
        get() = plain.getString("user_name", "") ?: ""
        set(v) = plain.edit().putString("user_name", v.trim()).apply()

    var customModeName: String
        get() = plain.getString("custom_name", "My Mode") ?: "My Mode"
        set(v) = plain.edit().putString("custom_name", v.trim()).apply()

    var customModePrompt: String
        get() = plain.getString("custom_prompt", "") ?: ""
        set(v) = plain.edit().putString("custom_prompt", v.trim()).apply()
}
