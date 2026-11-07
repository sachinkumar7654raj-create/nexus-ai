package com.nexus.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.nexus.ai.core.Prefs
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Text-to-speech and speech-to-text using the engines installed on the phone (Google by default). */
class VoiceManager(private val ctx: Context, private val prefs: Prefs) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val counter = AtomicInteger(0)
    @Volatile private var lastUtteranceId: String? = null

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        main.post { onSpeakStart?.invoke() }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == lastUtteranceId) main.post { onSpeakDone?.invoke() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == lastUtteranceId) main.post { onSpeakDone?.invoke() }
                    }
                })
            }
        }
    }

    val isTtsReady: Boolean get() = ttsReady

    /** Voices for Hindi and English installed on the device. Android does not expose gender, so the user picks by listening. */
    fun availableVoices(): List<Voice> = try {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "hi" || it.locale.language == "en" }
            .filter { !it.features.contains("notInstalled") }
            .sortedWith(compareBy({ it.locale.language != "hi" }, { it.locale.toString() }, { it.name }))
    } catch (e: Exception) {
        emptyList()
    }

    private fun isDevanagari(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        return text.count { it in 'ऀ'..'ॿ' } > letters * 0.25
    }

    private fun configure(text: String) {
        val t = tts ?: return
        val locale = if (isDevanagari(text)) Locale("hi", "IN") else Locale.forLanguageTag(prefs.ttsLatinLanguage)
        try {
            val all = t.voices.orEmpty()
            val chosen = all.firstOrNull { it.name == prefs.ttsVoice }
            if (chosen != null && chosen.locale.language == locale.language) {
                t.voice = chosen
            } else {
                t.language = locale
                all.firstOrNull { it.locale.language == locale.language && it.name.contains("female", true) }?.let { t.voice = it }
            }
        } catch (e: Exception) {
            t.language = locale
        }
        t.setSpeechRate(prefs.speechRate)
        t.setPitch(prefs.speechPitch)
    }

    private fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " code block ")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        .replace(Regex("\\s+\\n"), "\n")
        .trim()

    /** Speaks [text] sentence by sentence. Returns false if TTS is not available. */
    fun speak(text: String): Boolean {
        val t = tts
        if (t == null || !ttsReady) return false
        val spoken = clean(text)
        if (spoken.isEmpty()) return false
        configure(spoken)
        val parts = spoken.split(Regex("(?<=[.!?।])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        t.stop()
        parts.forEachIndexed { i, p ->
            val id = "nx-${counter.incrementAndGet()}"
            if (i == parts.lastIndex) lastUtteranceId = id
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, prefs.speechVolume) }
            t.speak(p.take(3500), if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
        }
        return true
    }

    fun stopSpeaking() {
        lastUtteranceId = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    val isSpeaking: Boolean get() = try { tts?.isSpeaking == true } catch (e: Exception) { false }

    /** Short sample so the user can test the chosen voice. */
    fun sample(voiceName: String?) {
        val t = tts ?: return
        if (!ttsReady) return
        try {
            val v = t.voices.orEmpty().firstOrNull { it.name == voiceName }
            if (v != null) t.voice = v
            t.setSpeechRate(prefs.speechRate)
            t.setPitch(prefs.speechPitch)
            val line = if (v?.locale?.language == "hi") "नमस्ते, मैं नेक्सस हूँ। आपकी क्या मदद कर सकती हूँ?" else "Hello, I'm Nexus. How can I help you today?"
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, prefs.speechVolume) }
            lastUtteranceId = null
            t.speak(line, TextToSpeech.QUEUE_FLUSH, params, "nx-sample")
        } catch (_: Exception) {}
    }

    // ---------------- Speech to text ----------------

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun startListening(
        partialCb: (String) -> Unit,
        finalCb: (String) -> Unit,
        errorCb: (String) -> Unit,
        levelCb: (Float) -> Unit
    ) {
        main.post {
            destroyRecognizer()
            if (!isRecognitionAvailable()) {
                errorCb("Is phone mein speech recognition service nahi mili. Google app / Speech Services install karo.")
                return@post
            }
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { levelCb(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { levelCb(0f) }
                override fun onError(error: Int) {
                    levelCb(0f)
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission chahiye."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition ke liye internet chahiye."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy hai, dobara try karo."
                        SpeechRecognizer.ERROR_CLIENT -> "ignore"
                        else -> "Speech recognition error ($error)."
                    }
                    if (msg != "ignore") errorCb(msg)
                }
                override fun onResults(results: Bundle?) {
                    levelCb(0f)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    finalCb(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(partialCb)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.sttLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            }
            r.startListening(i)
        }
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            try { it.cancel(); it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }

    fun stopListening() {
        main.post { destroyRecognizer() }
    }

    fun shutdown() {
        stopListening()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
