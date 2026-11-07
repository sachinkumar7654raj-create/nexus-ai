package com.nexus.ai.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexus.ai.R
import com.nexus.ai.ai.AiException
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.Msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** AI website builder: describe a site, preview it, refine it with follow-up requests, then save/share it. */
class StudioActivity : AppCompatActivity() {

    private val history = ArrayList<Msg>()
    private var html = ""
    private var job: Job? = null
    private lateinit var web: WebView
    private lateinit var codeScroll: ScrollView
    private lateinit var codeText: TextView
    private lateinit var status: TextView
    private lateinit var prompt: EditText

    private val system = """You are a senior front-end web developer.
Create ONE complete, self-contained, responsive HTML5 file with inline CSS and inline JavaScript (no build tools).
Use a modern, professional, good-looking design (clean layout, nice colours, mobile-first, working navigation/buttons where relevant).
You may use Google Fonts or a CDN library if really needed, but the page must still look good without them.
Write the page in the language the user asks for (default: English). When the user asks for changes, return the FULL updated HTML file.
Reply with ONLY the code inside a single ```html code block, with no explanation before or after."""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studio)
        web = findViewById(R.id.web)
        codeScroll = findViewById(R.id.codeScroll)
        codeText = findViewById(R.id.codeText)
        status = findViewById(R.id.studioStatus)
        prompt = findViewById(R.id.prompt)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false

        findViewById<Button>(R.id.generate).setOnClickListener { generate() }
        findViewById<Button>(R.id.tabPreview).setOnClickListener { showCode(false) }
        findViewById<Button>(R.id.tabCode).setOnClickListener { showCode(true) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share() }

        intent.getStringExtra("prompt")?.let {
            prompt.setText(it)
            generate()
        }
    }

    private fun showCode(show: Boolean) {
        codeScroll.visibility = if (show) View.VISIBLE else View.GONE
        web.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun generate() {
        val p = prompt.text.toString().trim()
        if (p.isEmpty()) { Toast.makeText(this, "Pehle batao kaisi website chahiye.", Toast.LENGTH_SHORT).show(); return }
        job?.cancel()
        history.add(Msg(true, p))
        status.text = "Bana rahi hoon… (kuch der lag sakti hai)"
        job = lifecycleScope.launch {
            try {
                var written = 0
                val out = Assistant.completeOnce(system, history.takeLast(6)) { piece ->
                    written += piece.length
                    runOnUiThread { status.text = "Likh rahi hoon… $written characters" }
                }
                html = extractHtml(out)
                history.add(Msg(false, "```html\n$html\n```"))
                codeText.text = html
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                showCode(false)
                status.text = "Ho gaya. Badlav chahiye to neeche likh kar dobara Generate dabao."
                prompt.setText("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                history.removeLastOrNull()
                status.text = e.message ?: "AI error"
            } catch (e: Exception) {
                history.removeLastOrNull()
                status.text = "Error: ${e.message}"
            }
        }
    }

    private fun extractHtml(text: String): String {
        val fenced = Regex("```(?:html)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)
        var code = (fenced ?: text).trim()
        val start = code.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: code.indexOf("<html", ignoreCase = true)
        if (start > 0) code = code.substring(start)
        return code
    }

    private fun save() {
        if (html.isBlank()) { Toast.makeText(this, "Pehle website generate karo.", Toast.LENGTH_SHORT).show(); return }
        val stamp = System.currentTimeMillis() / 1000
        try {
            writeDownload("nexus-site-$stamp.html", "text/html") { it.write(html.toByteArray()) }
            writeDownload("nexus-site-$stamp.zip", "application/zip") { out ->
                ZipOutputStream(out).use { z ->
                    z.putNextEntry(ZipEntry("index.html"))
                    z.write(html.toByteArray())
                    z.closeEntry()
                }
            }
            Toast.makeText(this, "Saved: nexus-site-$stamp.html aur .zip (Downloads folder)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save nahi hua: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeDownload(name: String, mime: String, writer: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Downloads mein file nahi ban payi")
            contentResolver.openOutputStream(uri)?.use(writer) ?: throw IllegalStateException("File nahi khul payi")
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            File(dir, name).outputStream().use(writer)
        }
    }

    private fun share() {
        if (html.isBlank()) { Toast.makeText(this, "Pehle website generate karo.", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, html), "Share HTML code"))
    }

    override fun onDestroy() {
        job?.cancel()
        web.destroy()
        super.onDestroy()
    }
}
