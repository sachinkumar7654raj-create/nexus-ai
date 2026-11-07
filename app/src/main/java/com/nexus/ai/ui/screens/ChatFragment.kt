package com.nexus.ai.ui.screens

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexus.ai.R
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.AvatarState
import com.nexus.ai.core.Msg
import kotlinx.coroutines.launch

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<Msg> = emptyList()
        class VH(v: View) : RecyclerView.ViewHolder(v) { val bubble: TextView = v.findViewById(R.id.bubble) }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            h.bubble.text = m.text
            h.bubble.setBackgroundResource(if (m.fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai)
            (h.bubble.layoutParams as FrameLayout.LayoutParams).gravity = if (m.fromUser) Gravity.END else Gravity.START
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.list)
        val input = view.findViewById<EditText>(R.id.input)
        val send = view.findViewById<ImageButton>(R.id.send)
        val typing = view.findViewById<TextView>(R.id.typing)
        val header = view.findViewById<TextView>(R.id.chatHeader)
        val adapter = Adapter()
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        list.adapter = adapter
        header.text = "${Assistant.currentMode.emoji} ${Assistant.currentMode.name}"

        fun submit() {
            val text = input.text.toString()
            if (text.isBlank()) return
            input.setText("")
            Assistant.send(text, viaVoice = false)
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Assistant.messages.collect {
                        adapter.items = it
                        adapter.notifyDataSetChanged()
                        if (it.isNotEmpty()) list.scrollToPosition(it.size - 1)
                    }
                }
                launch {
                    Assistant.liveText.collect { t ->
                        // Only show streaming reply text here (not the speech transcript while listening)
                        val streaming = Assistant.state.value == AvatarState.THINKING && t.isNotBlank()
                        typing.visibility = if (streaming) View.VISIBLE else View.GONE
                        if (streaming) typing.text = t
                    }
                }
                launch {
                    Assistant.state.collect { s ->
                        if (s == AvatarState.THINKING && Assistant.liveText.value.isBlank()) {
                            typing.visibility = View.VISIBLE; typing.text = "NEXUS soch rahi hai…"
                        } else if (s != AvatarState.THINKING) typing.visibility = View.GONE
                    }
                }
            }
        }
    }
}
