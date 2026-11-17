package com.dsh.kidstv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val url: String,
    val title: String,
    /** 所属系列，用于显示"小猪佩奇第七部" */
    val series: String,
    val at: Long,
    /** 看到第几毫秒；0 表示还没看或没记录 */
    val positionMs: Long = 0L,
    /** 总时长（毫秒），用来算百分比；0 表示未知 */
    val durationMs: Long = 0L
) {
    /** 0..1 的观看进度，用于"继续看"卡片上的小进度条 */
    val fraction: Float get() =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** 快看完了（>95%）就不再提示"继续看" */
    val nearlyFinished: Boolean get() = fraction > 0.95f
}

/**
 * 观看历史：记「看过哪几集 + 看到哪儿」。
 *
 * 播放时每 15 秒写一次，写在 filesDir/history.json，最多 30 条，同一 url 只留最新。
 * 写入是 debounce 的：进度条走一秒写一次的话，电视的闪存会被写穿。
 */
object History {

    private const val MAX = 30
    private const val FILE = "history.json"
    private const val WRITE_DEBOUNCE_MS = 1500L

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var cache: List<HistoryItem>? = null
    private var pendingWrite: Runnable? = null
    private var pendingCtx: Context? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): List<HistoryItem> {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val list = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("u")
                if (url.isBlank()) null else HistoryItem(
                    url = url,
                    title = o.optString("t"),
                    series = o.optString("s"),
                    at = o.optLong("at"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur")
                )
            }
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    fun positionFor(ctx: Context, url: String): HistoryItem? =
        load(ctx).firstOrNull { it.url == url }

    /** 开始看一集时记一下（保留上一次的进度，不要被 0 冲掉） */
    fun record(ctx: Context, url: String, title: String, series: String) {
        if (url.isBlank()) return
        val prev = positionFor(ctx, url)
        update(ctx, HistoryItem(
            url, title, series, System.currentTimeMillis(),
            positionMs = prev?.positionMs ?: 0L,
            durationMs = prev?.durationMs ?: 0L
        ))
    }

    /** 播放中定期记录进度 */
    fun recordProgress(ctx: Context, url: String, positionMs: Long, durationMs: Long) {
        if (url.isBlank()) return
        val prev = positionFor(ctx, url) ?: return
        // 倒退很多不覆盖（用户在从头重看）
        if (positionMs < prev.positionMs - 20_000L) return
        update(ctx, prev.copy(
            at = System.currentTimeMillis(),
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = if (durationMs > 0) durationMs else prev.durationMs
        ))
    }

    /** 一集播完：位置归零，下次从头放 */
    fun recordFinished(ctx: Context, url: String) {
        val prev = positionFor(ctx, url) ?: return
        update(ctx, prev.copy(at = System.currentTimeMillis(), positionMs = 0L))
    }

    private fun update(ctx: Context, item: HistoryItem) {
        synchronized(lock) {
            val merged = (listOf(item) + load(ctx).filterNot { it.url == item.url }).take(MAX)
            cache = merged
        }
        scheduleWrite(ctx)
    }

    private fun scheduleWrite(ctx: Context) {
        pendingCtx = ctx.applicationContext
        pendingWrite?.let { handler.removeCallbacks(it) }
        val job = Runnable {
            val target = pendingCtx ?: return@Runnable
            val data = cache ?: return@Runnable
            runCatching {
                val arr = JSONArray()
                data.forEach { h ->
                    arr.put(
                        JSONObject()
                            .put("u", h.url)
                            .put("t", h.title)
                            .put("s", h.series)
                            .put("at", h.at)
                            .put("pos", h.positionMs)
                            .put("dur", h.durationMs)
                    )
                }
                file(target).writeText(arr.toString())
            }
        }
        pendingWrite = job
        handler.postDelayed(job, WRITE_DEBOUNCE_MS)
    }

    /** 退出播放器前把还没落盘的进度立即写下去 */
    fun flush(ctx: Context) {
        pendingWrite?.let {
            handler.removeCallbacks(it)
            it.run()
        }
        pendingWrite = null
    }

    fun clear(ctx: Context) {
        pendingWrite?.let { handler.removeCallbacks(it) }
        pendingWrite = null
        cache = emptyList()
        runCatching { file(ctx).delete() }
    }
}
