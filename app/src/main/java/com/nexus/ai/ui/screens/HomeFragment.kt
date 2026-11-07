package com.nexus.ai.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexus.ai.R
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.AvatarState
import com.nexus.ai.ui.Perm
import com.nexus.ai.ui.avatar.AvatarView
import com.nexus.ai.ui.avatar.WaveView
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Assistant.listen() else showMicDenied()
    }

    private lateinit var avatar: AvatarView
    private lateinit var wave: WaveView
    private lateinit var liveText: TextView
    private lateinit var status: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        avatar = view.findViewById(R.id.avatar)
        wave = view.findViewById(R.id.wave)
        liveText = view.findViewById(R.id.liveText)
        status = view.findViewById(R.id.status)
        val mic = view.findViewById<ImageButton>(R.id.micButton)
        val chip = view.findViewById<TextView>(R.id.modeChip)

        mic.setOnClickListener {
            when (Assistant.state.value) {
                AvatarState.LISTENING, AvatarState.THINKING -> Assistant.stopAll()
                else -> startListeningWithPermission()
            }
        }
        // Long-press: stop speaking / cancel everything
        mic.setOnLongClickListener { Assistant.stopAll(); true }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Assistant.state.collect { s ->
                        avatar.state = s
                        wave.state = s
                        status.text = when (s) {
                            AvatarState.IDLE -> "Tap the mic and talk to me"
                            AvatarState.LISTENING -> "Listening… (tap to stop)"
                            AvatarState.THINKING -> "Thinking…"
                            AvatarState.SPEAKING -> "Speaking… (tap mic to interrupt)"
                        }
                        if (s == AvatarState.IDLE) liveText.text = lastReplyOrHint()
                    }
                }
                launch { Assistant.mood.collect { avatar.mood = it } }
                launch {
                    Assistant.level.collect { avatar.level = it; wave.level = it }
                }
                launch {
                    Assistant.liveText.collect { t -> if (t.isNotBlank()) liveText.text = t }
                }
                launch {
                    Assistant.messages.collect { if (Assistant.state.value == AvatarState.IDLE) liveText.text = lastReplyOrHint() }
                }
                launch {
                    Assistant.state.collect { chip.text = "${Assistant.currentMode.emoji}  ${Assistant.currentMode.name}" }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView>(R.id.modeChip)?.text = "${Assistant.currentMode.emoji}  ${Assistant.currentMode.name}"
    }

    private fun lastReplyOrHint(): String =
        Assistant.messages.value.lastOrNull { !it.fromUser }?.text
            ?: "Namaste! Main NEXUS hoon.\nSettings mein Gemini API key daalo, phir mic dabake baat karo."

    private fun startListeningWithPermission() {
        if (Perm.hasMic(requireContext())) {
            if (!Assistant.voice.isRecognitionAvailable()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Speech recognition unavailable")
                    .setMessage("Is phone par speech recognition service nahi mili. Play Store se 'Speech Services by Google' install/enable karo, ya Chat tab se type karke baat karo.")
                    .setPositiveButton("OK", null).show()
                return
            }
            Assistant.listen()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("Microphone permission")
                .setMessage("NEXUS ko aapki awaaz sunne ke liye microphone chahiye. Audio sirf tab sunta hai jab aap mic dabate ho, aur app band/background hone par mic apne aap band ho jata hai.")
                .setPositiveButton("Allow") { _, _ -> micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun showMicDenied() {
        AlertDialog.Builder(requireContext())
            .setTitle("Microphone denied")
            .setMessage("Mic ke bina voice input nahi chalega. Aap Chat tab mein type kar sakte ho, ya App settings se permission de sakte ho.")
            .setPositiveButton("App settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
            }
            .setNegativeButton("Close", null).show()
    }
}
