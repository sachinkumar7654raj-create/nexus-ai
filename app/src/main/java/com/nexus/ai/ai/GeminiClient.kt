package com.nexus.ai.ai

import com.nexus.ai.core.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Google Gemini over REST (streaming SSE). The key travels in a header, never in the URL. */
class GeminiClient : ChatProvider {
    override val displayName = "Gemini"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    private fun body(system: String, history: List<Msg>): String {
        val merged = ArrayList<Pair<Boolean, String>>()
        history.forEach {
            if (merged.isNotEmpty() && merged.last().first == it.fromUser) {
                merged[merged.lastIndex] = it.fromUser to (merged.last().second + "\n" + it.text)
            } else merged.add(it.fromUser to it.text)
        }
        val contents = JSONArray()
        merged.forEach { (fromUser, text) ->
            contents.put(
                JSONObject().put("role", if (fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        // No generationConfig on purpose: newer Gemini models reject some sampling parameters.
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .toString()
    }

    override suspend fun stream(apiKey: String, model: String, system: String, history: List<Msg>, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw AiException("Gemini API key set nahi hai. Settings mein key daalo.")
            val req = Request.Builder()
                .url("$base/models/$model:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", apiKey)
                .post(body(system, history).toRequestBody(json))
                .build()
            val call = http.newCall(req)
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            val full = StringBuilder()
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw AiException(explain(resp.code, resp.body?.string().orEmpty()), resp.code)
                    val source = resp.body?.source() ?: throw AiException("Gemini ka jawab khali aaya.")
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        val piece = extractText(payload)
                        if (piece.isNotEmpty()) {
                            full.append(piece)
                            onDelta(piece)
                        }
                    }
                }
            } catch (e: AiException) {
                throw e
            } catch (e: IOException) {
                if (!call.isCanceled()) throw AiException("Network error: internet check karo. (${e.message})") else throw e
            }
            if (full.isEmpty()) throw AiException("Gemini ne koi jawab nahi diya (safety filter ya model issue ho sakta hai).")
            full.toString()
        }

    override suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("Gemini API key set nahi hai.")
        val req = Request.Builder().url("$base/models?pageSize=200").header("x-goog-api-key", apiKey).get().build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw AiException(explain(resp.code, text), resp.code)
                val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
                val skip = Regex("tts|image|embed|live|audio|aqa|robotics|computer-use|banana|imagen|veo|gemma|learnlm|vision")
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var ok = false
                    if (methods != null) for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
                    if (ok && name.startsWith("gemini") && !skip.containsMatchIn(name)) out.add(name)
                }
                ModelPicker.sortForDisplay(out)
            }
        } catch (e: IOException) {
            throw AiException("Network error: internet check karo. (${e.message})")
        }
    }

    private fun extractText(payload: String): String = try {
        val parts = JSONObject(payload).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        sb.toString()
    } catch (e: Exception) {
        ""
    }

    private fun explain(code: Int, body: String): String {
        val apiMsg = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
        val hint = when (code) {
            400 -> "Request galat hai ya API key invalid hai."
            401, 403 -> "API key galat hai ya is key ko permission nahi hai."
            404 -> "Ye model ab available nahi hai."
            429 -> "Quota / rate limit khatam. Thodi der baad try karo."
            500, 503 -> "Gemini server busy hai. Thodi der baad try karo."
            else -> "Unexpected error."
        }
        return "Gemini error $code: $hint ${apiMsg.take(180)}".trim()
    }
}
