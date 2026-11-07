package com.nexus.ai.core

import org.json.JSONArray
import org.json.JSONObject

data class Msg(val fromUser: Boolean, val text: String, val time: Long = System.currentTimeMillis()) {
    fun toJson() = JSONObject().put("u", fromUser).put("t", text).put("ts", time)

    companion object {
        fun fromJson(o: JSONObject) = Msg(o.optBoolean("u"), o.optString("t"), o.optLong("ts"))
        fun listToJson(l: List<Msg>) = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }
        fun listFromJson(a: JSONArray) = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}
