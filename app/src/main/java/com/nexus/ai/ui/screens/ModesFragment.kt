package com.nexus.ai.ui.screens

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.nexus.ai.R
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.Mode
import com.nexus.ai.core.Modes

class ModesFragment : Fragment(R.layout.fragment_modes) {

    private lateinit var list: LinearLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.modeList)
        val name = view.findViewById<EditText>(R.id.customName)
        val prompt = view.findViewById<EditText>(R.id.customPrompt)
        val prefs = Assistant.prefs
        name.setText(prefs.customModeName)
        prompt.setText(prefs.customModePrompt)

        view.findViewById<Button>(R.id.saveCustom).setOnClickListener {
            if (prompt.text.isBlank()) {
                Toast.makeText(requireContext(), "Personality / instructions likho.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.customModeName = name.text.toString()
            prefs.customModePrompt = prompt.text.toString()
            prefs.modeId = Modes.CUSTOM_ID
            render()
            Toast.makeText(requireContext(), "Custom mode active.", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val prefs = Assistant.prefs
        val dp = resources.displayMetrics.density
        for (m: Mode in Modes.all(prefs)) {
            val active = m.id == prefs.modeId
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_glass)
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                alpha = if (active) 1f else 0.82f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (8 * dp).toInt() }
                isClickable = true
                setOnClickListener {
                    prefs.modeId = m.id
                    render()
                    Toast.makeText(requireContext(), "${m.name} active", Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(TextView(requireContext()).apply {
                text = "${m.emoji}  ${m.name}" + if (active) "   ✔ ACTIVE" else ""
                setTextColor(resources.getColor(if (active) R.color.nx_cyan else R.color.nx_text, null))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(requireContext()).apply {
                text = m.blurb
                setTextColor(resources.getColor(R.color.nx_text_dim, null))
                textSize = 13f
            })
            list.addView(card)
        }
    }
}
