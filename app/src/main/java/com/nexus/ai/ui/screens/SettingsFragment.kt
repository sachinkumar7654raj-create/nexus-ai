package com.nexus.ai.ui.screens

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.nexus.ai.R
import com.nexus.ai.ai.AiException
import com.nexus.ai.ai.ModelPicker
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.Inbox
import com.nexus.ai.core.Reminders
import com.nexus.ai.service.WakeService
import com.nexus.ai.ui.Perm
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var pendingPerm: String? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingPerm == Manifest.permission.RECORD_AUDIO && granted && Assistant.prefs.wakeWord) startWake()
        refreshPerms()
    }

    private val providers = listOf("Gemini (Google)" to "gemini", "Grok (xAI)" to "grok")
    private val sttLangs = listOf("Hindi (India)" to "hi-IN", "English (India)" to "en-IN", "English (US)" to "en-US", "English (UK)" to "en-GB")
    private val latinLangs = listOf("English (India)" to "en-IN", "English (US)" to "en-US", "English (UK)" to "en-GB")

    private var voiceNames: List<String?> = listOf(null)
    private var modelIds: List<String> = emptyList()
    private lateinit var root: View

    private fun <T : View> id(i: Int): T = root.findViewById(i)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        root = view
        val prefs = Assistant.prefs
        val ctx = requireContext()

        id<EditText>(R.id.userName).setText(prefs.userName)

        // ---------------- AI provider, keys, models ----------------
        id<EditText>(R.id.geminiKey).setText(prefs.geminiKey)
        id<EditText>(R.id.grokKey).setText(prefs.grokKey)
        id<EditText>(R.id.modelName).setText(prefs.modelFor(prefs.provider))

        val provSpinner = id<Spinner>(R.id.providerSpinner)
        provSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, providers.map { it.first })
        provSpinner.setSelection(providers.indexOfFirst { it.second == prefs.provider }.coerceAtLeast(0), false)
        provSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, i: Long) {
                prefs.provider = providers[pos].second
                id<EditText>(R.id.modelName).setText(prefs.modelFor(prefs.provider))
                setModelList(emptyList())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        setModelList(emptyList())
        val modelSpinner = id<Spinner>(R.id.modelSpinner)
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, i: Long) {
                if (pos > 0 && pos - 1 < modelIds.size) id<EditText>(R.id.modelName).setText(modelIds[pos - 1])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val aiStatus = id<TextView>(R.id.aiStatus)
        id<Button>(R.id.fetchModels).setOnClickListener {
            saveAi()
            aiStatus.text = "Models la rahi hoon…"
            val prov = prefs.provider
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val list = Assistant.fetchModels(prov)
                    setModelList(list)
                    if (list.isEmpty()) aiStatus.text = "Is key ke liye koi chat model nahi mila."
                    else {
                        val cur = id<EditText>(R.id.modelName).text.toString().trim()
                        if (cur.isEmpty() || cur !in list) ModelPicker.best(list, prov)?.let { id<EditText>(R.id.modelName).setText(it) }
                        aiStatus.text = "${list.size} models mile. Upar list se chuno ya jo auto-select hua wahi rakho, phir 'Save AI settings' dabao."
                    }
                } catch (e: AiException) {
                    aiStatus.text = e.message ?: "Error"
                } catch (e: Exception) {
                    aiStatus.text = "Error: ${e.message}"
                }
            }
        }
        id<Button>(R.id.saveAi).setOnClickListener { saveAi(); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() }
        id<Button>(R.id.testAi).setOnClickListener {
            saveAi()
            aiStatus.text = "Testing…"
            viewLifecycleOwner.lifecycleScope.launch {
                aiStatus.text = Assistant.testConnection()
                id<EditText>(R.id.modelName).setText(prefs.modelFor(prefs.provider))
            }
        }

        // ---------------- Voice ----------------
        val speak = id<MaterialSwitch>(R.id.speakSwitch)
        speak.isChecked = prefs.speakReplies
        speak.setOnCheckedChangeListener { _, c -> prefs.speakReplies = c }
        val cont = id<MaterialSwitch>(R.id.continuousSwitch)
        cont.isChecked = prefs.continuousConversation
        cont.setOnCheckedChangeListener { _, c -> prefs.continuousConversation = c }

        bindLangSpinner(id(R.id.sttLang), sttLangs, prefs.sttLanguage) { prefs.sttLanguage = it }
        bindLangSpinner(id(R.id.latinLang), latinLangs, prefs.ttsLatinLanguage) { prefs.ttsLatinLanguage = it }

        fillVoices()
        id<Button>(R.id.refreshVoices).setOnClickListener { fillVoices() }
        id<Button>(R.id.testVoice).setOnClickListener {
            val sel = voiceNames.getOrNull(id<Spinner>(R.id.voiceSpinner).selectedItemPosition)
            if (!Assistant.voice.isTtsReady) Toast.makeText(ctx, "Text-to-speech abhi ready nahi hai.", Toast.LENGTH_SHORT).show()
            else Assistant.voice.sample(sel ?: prefs.ttsVoice.ifBlank { null })
        }
        id<Button>(R.id.openTtsSettings).setOnClickListener {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
            }
        }
        bindSeek(R.id.rateSeek, R.id.rateLabel, "Speed", { 0.5f + it * 0.05f }, { ((prefs.speechRate - 0.5f) / 0.05f).toInt() }) { prefs.speechRate = it }
        bindSeek(R.id.pitchSeek, R.id.pitchLabel, "Pitch", { 0.6f + it * 0.05f }, { ((prefs.speechPitch - 0.6f) / 0.05f).toInt() }) { prefs.speechPitch = it }
        bindSeek(R.id.volSeek, R.id.volLabel, "Volume", { it / 100f }, { (prefs.speechVolume * 100).toInt() }) { prefs.speechVolume = it }

        // ---------------- Wake word ----------------
        val wake = id<MaterialSwitch>(R.id.wakeSwitch)
        wake.isChecked = prefs.wakeWord && WakeService.running
        wake.setOnCheckedChangeListener { _, on ->
            prefs.wakeWord = on
            if (on) {
                if (Perm.hasMic(ctx)) startWake() else askPerm(Manifest.permission.RECORD_AUDIO)
            } else WakeService.stop(ctx)
        }

        // ---------------- Memory ----------------
        val mem = id<MaterialSwitch>(R.id.memorySwitch)
        mem.isChecked = prefs.memoryEnabled
        mem.setOnCheckedChangeListener { _, c -> prefs.memoryEnabled = c }

        // ---------------- Data ----------------
        id<Button>(R.id.clearHistory).setOnClickListener {
            AlertDialog.Builder(ctx).setTitle("Clear history?").setMessage("Saari saved conversation delete ho jayegi.")
                .setPositiveButton("Delete") { _, _ -> Assistant.clearHistory(); Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }
        id<Button>(R.id.clearInbox).setOnClickListener { Inbox.clear(); Toast.makeText(ctx, "Inbox cleared", Toast.LENGTH_SHORT).show() }

        if (!prefs.secureStorageAvailable) {
            aiStatus.text = "Warning: secure key storage unavailable on this device; API keys are kept only until the app closes."
        }
        refreshPerms()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) refreshPerms()
    }

    private fun startWake() {
        try { WakeService.start(requireContext()) } catch (e: Exception) {
            Toast.makeText(requireContext(), "Wake word start nahi hua: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun askPerm(perm: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Permission")
            .setMessage(Perm.explain(perm))
            .setPositiveButton("Allow") { _, _ -> pendingPerm = perm; permLauncher.launch(perm) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveAi() {
        val prefs = Assistant.prefs
        prefs.geminiKey = id<EditText>(R.id.geminiKey).text.toString()
        prefs.grokKey = id<EditText>(R.id.grokKey).text.toString()
        val model = id<EditText>(R.id.modelName).text.toString().trim().removePrefix("models/")
        prefs.setModel(prefs.provider, model)   // empty = automatic choice
        prefs.userName = id<EditText>(R.id.userName).text.toString()
    }

    private fun setModelList(ids: List<String>) {
        modelIds = ids
        val labels = listOf(if (ids.isEmpty()) "Press 'Fetch available models'" else "Choose a model…") + ids
        id<Spinner>(R.id.modelSpinner).adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
    }

    // ---------------- Permission dashboard ----------------

    private class PermRow(val name: String, val why: String, val granted: () -> Boolean, val enable: () -> Unit)

    private fun refreshPerms() {
        val ctx = context ?: return
        val box = id<LinearLayout>(R.id.permBox)
        box.removeAllViews()
        val dp = resources.displayMetrics.density
        val rows = ArrayList<PermRow>()
        rows += PermRow("Microphone", "Voice commands. Used only when you tap the mic or enable the wake word.", { Perm.hasMic(ctx) }) { askPerm(Manifest.permission.RECORD_AUDIO) }
        rows += PermRow("Contacts", "Find a number by name for calls / WhatsApp / SMS.", { Perm.has(ctx, Manifest.permission.READ_CONTACTS) }) { askPerm(Manifest.permission.READ_CONTACTS) }
        rows += PermRow("Phone calls", "Place a call right after you say yes (otherwise only the dialer opens).", { Perm.has(ctx, Manifest.permission.CALL_PHONE) }) { askPerm(Manifest.permission.CALL_PHONE) }
        rows += PermRow("Notifications", "Show reminder alerts.", { Build.VERSION.SDK_INT < 33 || Perm.has(ctx, Manifest.permission.POST_NOTIFICATIONS) }) { askPerm(Manifest.permission.POST_NOTIFICATIONS) }
        rows += PermRow("Notification access", "Read and reply to WhatsApp/Telegram/SMS notifications. Find NEXUS AI in the list and turn it ON.", { Perm.listenerEnabled(ctx) }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        rows += PermRow("Accessibility", "Read the screen, press Back/Home, tap buttons, scroll, auto-press Send after you confirm. Find NEXUS AI under Installed/Downloaded apps and turn it ON.", { Perm.accessibilityEnabled(ctx) }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        rows += PermRow("Exact alarms", "Reminders fire on time (otherwise they can be a few minutes late).", { Reminders.canUseExact(ctx) }) {
            if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }
        rows += PermRow("Battery: no restrictions", "Helps the wake word and reminders survive Infinix/XOS battery cleaning.", { Perm.batteryUnrestricted(ctx) }) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        rows.forEach { r ->
            val ok = r.granted()
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_glass)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() }
            }
            card.addView(TextView(ctx).apply {
                text = "${r.name}  —  " + if (ok) "GRANTED ✔" else "NOT GRANTED ✖"
                setTextColor(resources.getColor(if (ok) R.color.nx_cyan else R.color.nx_text, null))
                setTypeface(typeface, Typeface.BOLD); textSize = 14f
            })
            card.addView(TextView(ctx).apply { text = r.why; setTextColor(resources.getColor(R.color.nx_text_dim, null)); textSize = 12f })
            if (!ok) card.addView(Button(ctx).apply { text = "Enable"; setOnClickListener { r.enable() } })
            box.addView(card)
        }
    }

    // ---------------- helpers ----------------

    private fun bindLangSpinner(sp: Spinner, items: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        sp.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items.map { it.first })
        sp.setSelection(items.indexOfFirst { it.second == current }.coerceAtLeast(0), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, i: Long) { onPick(items[pos].second) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun fillVoices() {
        val prefs = Assistant.prefs
        val sp = id<Spinner>(R.id.voiceSpinner)
        val voices = Assistant.voice.availableVoices()
        voiceNames = listOf<String?>(null) + voices.map { it.name }
        val labels = listOf("Automatic (best match)") + voices.map {
            "${it.locale.getDisplayName(Locale.ENGLISH)} • ${it.name}" + if (it.isNetworkConnectionRequired) " (online)" else ""
        }
        sp.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(voiceNames.indexOf(prefs.ttsVoice.ifBlank { null }).coerceAtLeast(0), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, i: Long) { prefs.ttsVoice = voiceNames.getOrNull(pos).orEmpty() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        if (voices.isEmpty()) Toast.makeText(requireContext(), "Voices abhi load nahi hui. Thodi der baad 'Refresh voice list' dabao.", Toast.LENGTH_LONG).show()
    }

    private fun bindSeek(seekId: Int, labelId: Int, name: String, toValue: (Int) -> Float, toProgress: () -> Int, save: (Float) -> Unit) {
        val seek = id<SeekBar>(seekId)
        val label = id<TextView>(labelId)
        seek.progress = toProgress().coerceIn(0, seek.max)
        label.text = String.format(Locale.US, "%s: %.2f", name, toValue(seek.progress))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = String.format(Locale.US, "%s: %.2f", name, toValue(p))
                if (fromUser) save(toValue(p))
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
}
