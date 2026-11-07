package com.nexus.ai.core

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Saves chat history in the app's private storage (not accessible to other apps). */
class HistoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")

    fun load(): List<Msg> = try {
        if (file.exists()) Msg.listFromJson(JSONArray(file.readText())) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<Msg>) {
        try {
            file.writeText(Msg.listToJson(list.takeLast(200)).toString())
        } catch (_: Exception) {}
    }

    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }
}
