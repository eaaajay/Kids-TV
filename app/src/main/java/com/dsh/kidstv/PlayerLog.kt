package com.dsh.kidstv

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放器状态日志：黑屏这种「现场消失」的问题，事后只能靠它还原。
 *
 * 只留最近 200 行，写在 filesDir/player-log.txt，家长页可以查看。
 * 每次播放开始时打一行分隔，方便定位是哪一次。
 */
object PlayerLog {

    private const val FILE = "player-log.txt"
    private const val MAX_LINES = 200

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val buffer = ArrayDeque<String>()
    private var lastFlush = 0L

    @Synchronized
    fun add(ctx: Context, msg: String) {
        buffer.addLast("${fmt.format(Date())}  $msg")
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        // 攒 1 秒再落盘，避免状态抖动时频繁写
        val now = System.currentTimeMillis()
        if (now - lastFlush < 1000L) return
        lastFlush = now
        write(ctx)
    }

    private fun write(ctx: Context) {
        runCatching {
            File(ctx.filesDir, FILE).writeText(buffer.joinToString("\n"))
        }
    }

    fun lines(ctx: Context): List<String> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines() }.getOrDefault(emptyList())
    }

    /** 家长页用：最近几行 */
    fun tail(ctx: Context, n: Int = 8): String =
        lines(ctx).takeLast(n).joinToString("\n").ifBlank { "" }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
        synchronized(this) { buffer.clear() }
    }
}
