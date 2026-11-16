package com.dsh.kidstv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val url: String,
    val title: String,
    /** 所属系列，用于显示"小猪佩奇第七部" */
    val series: String,
    val at: Long
)

/**
 * 观看历史：只记「看过哪几集」和加入时间，不记播放位置。
 * 存成 filesDir/history.json，最多保留 30 条，同一个 url 只留最新一条。
 */
object History {

    private const val MAX = 30
    private const val FILE = "history.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): List<HistoryItem> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("u")
                if (url.isBlank()) null else HistoryItem(
                    url = url,
                    title = o.optString("t"),
                    series = o.optString("s"),
                    at = o.optLong("at")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun record(ctx: Context, url: String, title: String, series: String) {
        if (url.isBlank()) return
        val list = load(ctx).filterNot { it.url == url }
        val merged = (listOf(HistoryItem(url, title, series, System.currentTimeMillis())) + list).take(MAX)
        runCatching {
            val arr = JSONArray()
            merged.forEach { h ->
                arr.put(
                    JSONObject()
                        .put("u", h.url)
                        .put("t", h.title)
                        .put("s", h.series)
                        .put("at", h.at)
                )
            }
            file(ctx).writeText(arr.toString())
        }
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
