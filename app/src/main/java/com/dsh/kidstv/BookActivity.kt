package com.dsh.kidstv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 绘本阅读器：把 NAS 上的 PDF 下载到本地缓存，用系统自带的 PdfRenderer 一页页画出来。
 * ← → 翻页，返回键直接退出。
 */
class BookActivity : Activity() {

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var pageLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var bar: ProgressBar

    /** 图片页模式：NAS 上 <书名>__pages/ 里的一堆 jpg；为空则退回整本 PDF */
    private var pages: List<String> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var page = 0
    private var renderJob: Job? = null
    private var lastBitmap: Bitmap? = null
    private var downX = 0f


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
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

        // 下载进度条（PdfRenderer 要完整文件才能开，所以这里只能先显示缓冲进度）
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.coral)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
            visibility = View.GONE
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(dp(280), dp(6), Gravity.CENTER).apply { topMargin = dp(56) }
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

        // 手机上没有方向键：点右半屏翻下一页、左半屏上一页，也能滑动
        root.addView(
            label("点右侧翻页 · 左侧上一页", 12, 0x66FFFFFF.toInt()).apply {
                setPadding(dp(28), 0, 0, dp(22))
            },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        )
        root.isClickable = true
        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    when {
                        dx < -dp(28) -> nextPage()
                        dx > dp(28) -> prevPage()
                        e.x > v.width * 0.5f -> nextPage()
                        else -> prevPage()
                    }
                    true
                }
                else -> true
            }
        }
        return root
    }

    private fun load(url: String) {
        scope.launch {
            try {
                // 先看 NAS 上有没有预渲染好的图片页（有就只下当前页，秒开）
                val imgs = withContext(Dispatchers.IO) { listPageImages(url) }
                if (imgs.isNotEmpty()) {
                    pages = imgs
                    pageCount = imgs.size
                    status.visibility = View.GONE
                    bar.visibility = View.GONE
                    pageLabel.visibility = View.VISIBLE
                    showPage(0)
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    BookCache.download(this@BookActivity, url) { done, total ->
                        runOnUiThread {
                            bar.visibility = View.VISIBLE
                            if (total > 0) {
                                bar.progress = (done * 100 / total).toInt()
                                status.text = "正在下载绘本 ${mb(done)} / ${mb(total)}"
                            } else {
                                status.text = "正在下载绘本 ${mb(done)}"
                            }
                        }
                    }
                }
                status.text = "正在排版…"
                withContext(Dispatchers.IO) { open(file) }
                status.visibility = View.GONE
                bar.visibility = View.GONE
                showPage(0)
            } catch (e: Exception) {
                status.visibility = View.VISIBLE
                status.text = "打不开这本绘本\n${e.message ?: "未知错误"}"
            }
        }
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
        if (index < 0 || index >= pageCount) return
        page = index
        if (pages.isNotEmpty()) {
            showImagePage(index)
            return
        }
        val r = renderer ?: return
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

    /** 图片页模式：只下当前页 + 预取后两页 */
    private fun showImagePage(index: Int) {
        val url = pages.getOrNull(index) ?: return
        val cfg = Prefs.load(this)
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        paintPageLabel()
        image.setImageBitmap(null)
        val cached = Thumbs.cached(url)
        if (cached != null) {
            image.setImageBitmap(cached)
        } else {
            status.visibility = View.VISIBLE
            status.text = "正在加载第 ${index + 1} 页…"
            Thumbs.load(url, cfg.user, cfg.pass, w, h) { bmp ->
                if (page != index) return@load
                status.visibility = View.GONE
                if (bmp != null) image.setImageBitmap(bmp) else status.text = "这一页加载失败"
            }
        }
        // 预取后面两页，翻过去就是秒开
        for (k in 1..2) {
            pages.getOrNull(index + k)?.let { Thumbs.load(it, cfg.user, cfg.pass, w, h) { _ -> } }
        }
    }

    private fun paintPageLabel() {
        val buffered = pages.count { Thumbs.cached(it) != null }
        pageLabel.text = "${page + 1} / $pageCount · 已缓冲 $buffered"
    }

    /** 找 PDF 旁边的 <书名>__pages/ 目录，列出里面的图片页 */
    private fun listPageImages(pdfUrl: String): List<String> {
        val cfg = Prefs.load(this)
        val base = cfg.base.trimEnd('/')
        if (!pdfUrl.startsWith(base)) return emptyList()
        val path = pdfUrl.removePrefix(base)
        if (!path.lowercase().endsWith(".pdf")) return emptyList()
        val dirPath = path.dropLast(4) + "__pages"
        val dav = DavClient(cfg)
        val entries = runCatching { dav.propfind(dirPath, 1) }.getOrNull() ?: return emptyList()
        return entries.asSequence()
            .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .sortedWith { a, b -> Library.natCompare(a.name, b.name) }
            .map { dav.urlFor(it.href) }
            .toList()
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

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)

    private fun nextPage() {
        if (pageCount > 0 && page < pageCount - 1) showPage(page + 1)
    }

    private fun prevPage() {
        if (pageCount > 0 && page > 0) showPage(page - 1)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    nextPage()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    prevPage()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
