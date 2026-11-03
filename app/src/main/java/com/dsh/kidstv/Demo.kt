package com.dsh.kidstv

import android.content.Context
import java.io.File

/**
 * 演示片：还没配 NAS（或 NAS 不在身边）时，先让这面墙有东西可看。
 * 三段 8 秒的占位视频，第一次打开时从 assets 复制到 cache。
 */
object Demo {

    private val ITEMS = listOf(
        Triple("peppa.mp4", "Muddy Puddles", "Peppa Pig"),
        Triple("bluey.mp4", "Magic Xylophone", "Bluey"),
        Triple("abc.mp4", "ABC Song", "Nursery Rhymes")
    )

    fun section(ctx: Context): Section {
        val dir = File(ctx.cacheDir, "demo")
        if (!dir.exists()) dir.mkdirs()
        val videos = ITEMS.map { (asset, title, _) ->
            val f = File(dir, asset)
            if (!f.exists() || f.length() == 0L) {
                runCatching {
                    ctx.assets.open("demo/$asset").use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }
            Video(title, "file://" + f.absolutePath, null)
        }
        return Section("演示片 · 先看看样子", videos)
    }
}
