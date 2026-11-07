package com.nexus.ai.ui.screens

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexus.ai.R
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.Inbox
import com.nexus.ai.core.Reminders
import com.nexus.ai.ui.Perm
import com.nexus.ai.ui.StudioActivity
import kotlinx.coroutines.launch

/** Quick phone actions, message inbox (WhatsApp etc.), reminders, notes and memory. */
class ToolsFragment : Fragment(R.layout.fragment_tools) {

    private lateinit var root: LinearLayout
    private lateinit var resultView: TextView
    private lateinit var inboxBox: LinearLayout
    private lateinit var remBox: LinearLayout
    private lateinit var notesBox: LinearLayout
    private lateinit var memBox: LinearLayout
    private var dp = 1f

    private fun c(id: Int) = resources.getColor(id, null)

    private fun heading(text: String) = TextView(requireContext()).apply {
        this.text = text; setTextColor(c(R.color.nx_cyan)); textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (22 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun note(text: String) = TextView(requireContext()).apply {
        this.text = text; setTextColor(c(R.color.nx_text_dim)); textSize = 12f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(requireContext()).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_glass)
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (6 * dp).toInt() }
    }

    private fun input(hint: String) = EditText(requireContext()).apply {
        this.hint = hint; setTextColor(c(R.color.nx_text)); setHintTextColor(c(R.color.nx_text_dim))
        setBackgroundResource(R.drawable.bg_glass); setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun itemRow(text: String, onDelete: (() -> Unit)?, extra: Pair<String, () -> Unit>? = null): View {
        val row = card()
        row.addView(TextView(requireContext()).apply { this.text = text; setTextColor(c(R.color.nx_text)); textSize = 14f })
        if (onDelete != null || extra != null) {
            val btns = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            extra?.let { btns.addView(button(it.first, it.second)) }
            onDelete?.let { btns.addView(button("Delete", it)) }
            row.addView(btns)
        }
        return row
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        dp = resources.displayMetrics.density
        root = view.findViewById(R.id.toolsRoot)
        build()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Inbox.items.collect { renderInbox() } }
                launch {
                    Assistant.messages.collect { list ->
                        list.lastOrNull { !it.fromUser }?.let { resultView.text = "NEXUS: ${it.text}" }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) { renderReminders(); renderNotes(); renderMemory() }
    }

    private fun build() {
        val ctx = requireContext()
        root.removeAllViews()
        root.addView(TextView(ctx).apply { text = "Tools"; setTextColor(c(R.color.nx_text)); textSize = 22f; setTypeface(typeface, Typeface.BOLD) })

        // ---------- Quick actions ----------
        root.addView(heading("Quick actions"))
        resultView = note("Tap an action. NEXUS answers here and out loud.").apply { textSize = 13f; setPadding(0, 0, 0, (6 * dp).toInt()) }
        root.addView(resultView)
        val actions = listOf(
            "🔦 Torch ON" to "flashlight on karo", "Torch OFF" to "flashlight band karo",
            "🔋 Battery" to "battery kitni hai", "💾 Storage" to "storage kitna free hai",
            "🔊 Volume +" to "volume badhao", "🔉 Volume −" to "volume kam karo",
            "▶ YouTube" to "youtube kholo", "💬 WhatsApp" to "whatsapp kholo",
            "📷 Camera" to "camera kholo", "⚙ Phone settings" to "phone ki settings kholo",
            "◀ Back" to "back jao", "⌂ Home" to "home jao",
            "📜 Read my screen" to "screen par kya likha hai", "🔔 Notifications" to "notification panel kholo"
        )
        actions.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (label, cmd) ->
                row.addView(button(label) { Assistant.send(cmd, viaVoice = false) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            root.addView(row)
        }
        root.addView(button("🌐 Open Website Studio") { startActivity(Intent(ctx, StudioActivity::class.java)) })

        // ---------- Inbox ----------
        root.addView(heading("Message inbox (WhatsApp, Telegram, SMS...)"))
        root.addView(note("Needs Notification Access. NEXUS only sees messages that arrive after you enable it, keeps them in memory only, and replies only when you press Send."))
        val inboxButtons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        inboxButtons.addView(button("Read unread aloud") { Assistant.send("whatsapp message check karo", viaVoice = false) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        inboxButtons.addView(button("Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(inboxButtons)
        inboxBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(inboxBox)

        // ---------- Reminders ----------
        root.addView(heading("Reminders"))
        val remInput = input("e.g. 10 minute baad chai pina / kal subah 7 baje gym")
        root.addView(remInput)
        root.addView(button("Add reminder") {
            val t = remInput.text.toString()
            if (t.isNotBlank()) {
                val msg = Reminders.addFromText(requireContext(), t)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                remInput.setText("")
                renderReminders()
            }
        })
        remBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(remBox)

        // ---------- Notes ----------
        root.addView(heading("Notes"))
        val noteInput = input("Write a note")
        root.addView(noteInput)
        root.addView(button("Add note") {
            val t = noteInput.text.toString()
            if (t.isNotBlank()) { Assistant.notes.add(t); noteInput.setText(""); renderNotes() }
        })
        notesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(notesBox)

        // ---------- Memory ----------
        root.addView(heading("What NEXUS remembers"))
        root.addView(note("Say “yaad rakho ki ...” to add. Passwords/OTPs are refused. You can delete anything here."))
        memBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(memBox)

        renderInbox(); renderReminders(); renderNotes(); renderMemory()
    }

    private fun renderInbox() {
        if (!::inboxBox.isInitialized) return
        inboxBox.removeAllViews()
        val ctx = requireContext()
        if (!Perm.listenerEnabled(ctx)) { inboxBox.addView(note("Notification Access is OFF. Tap “Notification access” and enable NEXUS AI.")); return }
        val items = Inbox.items.value.reversed()
        if (items.isEmpty()) { inboxBox.addView(note("No messages yet.")); return }
        items.take(15).forEach { m ->
            val label = "${m.app} • ${m.sender}${if (!m.read) "  ●" else ""}\n${m.text}"
            inboxBox.addView(itemRow(label, null, "Reply" to { replyDialog(m) }))
        }
    }

    private fun replyDialog(m: com.nexus.ai.core.Incoming) {
        val et = EditText(requireContext()).apply { hint = "Your reply" }
        AlertDialog.Builder(requireContext())
            .setTitle("Reply to ${m.sender}")
            .setView(et)
            .setPositiveButton("Send") { _, _ ->
                val t = et.text.toString()
                if (t.isNotBlank()) {
                    val ok = Inbox.reply(requireContext(), m, t)
                    Toast.makeText(requireContext(), if (ok) "Reply sent." else "This message has no reply option. Open ${m.app} to reply.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renderReminders() {
        if (!::remBox.isInitialized) return
        remBox.removeAllViews()
        val list = Reminders.all(requireContext())
        if (list.isEmpty()) remBox.addView(note("No reminders."))
        list.forEach { r -> remBox.addView(itemRow(Reminders.describe(r), { Reminders.remove(requireContext(), r.id); renderReminders() })) }
    }

    private fun renderNotes() {
        if (!::notesBox.isInitialized) return
        notesBox.removeAllViews()
        val list = Assistant.notes.all().reversed()
        if (list.isEmpty()) notesBox.addView(note("No notes."))
        list.forEach { n -> notesBox.addView(itemRow(n.text, { Assistant.notes.remove(n.id); renderNotes() })) }
    }

    private fun renderMemory() {
        if (!::memBox.isInitialized) return
        memBox.removeAllViews()
        val list = Assistant.memory.all()
        if (list.isEmpty()) memBox.addView(note(if (Assistant.prefs.memoryEnabled) "Nothing remembered yet." else "Memory is OFF (Settings)."))
        list.forEach { m -> memBox.addView(itemRow(m.text, { Assistant.memory.remove(m.id); renderMemory() })) }
    }
}
