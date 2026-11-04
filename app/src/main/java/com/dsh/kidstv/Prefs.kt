package com.dsh.kidstv

import android.content.Context

data class NasConfig(
    val host: String = "",
    val port: Int = 5005,
    val scheme: String = "http",
    val rootPath: String = "/",
    val user: String = "",
    val pass: String = "",
    val title: String = "英语时间",
    /** 绘本目录；留空就跟视频用同一个根目录 */
    val booksPath: String = ""
) {
    val ready: Boolean get() = host.isNotBlank()
    val booksRoot: String get() = booksPath.ifBlank { rootPath }
    val base: String get() = "$scheme://$host:$port"
    val cacheKey: String get() = "$scheme://$host:$port$rootPath"
}

object Prefs {
    private const val FILE = "kidstv"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): NasConfig {
        val s = sp(c)
        return NasConfig(
            host = s.getString("host", "") ?: "",
            port = s.getInt("port", 5005),
            scheme = s.getString("scheme", "http") ?: "http",
            rootPath = s.getString("root", "/") ?: "/",
            user = s.getString("user", "") ?: "",
            pass = s.getString("pass", "") ?: "",
            title = s.getString("title", "英语时间") ?: "英语时间",
            booksPath = s.getString("books", "") ?: ""
        )
    }

    fun save(c: Context, cfg: NasConfig) {
        sp(c).edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("scheme", cfg.scheme)
            .putString("root", cfg.rootPath)
            .putString("user", cfg.user)
            .putString("pass", cfg.pass)
            .putString("title", cfg.title)
            .putString("books", cfg.booksPath)
            .apply()
    }

    /** 上次选的倍速（0.75× 起，索引 1 = 1.0×） */
    fun speedIndex(c: Context): Int = sp(c).getInt("speed", 1)

    fun setSpeedIndex(c: Context, i: Int) {
        sp(c).edit().putInt("speed", i).apply()
    }

    fun clear(c: Context) {
        sp(c).edit().clear().apply()
    }

    fun pin(c: Context): String = sp(c).getString("pin", "1234") ?: "1234"
    fun setPin(c: Context, pin: String) {
        sp(c).edit().putString("pin", pin).apply()
    }

    fun limitMinutes(c: Context): Int = sp(c).getInt("limit", 0)

    fun setLimitMinutes(c: Context, m: Int) {
        sp(c).edit().putInt("limit", m).apply()
    }
}
