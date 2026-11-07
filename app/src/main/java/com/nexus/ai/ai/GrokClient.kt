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

/** xAI Grok (OpenAI-compatible chat completions API with SSE streaming). */
class GrokClient : ChatProvider {
    override val displayName = "Grok"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = "https://api.x.ai/v1"

    private fun body(model: String, system: String, history: List<Msg>): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
        return JSONObject().put("model", model).put("messages", msgs).put("stream", true).toString()
    }

    override suspend fun stream(apiKey: String, model: String, system: String, history: List<Msg>, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw AiException("Grok API key set nahi hai. Settings mein key daalo.")
            val req = Request.Builder()
                .url("$base/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body(model, system, history).toRequestBody(json))
                .build()
            val call = http.newCall(req)
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            val full = StringBuilder()
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw AiException(explain(resp.code, resp.body?.string().orEmpty()), resp.code)
                    val source = resp.body?.source() ?: throw AiException("Grok ka jawab khali aaya.")
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        if (payload == "[DONE]") break
                        val piece = try {
                            JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                        } catch (e: Exception) { "" }
                        if (piece.isNotEmpty()) { full.append(piece); onDelta(piece) }
                    }
                }
            } catch (e: AiException) {
                throw e
            } catch (e: IOException) {
                if (!call.isCanceled()) throw AiException("Network error: internet check karo. (${e.message})") else throw e
            }
            if (full.isEmpty()) throw AiException("Grok ne koi jawab nahi diya.")
            full.toString()
        }

    override suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("Grok API key set nahi hai.")
        val req = Request.Builder().url("$base/models").header("Authorization", "Bearer $apiKey").get().build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw AiException(explain(resp.code, text), resp.code)
                val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id")
                    if (id.startsWith("grok") && !Regex("image|imagine|video|embed").containsMatchIn(id)) out.add(id)
                }
                ModelPicker.sortForDisplay(out)
            }
        } catch (e: IOException) {
            throw AiException("Network error: internet check karo. (${e.message})")
        }
    }

    private fun explain(code: Int, body: String): String {
        val apiMsg = try {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: o.optString("error")
        } catch (e: Exception) { "" }
        val hint = when (code) {
            400 -> "Request ya model naam galat hai."
            401, 403 -> "API key galat hai ya permission nahi hai."
            404 -> "Ye model available nahi hai."
            429 -> "Quota / rate limit khatam."
            500, 503 -> "Grok server busy hai."
            else -> "Unexpected error."
        }
        return "Grok error $code: $hint ${apiMsg.orEmpty().take(180)}".trim()
    }
}
