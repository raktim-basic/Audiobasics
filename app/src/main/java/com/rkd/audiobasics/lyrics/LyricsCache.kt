package com.rkd.audiobasics.lyrics

import org.json.JSONArray
import org.json.JSONObject

object LyricsCache {

    fun serialize(result: LyricsResult): String {
        val obj = JSONObject()
        obj.put("hasSynced", result.hasSynced)
        obj.put("plainText", result.plainText)
        val arr = JSONArray()
        result.syncedLines.forEach { line ->
            arr.put(JSONObject().apply {
                put("timeMs", line.timeMs)
                put("text", line.text)
            })
        }
        obj.put("syncedLines", arr)
        return obj.toString()
    }

    fun deserialize(json: String): LyricsResult? {
        return try {
            val obj = JSONObject(json)
            val hasSynced = obj.optBoolean("hasSynced", false)
            val plainText = obj.optString("plainText", "")
            val arr = obj.optJSONArray("syncedLines") ?: JSONArray()
            val lines = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                LyricLine(timeMs = l.getLong("timeMs"), text = l.getString("text"))
            }
            LyricsResult(syncedLines = lines, plainText = plainText, hasSynced = hasSynced)
        } catch (_: Exception) { null }
    }
}
