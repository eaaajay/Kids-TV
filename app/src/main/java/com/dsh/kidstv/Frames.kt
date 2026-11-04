package com.dsh.kidstv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 视频没有同名封面图时，从视频本身抽一帧当封面。
 *
 * 关键点：不下载整集。用 [MediaDataSource] + HTTP Range，只让 MediaMetadataRetriever
 * 读它需要的那几段（moov 和关键帧），所以在慢速中继上也能抽出图来。
 * 抽到的帧落盘缓存，同一个视频只抽一次。
 */
object Frames {

    private val main = Handler(Looper.getMainLooper())
    /** 抽帧比下缩略图重，只开 2 条，别把中继挤死 */
    private val pool = Executors.newFixedThreadPool(2)
    private var dir: File? = null

    private val mem = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val inflight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val lock = Any()

    /** 服务器不支持 Range 就别再试了，省得每张卡都等一次超时 */
    @Volatile
    private var disabled = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.cacheDir, "frames").apply { mkdirs() }
    }

    fun cached(url: String): Bitmap? = mem.get(url)

    fun load(url: String, user: String, pass: String, reqW: Int, reqH: Int, onDone: (Bitmap?) -> Unit) {
        if (disabled || !url.startsWith("http")) return
        mem.get(url)?.let { onDone(it); return }

        val waiting = enqueue(url, onDone) ?: return

        pool.execute {
            var bmp: Bitmap? = null
            try {
                val file = File(dir, "frame-" + md5(url) + ".img")
                if (file.exists() && file.length() > 0) {
                    bmp = decode(file, reqW, reqH)
                }
                if (bmp == null) {
                    bmp = extract(url, user, pass, reqW, reqH)
                    if (bmp != null) runCatching {
                        file.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (bmp != null) mem.put(url, bmp)
                val done = synchronized(lock) {
                    inflight.remove(url)
                    waiting.toList()
                }
                val result = bmp
                main.post { done.forEach { cb -> runCatching { cb(result) } } }
            }
        }
    }

    private fun enqueue(url: String, onDone: (Bitmap?) -> Unit): MutableList<(Bitmap?) -> Unit>? =
        synchronized(lock) {
            val running = inflight[url]
            if (running != null) {
                running.add(onDone)
                null
            } else {
                ArrayList<(Bitmap?) -> Unit>().also {
                    it.add(onDone)
                    inflight[url] = it
                }
            }
        }

    // ---------------- 抽帧 ----------------

    private fun extract(url: String, user: String, pass: String, reqW: Int, reqH: Int): Bitmap? {
        val auth = if (user.isNotBlank()) Credentials.basic(user, pass, Charsets.UTF_8) else null
        val size = remoteSize(url, auth) ?: return null
        if (size <= 0) return null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(RangeSource(url, auth, size))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // 取 15% 处：片头经常是黑场或台标
            val atUs = if (durationMs > 1000) durationMs * 150 else 2_000_000L
            val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
                ?: return null
            return scale(frame, reqW, reqH)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun remoteSize(url: String, auth: String?): Long? {
        val head = Request.Builder().url(url).head()
        if (auth != null) head.header("Authorization", auth)
        runCatching {
            client.newCall(head.build()).execute().use { r ->
                if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull()?.let { if (it > 0) return it }
            }
        }
        // 有的服务器不认 HEAD，用 Range: bytes=0-0 探一下总长度
        val probe = Request.Builder().url(url).header("Range", "bytes=0-0")
        if (auth != null) probe.header("Authorization", auth)
        return runCatching {
            client.newCall(probe.build()).execute().use { r ->
                val cr = r.header("Content-Range")
                cr?.substringAfter('/')?.toLongOrNull()
            }
        }.getOrNull()
    }

    private fun scale(src: Bitmap, w: Int, h: Int): Bitmap {
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height).coerceAtLeast(0.01f)
        val nw = (src.width * s).toInt().coerceIn(1, 1920)
        val nh = (src.height * s).toInt().coerceIn(1, 1920)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun decode(f: File, w: Int, h: Int): Bitmap? = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        bmp?.let { scale(it, w, h) }
    }.getOrNull()

    /** 让 MediaMetadataRetriever 以为自己在读本地文件，实际是按需发 Range 请求 */
    private class RangeSource(
        private val url: String,
        private val auth: String?,
        private val total: Long
    ) : MediaDataSource() {

        override fun getSize(): Long = total

        override fun close() = Unit

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= total) return -1
            val end = (position + length - 1).coerceAtMost(total - 1)
            val req = Request.Builder().url(url).header("Range", "bytes=$position-$end")
            if (auth != null) req.header("Authorization", auth)
            return try {
                client.newCall(req.build()).execute().use { r ->
                    if (r.code != 206) {
                        // 不支持 Range（返回 200 整包）就没法随机读，直接放弃
                        disabled = true
                        return -1
                    }
                    val body = r.body?.byteStream() ?: return -1
                    var read = 0
                    while (read < length) {
                        val n = body.read(buffer, offset + read, length - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read == 0) -1 else read
                }
            } catch (_: Exception) {
                -1
            }
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
