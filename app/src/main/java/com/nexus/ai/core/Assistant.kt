package com.nexus.ai.core

import android.content.Context
import com.nexus.ai.ai.AiException
import com.nexus.ai.ai.ChatProvider
import com.nexus.ai.ai.GeminiClient
import com.nexus.ai.ai.GrokClient
import com.nexus.ai.ai.ModelPicker
import com.nexus.ai.voice.VoiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A sensitive action waiting for the user's yes/no (call, send message, delete...). */
data class Pending(val prompt: String, val run: () -> String)

/** The single brain of the app: settings, voice, AI providers, history, commands and the state the UI observes. */
object Assistant {
    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var voice: VoiceManager
        private set
    lateinit var notes: ItemStore
        private set
    lateinit var memory: ItemStore
        private set
    private lateinit var store: HistoryStore
    private lateinit var phone: PhoneCommands
    private lateinit var smart: SmartCommands

    private val gemini = GeminiClient()
    private val grok = GrokClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var chatJob: Job? = null
    private var initialized = false
    private var continueListening = false
    private var listenAfterSpeech = false

    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val state = MutableStateFlow(AvatarState.IDLE)
    val mood = MutableStateFlow(Mood.NEUTRAL)
    /** Live transcript while listening, or the reply as it streams in. */
    val liveText = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val pending = MutableStateFlow<Pending?>(null)

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices
    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<String> = _permissionRequests

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = Prefs(app)
        store = HistoryStore(app)
        notes = ItemStore(app, "notes.json")
        memory = ItemStore(app, "memory.json")
        phone = PhoneCommands(app)
        smart = SmartCommands(app, { prompt, run -> pending.value = Pending(prompt, run) }, { perm -> _permissionRequests.tryEmit(perm) })
        voice = VoiceManager(app, prefs)
        voice.init()
        messages.value = store.load()
        voice.onSpeakStart = { state.value = AvatarState.SPEAKING }
        voice.onSpeakDone = {
            state.value = AvatarState.IDLE
            level.value = 0f
            if (listenAfterSpeech || (prefs.continuousConversation && continueListening)) {
                listenAfterSpeech = false
                listen()
            }
        }
    }

    val currentMode: Mode get() = Modes.byId(prefs, prefs.modeId)

    private fun notice(s: String) { _notices.tryEmit(s) }

    private fun client(p: String): ChatProvider = if (p == "grok") grok else gemini
    fun providerName(p: String = prefs.provider) = client(p).displayName

    // ---------------- Voice input ----------------

    /** Starts listening. The caller must already hold the RECORD_AUDIO permission. */
    fun listen() {
        voice.stopSpeaking()
        chatJob?.cancel()
        continueListening = true
        state.value = AvatarState.LISTENING
        liveText.value = ""
        voice.startListening(
            partialCb = { liveText.value = it },
            finalCb = { text ->
                liveText.value = ""
                if (text.isBlank()) {
                    state.value = AvatarState.IDLE
                    if (prefs.continuousConversation && continueListening) listen()
                } else send(text, viaVoice = true)
            },
            errorCb = { msg ->
                state.value = AvatarState.IDLE
                liveText.value = ""
                continueListening = false
                if (msg.isNotBlank()) notice(msg)
            },
            levelCb = { level.value = it }
        )
    }

    fun stopAll() {
        continueListening = false
        listenAfterSpeech = false
        voice.stopListening()
        voice.stopSpeaking()
        chatJob?.cancel()
        state.value = AvatarState.IDLE
        level.value = 0f
        liveText.value = ""
    }

    // ---------------- Confirmations ----------------

    fun confirm(yes: Boolean) {
        val p = pending.value ?: return
        pending.value = null
        if (yes) {
            val result = try { p.run() } catch (e: Exception) { "Nahi ho paya: ${e.message}" }
            respondLocally(result, false)
        } else {
            respondLocally("Theek hai, cancel kar diya.", false)
        }
    }

    private val yesTokens = setOf("haan", "han", "ha", "yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "bhejo", "lagao", "हाँ", "हां", "जी", "ठीक", "सही")
    private val yesPhrases = listOf("kar do", "kardo", "bhej do", "laga do", "theek hai", "go ahead", "ठीक है", "कर दो", "भेज दो", "लगा दो")
    private val noTokens = setOf("nahi", "nahin", "no", "cancel", "mat", "ruko", "stop", "na", "नहीं", "रुको", "मत", "कैंसल", "नही")

    private fun yesNo(text: String): Boolean? {
        val s = text.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        val tokens = s.split(Regex("\\s+"))
        if (tokens.size > 6) return null
        if (tokens.any { it in noTokens }) return false
        if (tokens.any { it in yesTokens } || yesPhrases.any { s.contains(it) }) return true
        return null
    }

    // ---------------- Sending ----------------

    fun send(text: String, viaVoice: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        voice.stopSpeaking()
        chatJob?.cancel()
        continueListening = viaVoice
        append(Msg(true, clean))

        if (pending.value != null) {
            when (yesNo(clean)) {
                true -> { confirm(true); return }
                false -> { confirm(false); return }
                null -> pending.value = null
            }
        }

        // 1) switch personality mode
        Modes.matchVoiceCommand(prefs, clean)?.let { m ->
            prefs.modeId = m.id
            respondLocally("Theek hai, ${m.name} on kar diya.", viaVoice)
            return
        }
        // 2) contacts, messaging, reminders, notes, memory, screen, accessibility
        val smartRes = try { smart.handle(clean) } catch (e: Exception) { CmdResult(reply = "Command chalate waqt error aaya: ${e.message}") }
        if (smartRes != null) {
            if (smartRes.reply != null) { respondLocally(smartRes.reply, viaVoice); return }
            if (smartRes.aiPrompt != null) { askAi(viaVoice, smartRes.aiPrompt, smartRes.extra); return }
        }
        // 3) simple phone actions
        val local = try { phone.tryHandle(clean) } catch (e: Exception) { "Yeh command chalate waqt error aaya: ${e.message}" }
        if (local != null) { respondLocally(local, viaVoice); return }
        // 4) everything else goes to the AI
        askAi(viaVoice, null, null)
    }

    private fun respondLocally(reply: String, viaVoice: Boolean) {
        append(Msg(false, reply))
        mood.value = MoodDetector.fromReply(reply)
        val speaking = speakIfNeeded(reply)
        if (pending.value != null && viaVoice) {
            // The assistant just asked yes/no: listen for the answer.
            if (speaking) listenAfterSpeech = true else listen()
        }
    }

    private fun systemPrompt(extra: String?): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        val name = prefs.userName.takeIf { it.isNotBlank() }?.let { "The user's name is $it." } ?: ""
        val mem = if (prefs.memoryEnabled) memory.all().takeLast(30).joinToString("\n") { "- ${it.text}" } else ""
        val memBlock = if (mem.isNotBlank()) "\nThings the user asked you to remember:\n$mem" else ""
        val extraBlock = if (!extra.isNullOrBlank()) "\nContext provided by the app for this question:\n$extra" else ""
        return "${currentMode.prompt}\n$name\nCurrent date and time on the user's phone: $now.$memBlock$extraBlock"
    }

    private fun askAi(viaVoice: Boolean, instruction: String?, extra: String?) {
        state.value = AvatarState.THINKING
        liveText.value = ""
        var history = messages.value.takeLast(24).dropWhile { !it.fromUser }
        if (instruction != null && history.isNotEmpty()) history = history.dropLast(1) + Msg(true, instruction)
        val sys = systemPrompt(extra)
        chatJob = scope.launch {
            try {
                val reply = streamAi(sys, history) { liveText.value += it }
                liveText.value = ""
                append(Msg(false, reply))
                mood.value = MoodDetector.fromReply(reply)
                if (!speakIfNeeded(reply) && viaVoice && prefs.continuousConversation) listen()
            } catch (e: CancellationException) {
                liveText.value = ""
                throw e
            } catch (e: AiException) {
                fail(e.message ?: "AI error")
            } catch (e: Exception) {
                fail("Kuch gadbad ho gayi: ${e.message}")
            }
        }
    }

    /**
     * Streams from the selected provider. If the model name is rejected (retired / not allowed for this key),
     * it fetches the live model list, picks a working one, saves it, and retries once.
     */
    private suspend fun streamAi(system: String, history: List<Msg>, onDelta: (String) -> Unit): String {
        var retried = false
        while (true) {
            val p = prefs.provider
            val key = prefs.keyFor(p)
            if (key.isBlank()) throw AiException("${providerName(p)} API key set nahi hai. Settings mein key daalo.")
            var model = prefs.modelFor(p)
            if (model.isBlank()) {
                model = autoPick(p, key) ?: throw AiException("Is key ke liye koi model nahi mila. Settings mein 'Fetch models' dabao.")
                prefs.setModel(p, model)
            }
            try {
                return client(p).stream(key, model, system, history, onDelta)
            } catch (e: AiException) {
                if (!retried && e.isModelProblem) {
                    retried = true
                    val best = autoPick(p, key)
                    if (best != null && best != model) {
                        prefs.setModel(p, best)
                        notice("Model '$model' kaam nahi kar raha tha. Maine '$best' chun liya.")
                        liveText.value = ""
                        continue
                    }
                }
                throw e
            }
        }
    }

    private suspend fun autoPick(p: String, key: String): String? = try {
        ModelPicker.best(client(p).listModels(key), p)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun fail(msg: String) {
        liveText.value = ""
        state.value = AvatarState.IDLE
        continueListening = false
        mood.value = Mood.SAD
        notice(msg)
    }

    private fun speakIfNeeded(reply: String): Boolean {
        if (prefs.speakReplies && voice.speak(reply)) {
            state.value = AvatarState.SPEAKING
            return true
        }
        state.value = AvatarState.IDLE
        if (prefs.speakReplies && !voice.isTtsReady) notice("Text-to-speech engine ready nahi hai. Phone Settings > Text-to-speech check karo.")
        return false
    }

    private fun append(m: Msg) {
        messages.value = (messages.value + m).takeLast(200)
        store.save(messages.value)
    }

    fun clearHistory() {
        chatJob?.cancel()
        messages.value = emptyList()
        store.clear()
    }

    // ---------------- Used by Settings and Studio ----------------

    /** Models this key can really use (live from the provider). */
    suspend fun fetchModels(p: String): List<String> = client(p).listModels(prefs.keyFor(p))

    suspend fun testConnection(): String = try {
        val p = prefs.provider
        streamAi("Reply with the single word: ok", listOf(Msg(true, "ping"))) {}
        "Connection OK ✔  (${providerName(p)} • ${prefs.modelFor(p)})"
    } catch (e: AiException) {
        e.message ?: "AI error"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    /** One-off generation (website studio). Does not touch chat history or speak. */
    suspend fun completeOnce(system: String, history: List<Msg>, onDelta: (String) -> Unit): String = streamAi(system, history, onDelta)
}
