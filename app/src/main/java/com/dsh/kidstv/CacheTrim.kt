package com.dsh.kidstv

import java.io.File

/**
 * 磁盘缓存上限：按最后访问时间（LRU）删最老的。
 *
 * 电视存储普遍 4–8G，缓存不设上限的话，刷过几千集之后
 * cacheDir 能涨到几个 G，系统清理时还可能把 App 一起卡死。
 */
object CacheTrim {

    private val lastTrim = HashMap<String, Long>()

    /**
     * @param dir    缓存目录
     * @param maxMb  上限（MB）
     */
    fun trim(dir: File, maxMb: Int) {
        // 一天最多清一次，别每次加载都遍历文件
        val key = dir.absolutePath
        val now = System.currentTimeMillis()
        if (now - (lastTrim[key] ?: 0L) < 24 * 60 * 60 * 1000L) return
        lastTrim[key] = now

        Thread({
            runCatching {
                val files = dir.listFiles()?.filter { it.isFile } ?: return@runCatching
                val max = maxMb.toLong() * 1024L * 1024L
                var total = files.sumOf { it.length() }
                if (total <= max) return@runCatching

                // 最老的先删，删到低于上限的 80% 就停（留点余量，别天天清）
                val target = (max * 0.8).toLong()
                files.sortedBy { it.lastModified() }.forEach { f ->
                    if (total <= target) return@runCatching
                    val len = f.length()
                    if (f.delete()) total -= len
                }
                android.util.Log.i("CacheTrim", "${dir.name}: 清理到 ${total / 1024 / 1024}MB")
            }
        }, "cache-trim").apply { isDaemon = true; start() }
    }
}
