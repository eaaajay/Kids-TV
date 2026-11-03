package com.dsh.kidstv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 缩略图加载：内存 LRU + 磁盘缓存 + 3 线程下载。
 * 只认 HTTP(S) 的 WebDAV 图片地址，Basic Auth 认证。
 */
object Thumbs {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val inflight = ConcurrentHashMap<String, Boolean>()
    private var dir: File? = null

    private val maxMem = (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(16 * 1024 * 1024)

    private val mem = object : LruCache<String, Bitmap>(maxMem) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        }
    }

    fun cached(url: String): Bitmap? = mem.get(url)

    fun load(url: String, user: String, pass: String, reqW: Int, reqH: Int, onDone: (Bitmap?) -> Unit) {
        mem.get(url)?.let { onDone(it); return }
        if (inflight.putIfAbsent(url, true) != null) return

        pool.execute {
            var bmp: Bitmap? = null
            try {
                val file = File(dir, md5(url) + ".img")
                if (file.exists() && file.length() > 0) {
                    bmp = decodeFile(file, reqW, reqH)
                }
                if (bmp == null) {
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", Credentials.basic(user, pass, Charsets.UTF_8))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                bmp = decodeBytes(bytes, reqW, reqH)
                                if (bmp != null) runCatching { file.writeBytes(bytes) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                inflight.remove(url)
            }
            val result = bmp
            if (result != null) mem.put(url, result)
            main.post { onDone(result) }
        }
    }

    private fun decodeFile(f: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull()
    }

    private fun decodeBytes(bytes: ByteArray, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    private fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1
        var sample = 1
        var w = srcW
        var h = srcH
        while (w / 2 >= dstW && h / 2 >= dstH) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
