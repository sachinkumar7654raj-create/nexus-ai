package com.nexus.ai.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Item(val id: Long, val text: String, val time: Long)

/** Tiny on-device list (notes, memory). Stored in the app's private folder. */
class ItemStore(ctx: Context, fileName: String) {
    private val file = File(ctx.filesDir, fileName)

    fun all(): List<Item> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getLong("id"), o.getString("t"), o.optLong("ts")) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<Item>) {
        try {
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.text).put("ts", it.time)) }
            file.writeText(a.toString())
        } catch (_: Exception) {}
    }

    fun add(text: String): Item {
        val list = all()
        val item = Item((list.maxOfOrNull { it.id } ?: 0L) + 1L, text.trim(), System.currentTimeMillis())
        save(list + item)
        return item
    }

    fun update(id: Long, text: String) = save(all().map { if (it.id == id) it.copy(text = text.trim()) else it })
    fun remove(id: Long) = save(all().filter { it.id != id })
    fun clear() { try { file.delete() } catch (_: Exception) {} }
}
