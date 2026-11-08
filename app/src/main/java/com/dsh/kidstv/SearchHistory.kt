package com.dsh.kidstv

import android.content.Context
import org.json.JSONArray

/**
 * 搜索历史：最近用过的关键词，存在本地，重启还在。
 * 最多留 8 条，重复的提到最前面。
 */
object SearchHistory {

    private const val PREF = "search"
    private const val KEY = "recent"
    private const val MAX = 8

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, q: String) {
        val key = q.trim()
        if (key.isEmpty()) return
        val list = load(ctx).toMutableList()
        list.removeAll { it.equals(key, true) }
        list.add(0, key)
        while (list.size > MAX) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun remove(ctx: Context, q: String) {
        val list = load(ctx).toMutableList()
        list.removeAll { it.equals(q, true) }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
