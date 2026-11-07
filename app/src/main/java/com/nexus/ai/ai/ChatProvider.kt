package com.nexus.ai.ai

import com.nexus.ai.core.Msg

class AiException(message: String, val code: Int = 0) : Exception(message) {
    /** True when the error means "this model name is not usable" (retired, wrong, or not allowed for this key). */
    val isModelProblem: Boolean get() = code == 404 || (code == 400 && (message ?: "").contains("model", ignoreCase = true))
}

interface ChatProvider {
    val displayName: String
    suspend fun stream(apiKey: String, model: String, system: String, history: List<Msg>, onDelta: (String) -> Unit): String
    /** Model ids this key can really use, fetched live from the provider. */
    suspend fun listModels(apiKey: String): List<String>
}

/** Chooses a working model from the live list. Model names change often, so nothing is hard-coded. */
object ModelPicker {
    private val versionRe = Regex("(\\d+(?:\\.\\d+)?)")
    private fun version(id: String): Double = versionRe.find(id.substringAfter('-', id))?.value?.toDoubleOrNull() ?: -1.0
    private fun isStable(id: String) = !(id.contains("preview") || id.contains("exp") || id.contains("beta"))

    fun sortForDisplay(ids: List<String>): List<String> =
        ids.distinct().sortedWith(compareByDescending<String> { version(it) }.thenBy { it })

    fun best(ids: List<String>, provider: String): String? {
        if (ids.isEmpty()) return null
        val pool = if (provider == "grok") {
            ids.filter { it.startsWith("grok") && !Regex("image|imagine|video|embed|vision").containsMatchIn(it) }
        } else {
            val flash = ids.filter { it.contains("flash") && !it.contains("lite") }
            flash.ifEmpty { ids.filter { it.contains("flash") }.ifEmpty { ids } }
        }
        val list = pool.ifEmpty { ids }
        return list.sortedWith(
            compareByDescending<String> { isStable(it) }
                .thenByDescending { version(it) }
                .thenBy { it.contains("mini") }
                .thenBy { it.length }
        ).firstOrNull()
    }
}
