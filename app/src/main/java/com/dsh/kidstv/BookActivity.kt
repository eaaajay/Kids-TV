package com.dsh.kidstv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 绘本阅读器：把 NAS 上的 PDF 下载到本地缓存，用系统自带的 PdfRenderer 一页页画出来。
 * ← → 翻页，长按返回键退出（跟播放页一样，孩子按一下出不去）。
 */
class BookActivity : Activity() {

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var pageLabel: TextView
    private lateinit var titleLabel: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var page = 0
    private var backAt = 0L
    private var renderJob: Job? = null
    private var lastBitmap: Bitmap? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(buildUi())

        val url = intent.getStringExtra("url").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        titleLabel.text = title
        if (url.isBlank()) {
            finish()
            return
        }
        load(url)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.ink) }

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Palette.ink)
        }
        root.addView(image, MATCH_PARENT, MATCH_PARENT)

        status = label("正在打开绘本…", 22, 0xCCFFFFFF.toInt(), display = true).apply {
            gravity = Gravity.CENTER
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER)
        )

        titleLabel = label("", 15, 0x88FFFFFF.toInt()).apply {
            setPadding(dp(28), dp(20), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(
            titleLabel,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
        )

        pageLabel = label("", 16, 0x99FFFFFF.toInt(), display = true).apply {
            setPadding(0, 0, dp(32), dp(22))
        }
        root.addView(
            pageLabel,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        )
        return root
    }

    private fun load(url: String) {
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetch(url) { pct ->
                    runOnUiThread { status.text = "正在下载绘本 $pct%" }
                } }
                status.text = "正在排版…"
                withContext(Dispatchers.IO) { open(file) }
                status.visibility = View.GONE
                showPage(0)
            } catch (e: Exception) {
                status.visibility = View.VISIBLE
                status.text = "打不开这本绘本\n${e.message ?: "未知错误"}"
            }
        }
    }

    private fun fetch(url: String, onProgress: (Int) -> Unit): File {
        val dir = File(cacheDir, "books")
        if (!dir.exists()) dir.mkdirs()
        val name = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".pdf"
        val file = File(dir, name)
        if (file.exists() && file.length() > 1024) return file

        val cfg = Prefs.load(this)
        val builder = Request.Builder().url(url)
        if (cfg.user.isNotBlank()) {
            builder.header("Authorization", okhttp3.Credentials.basic(cfg.user, cfg.pass, Charsets.UTF_8))
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            val tmp = File(dir, name + ".part")
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
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
        return file
    }

    private fun open(file: File) {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(fd)
        pfd = fd
        renderer = r
        pageCount = r.pageCount
        runOnUiThread {
            pageLabel.text = "1 / $pageCount"
            pageLabel.visibility = View.VISIBLE
        }
    }

    private fun showPage(index: Int) {
        val r = renderer ?: return
        if (index < 0 || index >= pageCount) return
        page = index
        pageLabel.text = "${index + 1} / $pageCount"
        renderJob?.cancel()
        renderJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) { render(r, index) }
            if (bmp != null) {
                image.setImageBitmap(bmp)
                lastBitmap?.recycle()
                lastBitmap = bmp
                status.visibility = View.GONE
            }
        }
    }

    private fun render(r: PdfRenderer, index: Int): Bitmap? = runCatching {
        val p = r.openPage(index)
        try {
            val maxW = resources.displayMetrics.widthPixels
            val maxH = resources.displayMetrics.heightPixels
            val scale = minOf(maxW.toFloat() / p.width, maxH.toFloat() / p.height)
            val w = (p.width * scale).toInt().coerceIn(1, maxW)
            val h = (p.height * scale).toInt().coerceIn(1, maxH)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            p.close()
        }
    }.getOrNull()

    override fun onDestroy() {
        scope.cancel()
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        lastBitmap?.recycle()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (page < pageCount - 1) showPage(page + 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (page > 0) showPage(page - 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backAt = SystemClock.uptimeMillis()
                    KeyEvent.ACTION_UP -> {
                        val held = SystemClock.uptimeMillis() - backAt
                        backAt = 0
                        if (held >= 2000) finish()
                        else Toast.makeText(this, "长按返回键 2 秒退出", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
