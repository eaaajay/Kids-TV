package com.dsh.kidstv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var cfg: NasConfig

    private lateinit var tabWatch: TextView
    private lateinit var tabAll: TextView
    private lateinit var pageWatch: View
    private lateinit var pageAll: View
    private lateinit var watchList: LinearLayout
    private lateinit var allHost: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewBox: FrameLayout
    private lateinit var subtitle: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sections: List<Section> = emptyList()
    private var shuffled: List<Video> = emptyList()
    private var tab = 0
    private var backAt = 0L

    private var previewPlayer: ExoPlayer? = null
    private var previewView: PlayerView? = null
    private var previewJob: Job? = null
    private var currentPreview: String? = null

    /** 窄屏（手机竖屏）紧凑排版；电视上仍是 10 尺大字 */
    private val compact: Boolean by lazy { resources.configuration.screenWidthDp < 600 }
    private val padH: Int get() = dp(if (compact) 18 else 52)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        cfg = Prefs.load(this)
        setContentView(buildUi())

        if (cfg.ready) {
            showCached()
            refresh()
        } else {
            renderDemo()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (tab == 0) previewPlayer?.play()
        val now = Prefs.load(this)
        if (now.ready && now.cacheKey != cfg.cacheKey) {
            cfg = now
            showCached()
            refresh()
        }
    }

    override fun onPause() {
        super.onPause()
        previewPlayer?.pause()
    }

    override fun onDestroy() {
        scope.cancel()
        previewView?.player = null
        previewPlayer?.release()
        previewPlayer = null
        super.onDestroy()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ---------------- 骨架 ----------------

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(DottedPaperView(this), MATCH_PARENT, MATCH_PARENT)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, dp(if (compact) 14 else 26), padH, dp(if (compact) 8 else 16))
        }
        root.addView(column, MATCH_PARENT, MATCH_PARENT)

        column.addView(header())

        val content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        pageWatch = buildWatchPage()
        pageAll = buildAllPage()
        content.addView(pageWatch, MATCH_PARENT, MATCH_PARENT)
        content.addView(pageAll, MATCH_PARENT, MATCH_PARENT)
        pageAll.visibility = View.GONE

        paintTabs()
        return root
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(if (compact) 8 else 14))
        }

        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("小芽英语", if (compact) 16 else 21, Palette.ink, display = true))
        subtitle = label("", if (compact) 10 else 12, Palette.ink3)
        subtitle.setPadding(0, dp(2), 0, 0)
        brand.addView(subtitle)
        row.addView(brand, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabWatch = tabPill("随心看", 0)
        tabAll = tabPill("全部目录", 1)
        tabs.addView(tabWatch)
        tabs.addView(tabAll)
        row.addView(tabs)

        val parent = label("家长", if (compact) 11 else 13, Palette.ink2).apply {
            setPadding(
                dp(if (compact) 12 else 18), dp(if (compact) 7 else 9),
                dp(if (compact) 12 else 18), dp(if (compact) 7 else 9)
            )
            background = shape(Palette.card, dp(30).toFloat(), dp(1), Palette.paper2)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener { askPin() }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.leftMargin = dp(if (compact) 8 else 18)
        row.addView(parent, lp)
        return row
    }

    private fun tabPill(text: String, index: Int): TextView {
        val tv = label(text, if (compact) 12 else 17, Palette.ink2, display = true).apply {
            setPadding(
                dp(if (compact) 11 else 20), dp(if (compact) 6 else 9),
                dp(if (compact) 11 else 20), dp(if (compact) 6 else 9)
            )
            background = shape(Color.TRANSPARENT, dp(24).toFloat())
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener { switchTab(index) }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN &&
                    (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
                ) {
                    switchTab(if (code == KeyEvent.KEYCODE_DPAD_LEFT) 0 else 1)
                    true
                } else false
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.rightMargin = dp(6)
        tv.layoutParams = lp
        return tv
    }

    private fun paintTabs() {
        listOf(tabWatch to 0, tabAll to 1).forEach { (tv, i) ->
            val on = tab == i
            tv.setTextColor(if (on) Palette.paper else Palette.ink2)
            tv.background = shape(if (on) Palette.ink else Color.TRANSPARENT, dp(24).toFloat())
        }
    }

    private fun switchTab(index: Int) {
        if (tab == index) return
        tab = index
        paintTabs()
        pageWatch.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageAll.visibility = if (index == 1) View.VISIBLE else View.GONE
        if (index == 0) {
            previewPlayer?.play()
            focusFirst(watchList)
        } else {
            previewPlayer?.pause()
            focusFirst(allHost)
        }
    }

    private fun focusFirst(host: ViewGroup) {
        host.post { firstFocusable(host)?.requestFocus() }
    }

    private fun firstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.isFocusable) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    // ---------------- Tab 1：随心看 ----------------

    private fun buildWatchPage(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }

        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        watchList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(if (compact) 4 else 12), 0)
        }
        scroller.addView(watchList, MATCH_PARENT, WRAP_CONTENT)
        row.addView(scroller, LinearLayout.LayoutParams(dp(if (compact) 232 else 330), MATCH_PARENT))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 4 else 14), 0, 0, 0)
        }

        previewBox = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val w = MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(w, (w * 9) / 16)
            }
        }.apply {
            background = shape(Palette.ink, dp(if (compact) 12 else 20).toFloat())
            clipToOutline = true
        }
        right.addView(previewBox, MATCH_PARENT, WRAP_CONTENT)

        previewTitle = label("", if (compact) 15 else 24, Palette.ink, display = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(if (compact) 8 else 16), 0, 0)
        }
        right.addView(previewTitle)

        right.addView(
            label("按 OK 全屏播放", if (compact) 11 else 14, Palette.ink3).apply {
                setPadding(0, dp(4), 0, 0)
            }
        )

        row.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun renderWatch(list: List<Video>) {
        shuffled = list
        watchList.removeAllViews()
        if (list.isEmpty()) {
            watchList.addView(centered("这里还是空的", Palette.ink2, "去「全部目录」看看"))
            return
        }
        list.forEachIndexed { i, v -> watchList.addView(watchRow(v, i)) }
        if (tab == 0) {
            watchList.post { watchList.getChildAt(0)?.requestFocus() }
            list.firstOrNull()?.let { showPreview(it, force = true) }
        }
    }

    private fun watchRow(v: Video, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), dp(6))
            background = shape(Color.TRANSPARENT, dp(14).toFloat())
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.bottomMargin = dp(2)
        row.layoutParams = lp

        val thumbW = dp(if (compact) 72 else 104)
        val thumbH = thumbW * 9 / 16
        val thumb = FrameLayout(this).apply {
            background = shape(Palette.cover(index), dp(8).toFloat())
            clipToOutline = true
        }
        thumb.addView(
            label(v.title.take(1), if (compact) 17 else 26, 0x66FFFFFF, display = true).apply {
                gravity = Gravity.CENTER
            },
            MATCH_PARENT, MATCH_PARENT
        )
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumb.addView(img, MATCH_PARENT, MATCH_PARENT)
        v.thumb?.let { url ->
            val cached = Thumbs.cached(url)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                Thumbs.load(url, cfg.user, cfg.pass, thumbW, thumbH) { bmp ->
                    if (bmp != null) img.setImageBitmap(bmp)
                }
            }
        }
        row.addView(thumb, thumbW, thumbH)

        val title = label(v.title, if (compact) 12 else 15, Palette.ink2).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, 0, 0)
        }
        row.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        row.setOnFocusChangeListener { _, has ->
            row.background = shape(
                if (has) Palette.card else Color.TRANSPARENT,
                dp(14).toFloat(),
                if (has) dp(3) else 0,
                Palette.ink
            )
            title.setTextColor(if (has) Palette.ink else Palette.ink2)
            if (has) showPreview(v)
        }
        row.setOnClickListener { playList(shuffled, index) }
        return row
    }

    private fun showPreview(v: Video, force: Boolean = false) {
        previewTitle.text = v.title
        if (!force && currentPreview == v.url) return
        currentPreview = v.url
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(if (force) 0L else 280L)
            val p = ensurePreviewPlayer()
            p.setMediaItem(MediaItem.fromUri(v.url))
            p.prepare()
            p.play()
        }
    }

    private fun ensurePreviewPlayer(): ExoPlayer {
        previewPlayer?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(authHeaders())
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
        val base = DefaultDataSource.Factory(this, http)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(base))
            .build()
        p.volume = 0f
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.playWhenReady = true

        val pv = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Palette.ink)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            player = p
        }
        previewView = pv
        previewBox.addView(pv, MATCH_PARENT, MATCH_PARENT)
        previewPlayer = p
        return p
    }

    private fun authHeaders(): Map<String, String> {
        val map = HashMap<String, String>()
        if (cfg.user.isNotBlank()) {
            map["Authorization"] = Credentials.basic(cfg.user, cfg.pass, Charsets.UTF_8)
        }
        return map
    }

    // ---------------- Tab 2：全部目录 ----------------

    private fun buildAllPage(): View {
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        allHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, 0, 0, dp(20))
        }
        scroller.addView(allHost, MATCH_PARENT, WRAP_CONTENT)
        return scroller
    }

    private fun renderAll(list: List<Section>) {
        allHost.removeAllViews()
        list.forEach { s ->
            allHost.addView(sectionHeader(s.title, s.videos.size))
            allHost.addView(sectionRow(s))
        }
        if (tab == 1) focusFirst(allHost)
    }

    private fun sectionHeader(title: String, count: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(if (compact) 12 else 18), dp(4), dp(6))
        }
        row.addView(label(title, if (compact) 16 else 21, Palette.ink, display = true))
        row.addView(
            label("  $count", if (compact) 11 else 14, Palette.ink3).apply {
                setPadding(dp(6), dp(4), 0, 0)
            }
        )
        return row
    }

    private fun sectionRow(section: Section): View {
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        strip.addView(row, WRAP_CONTENT, WRAP_CONTENT)
        section.videos.forEachIndexed { i, v -> row.addView(card(v, i, section)) }
        return strip
    }

    private fun card(video: Video, index: Int, section: Section): View {
        val w = dp(if (compact) 208 else 318)
        val h = dp(if (compact) 117 else 179)
        val radius = dp(if (compact) 14 else 20).toFloat()

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                rightMargin = dp(if (compact) 10 else 18)
            }
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            clipToOutline = true
            elevation = dp(2).toFloat()
            background = shape(Palette.card, radius, dp(2), Palette.paper2)
        }

        frame.addView(
            View(this).apply { setBackgroundColor(Palette.cover(index)) },
            MATCH_PARENT, MATCH_PARENT
        )
        frame.addView(
            label(video.title.take(1), if (compact) 40 else 64, 0x55FFFFFF, display = true).apply {
                gravity = Gravity.CENTER
            },
            MATCH_PARENT, MATCH_PARENT
        )

        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)

        val caption = label(video.title, if (compact) 12 else 16, Palette.ink, display = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = shape(
                0xF7FFFBF4.toInt(), 0f,
                radii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
            )
        }
        frame.addView(
            caption,
            FrameLayout.LayoutParams(MATCH_PARENT, dp(if (compact) 34 else 44), Gravity.BOTTOM)
        )

        video.thumb?.let { thumb ->
            val cached = Thumbs.cached(thumb)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                Thumbs.load(thumb, cfg.user, cfg.pass, w, h) { bmp ->
                    if (bmp != null) img.setImageBitmap(bmp)
                }
            }
        }

        frame.setOnFocusChangeListener { v, has ->
            v.animate()
                .scaleX(if (has) 1.05f else 1f)
                .scaleY(if (has) 1.05f else 1f)
                .setDuration(120)
                .start()
            v.elevation = dp(if (has) 16 else 2).toFloat()
            v.background = shape(
                Palette.card, radius,
                dp(if (has) 5 else 2),
                if (has) Palette.ink else Palette.paper2
            )
        }
        frame.setOnClickListener { playList(section.videos, index) }
        return frame
    }

    // ---------------- 数据 ----------------

    private fun showCached() {
        val cached = Library.loadCache(this, cfg)
        if (cached != null && cached.isNotEmpty()) renderData(cached) else showStatus("正在连接 NAS…")
    }

    private fun refresh() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Library.scan(cfg) } }
            result.onSuccess { list ->
                if (list.isEmpty()) {
                    if (sections.isEmpty()) {
                        showEmpty("NAS 里还没有视频", "把英语视频放进 ${cfg.rootPath} 就行")
                    }
                    return@onSuccess
                }
                Library.saveCache(this@MainActivity, cfg, list)
                renderData(list)
            }.onFailure { e ->
                if (sections.isEmpty()) {
                    showEmpty("连不上 NAS", e.message ?: "检查电视和 NAS 是否在同一个 WiFi")
                }
            }
        }
    }

    private fun renderData(list: List<Section>) {
        sections = list
        val flat = list.flatMap { it.videos }
        subtitle.text = "${list.size} 个目录 · ${flat.size} 集"
        renderWatch(flat.shuffled().take(if (compact) 30 else 40))
        renderAll(list)
        paintTabs()
    }

    private fun renderDemo() {
        runCatching {
            renderData(listOf(Demo.section(this)))
            subtitle.text = "演示模式 · 点右上角「家长」填 NAS 地址"
        }.onFailure {
            showEmpty("还没有接入 NAS", "点右上角「家长」，用手机填一下 NAS 地址")
        }
    }

    private fun showStatus(msg: String) {
        watchList.removeAllViews()
        watchList.addView(centered(msg, Palette.ink2, "正在读取 NAS 上的目录"))
        allHost.removeAllViews()
        allHost.addView(centered(msg, Palette.ink2, ""))
    }

    private fun showEmpty(title: String, detail: String) {
        watchList.removeAllViews()
        watchList.addView(centered(title, Palette.ink, detail))
        allHost.removeAllViews()
        allHost.addView(centered(title, Palette.ink, detail))
    }

    private fun centered(title: String, color: Int, detail: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(60), 0, dp(40))
        }
        box.addView(label(title, if (compact) 17 else 24, color, display = true))
        if (detail.isNotBlank()) {
            box.addView(
                label(detail, if (compact) 12 else 15, Palette.ink3).apply {
                    setPadding(0, dp(10), 0, 0)
                }
            )
        }
        return box
    }

    // ---------------- 交互 ----------------

    private fun playList(list: List<Video>, index: Int) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putStringArrayListExtra("urls", ArrayList(list.map { it.url }))
        intent.putStringArrayListExtra("titles", ArrayList(list.map { it.title }))
        intent.putExtra("index", index)
        startActivity(intent)
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "请输入家长 PIN"
            setSingleLine()
            setTextSize(24f)
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        val holder = FrameLayout(this).apply { addView(input) }
        AlertDialog.Builder(this)
            .setTitle("家长验证")
            .setMessage("默认 PIN 是 1234")
            .setView(holder)
            .setPositiveButton("进入设置") { _, _ ->
                if (input.text.toString().trim() == Prefs.pin(this)) {
                    startActivity(Intent(this, SetupActivity::class.java))
                } else {
                    toast("PIN 不正确")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backAt = SystemClock.uptimeMillis()
                KeyEvent.ACTION_UP -> {
                    val held = SystemClock.uptimeMillis() - backAt
                    backAt = 0
                    if (held >= 2000) finish() else toast("长按返回键 2 秒退出")
                    return true
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
