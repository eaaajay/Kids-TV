package com.dsh.kidstv

import android.content.Context
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 绘本 PDF 的本地缓存。
 *
 * Android 的 PdfRenderer 要求完整文件（不支持流式读页），所以没法"边下边翻"。
 * 能做到的是：打开时显示真实进度（已下载 / 总大小），以及**焦点预取** ——
 * 遥控器停在哪本、手指点到哪本，就在后台先把文件拉下来，点开基本秒开。
 */
object BookCache {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 预取只开一条线程，别和播放/封面抢带宽 */
    private val pool = Executors.newSingleThreadExecutor()
    private val inflight = HashSet<String>()
    private val lock = Any()

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "books").apply { mkdirs() }

    fun fileFor(ctx: Context, url: String): File =
        File(dir(ctx), sha1(url) + ".pdf")

    fun isCached(ctx: Context, url: String): Boolean {
        val f = fileFor(ctx, url)
        return f.exists() && f.length() > 1024
    }

    /** 阻塞下载；已缓存直接返回。onProgress 回调在 IO 线程 */
    fun download(ctx: Context, url: String, onProgress: (Long, Long) -> Unit): File {
        val dir = dir(ctx)
        val file = fileFor(ctx, url)
        if (file.exists() && file.length() > 1024) return file

        val cfg = Prefs.load(ctx)
        val builder = Request.Builder().url(url)
        if (cfg.user.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(cfg.user, cfg.pass, Charsets.UTF_8))
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            val tmp = File(dir, file.name + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
        return file
    }

    /** 后台预取：焦点到了就先下，别等用户点开 */
    fun prefetch(ctx: Context, url: String) {
        if (isCached(ctx, url)) return
        synchronized(lock) {
            if (!inflight.add(url)) return
        }
        val app = ctx.applicationContext
        pool.execute {
            try {
                download(app, url) { _, _ -> }
            } catch (_: Exception) {
            } finally {
                synchronized(lock) { inflight.remove(url) }
            }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
