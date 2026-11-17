package com.dsh.kidstv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private lateinit var tabBooks: TextView
    private lateinit var pageWatch: View
    private lateinit var pageAll: View
    private lateinit var pageBooks: View
    private lateinit var booksHost: LinearLayout
    private lateinit var watchList: LinearLayout
    private lateinit var allHost: LinearLayout
    private var searchAll: LinearLayout? = null
    private var allSearchBox: EditText? = null

    // 扫描状态：顶部那行要能看出「还在动」，而不是卡死
    private var scanStep: String = "连接 NAS"
    private var scanStartAt: Long = 0L
    private var scanTicker: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seriesRenderJob: Runnable? = null
    private var searchBooks: LinearLayout? = null
    private var videoQuery: String = ""
    private var bookQuery: String = ""
    private var watchQuery: String = ""
    private var watchAll: List<Video> = emptyList()
    private lateinit var previewBox: FrameLayout
    /** 视频目录页的预览容器（和随心看共用同一个播放器，切 tab 时挪过去） */
    private lateinit var libPreviewBox: FrameLayout
    private lateinit var libPreviewSeries: TextView
    private lateinit var libPreviewEpisode: TextView
    private lateinit var seriesStripHost: LinearLayout
    private lateinit var episodeList: LinearLayout
    private val seriesTabBinds = ArrayList<(Boolean) -> Unit>()
    private var selectedSeries = 0
    private var libSection: Section? = null
    private var libIndex = 0
    /** 预览播放器当前挂在哪个容器里 */
    private var previewHost: FrameLayout? = null
    private lateinit var previewSeries: TextView
    private lateinit var previewEpisode: TextView
    private lateinit var subtitle: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sections: List<Section> = emptyList()
    /** 当前显示的是不是演示片（用来判断"连不上 NAS"要不要盖掉演示墙） */
    private var showingDemo = false
    /** 启动时盖在内容上的「正在扫描」，扫完才露出主界面 */
    private lateinit var splash: View
    private lateinit var splashStep: TextView
    private var books: List<Book> = emptyList()
    private var booksLoaded = false
    private var shuffled: List<Video> = emptyList()
    private var tab = 0

    /** 每集属于哪个系列（随心看只拿得到扁平列表） */
    private var seriesOf: Map<String, String> = emptyMap()
    /** 系列 -> 用来抽封面的那一集（每个系列只抽一次） */
    private var coverUrlFor: Map<String, String> = emptyMap()
    /** url -> Video，历史卡片查封面用 */
    private var videoByUrl: Map<String, Video> = emptyMap()
    private var previewPlayer: ExoPlayer? = null
    private var previewView: PlayerView? = null
    private var previewJob: Job? = null
    private var currentPreview: String? = null
    /**
     * 正在重建内容区。
     * removeAllViews 会让焦点短暂悬空，系统可能把焦点塞给最近的 tab，
     * 这时 tab 的「焦点即切页」会误触发（点开一个目录却跳回随心看）。
     */
    private var rebuilding: Boolean = false

    private fun beginRebuild() {
        rebuilding = true
        Handler(Looper.getMainLooper()).postDelayed({ rebuilding = false }, 700)
    }


    /** 窄屏（手机竖屏）紧凑排版；电视上仍是 10 尺大字 */
    private val compact: Boolean get() = resources.configuration.screenWidthDp < 600
    private val padH: Int get() = dp(if (compact) 18 else 52)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
        Frames.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        cfg = Prefs.load(this)
        setContentView(buildUi())

        if (cfg.ready) {
            // 和 kidsbox 一样：每次冷启动都过一遍启动页。
            // 但有缓存时不让人干等 —— 露个脸就淡出，扫描继续在后台跑。
            val hadCache = showCached()
            showSplash()
            // 计时要从“启动页真的画出来”开始算，否则 onCreate 到首帧就过去了 1 秒，
            // banner 等于没显示。先 post 等布局完成，再延时。
            if (hadCache) splash.post { splash.postDelayed({ hideSplash() }, 700) }
            refresh()
        } else {
            renderDemo()
            // 还没配置 NAS：直接把「家长设置」推到最前面。
            // 电视上用遥控器摸焦点太痛苦，这一步省掉；手机上填完按返回就进视频墙。
            if (savedInstanceState == null) {
                startActivity(Intent(this, SetupActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (tab == 0 && currentPreview != null) previewPlayer?.play()
        // 整份配置比对，不能只比 cacheKey（scheme+host+port+path）：
        // 只改账号或密码时地址没变，旧代码会一直拿旧密码去请求，只有杀进程才生效。
        val now = Prefs.load(this)
        if (now != cfg) {
            cfg = now
            booksLoaded = false
            books = emptyList()
            currentPreview = null
            if (now.ready) {
                if (!showCached()) showSplash()
                refresh()
            } else {
                // 家长把 NAS 清空了：退回演示墙，别继续显示上一份内容
                sections = emptyList()
                seriesOf = emptyMap()
                renderDemo()
            }
            // 停在绘本页时也要跟着换掉旧 NAS 的书（否则卡片还在，点开却全是旧地址）
            if (tab == 2) loadBooks()
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
        pageBooks = buildBooksPage()
        content.addView(pageWatch, MATCH_PARENT, MATCH_PARENT)
        content.addView(pageAll, MATCH_PARENT, MATCH_PARENT)
        content.addView(pageBooks, MATCH_PARENT, MATCH_PARENT)
        pageAll.visibility = View.GONE
        pageBooks.visibility = View.GONE

        paintTabs()
        root.addView(buildSplash(), MATCH_PARENT, MATCH_PARENT)
        return root
    }

    private fun buildSplash(): View {
        val wrap = FrameLayout(this).apply {
            setBackgroundColor(Palette.paper)
            visibility = View.GONE
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(label("夏夏爱学习", if (compact) 26 else 40, Palette.ink, display = true).apply {
            gravity = Gravity.CENTER
        })
        // 圆角进度条：系统 ProgressBar 的轨道是直角的，套一层圆角容器裁一下
        val track = FrameLayout(this).apply {
            background = shape(Palette.paper2, dp(999).toFloat())
            clipToOutline = true
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Palette.coral)
            progressBackgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        }
        track.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, dp(if (compact) 5 else 6), Gravity.CENTER_VERTICAL))
        col.addView(
            track,
            LinearLayout.LayoutParams(dp(if (compact) 220 else 420), dp(if (compact) 5 else 6)).apply { topMargin = dp(26) }
        )
        splashStep = label("正在连接 NAS…", if (compact) 13 else 16, Palette.ink2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(splashStep)
        col.addView(
            label("第一次扫描会慢一点，之后有缓存就秒开", if (compact) 11 else 13, Palette.ink3).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
        )
        wrap.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        splash = wrap
        return wrap
    }

    private fun showSplash() {
        splash.alpha = 1f
        splash.visibility = View.VISIBLE
        splashStep.text = "正在连接 NAS…"
        // 中继慢的时候别把人永远锁在启动页：45 秒还没扫完就先放行
        scope.launch {
            delay(45_000)
            if (splash.visibility == View.VISIBLE) hideSplash()
        }
    }

    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE) return
        splash.animate().alpha(0f).setDuration(220)
            .withEndAction { splash.visibility = View.GONE }
            .start()
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(if (compact) 8 else 14))
        }

        // 左上角不再放大标题，只留一行状态（N 个目录 · M 集 / 扫描进度）
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        subtitle = label("", if (compact) 11 else 13, Palette.ink3).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        brand.addView(subtitle)
        row.addView(brand, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabWatch = tabPill("随心看", 0)
        tabAll = tabPill("视频目录", 1)
        tabBooks = tabPill("绘本", 2)
        tabs.addView(tabWatch)
        tabs.addView(tabAll)
        tabs.addView(tabBooks)
        row.addView(tabs)

        val parent = label("家长", if (compact) 11 else 13, Palette.ink2).apply {
            setPadding(
                dp(if (compact) 12 else 18), dp(if (compact) 7 else 9),
                dp(if (compact) 12 else 18), dp(if (compact) 7 else 9)
            )
            background = shape(Palette.card, dp(30).toFloat(), dp(1), Palette.paper2)
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { askPin() }
            // 遥控器焦点必须有反馈，不然孩子根本看不出焦点停在「家长」上
            setOnFocusChangeListener { v, has ->
                v.background = shape(
                    if (has) Palette.ink else Palette.card,
                    dp(30).toFloat(), dp(1), if (has) Palette.ink else Palette.paper2
                )
                (v as TextView).setTextColor(if (has) Palette.paper else Palette.ink2)
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.leftMargin = dp(if (compact) 8 else 18)
        row.addView(parent, lp)
        return row
    }

    private fun tabPill(text: String, index: Int): TextView {
        val tv = label(text, if (compact) 11 else 17, Palette.ink2, display = true).apply {
            setPadding(
                dp(if (compact) 9 else 20), dp(if (compact) 6 else 9),
                dp(if (compact) 9 else 20), dp(if (compact) 6 else 9)
            )
            background = shape(Color.TRANSPARENT, dp(24).toFloat())
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { switchTab(index) }
            // 焦点一到 tab 就切页，不用再按 OK
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !rebuilding && tab != index) switchTab(index) else paintTabs()
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.rightMargin = dp(6)
        tv.layoutParams = lp
        return tv
    }

    private fun paintTabs() {
        listOf(tabWatch to 0, tabAll to 1, tabBooks to 2).forEach { (tv, i) ->
            val on = tab == i
            val focused = tv.hasFocus()
            tv.setTextColor(if (focused || on) Palette.paper else Palette.ink2)
            tv.background = shape(
                when {
                    focused -> Palette.coral
                    on -> Palette.ink
                    else -> Color.TRANSPARENT
                },
                dp(24).toFloat()
            )
        }
    }

    private fun switchTab(index: Int) {
        if (tab == index) return
        tab = index
        paintTabs()
        pageWatch.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageAll.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageBooks.visibility = if (index == 2) View.VISIBLE else View.GONE
        // 焦点留在 tab 上：左右键换 tab，OK 选中，按下键进内容区
        previewHost = if (index == 0) previewBox else libPreviewBox
        if (index == 0) {
            previewPlayer?.play()
        } else {
            previewPlayer?.pause()
        }
        if (index == 1) {
            // 切回视频目录时把预览挪到这一页
            libSection?.let { sec -> showLibPreview(sec, libIndex) }
        }
        if (index == 2) loadBooks()
    }

    // ---------------- Tab 1：随心看 ----------------

    private fun buildWatchPage(): View {
        val row = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
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

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        leftCol.addView(
            searchBar(
                if (compact) "搜这一集的名字" else "搜分集名字，比如 Muddy Puddles",
                watchQuery,
                onChange = { q ->
                    watchQuery = q
                    renderWatch(watchAll)
                }
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        leftCol.addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 右边一大块：当前选中剧集的实时预览
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 4 else 20), 0, 0, 0)
        }

        previewBox = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val w = MeasureSpec.getSize(widthMeasureSpec)
                // 高度不能只按 16:9 算：手机上横屏时整屏宽算出来 450dp 高，
                // 会把下面那栏列表挤得一点不剩。给个屏幕高度的上限。
                val cap = (resources.displayMetrics.heightPixels * 0.42f).toInt()
                setMeasuredDimension(w, minOf((w * 9) / 16, cap))
            }
        }.apply {
            background = shape(Palette.bg, dp(if (compact) 12 else 22).toFloat())
            clipToOutline = true
        }
        previewBox.isClickable = true
        previewBox.setOnClickListener { playFromPreview() }
        right.addView(previewBox, MATCH_PARENT, WRAP_CONTENT)

        previewSeries = label("", if (compact) 18 else 28, Palette.ink, display = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(if (compact) 8 else 16), 0, 0)
        }
        right.addView(previewSeries)

        previewEpisode = label("", if (compact) 12 else 15, Palette.ink2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        right.addView(previewEpisode)

        // 底部一行：▶ 提示文字 …… 珊瑚色「全屏播放」胶囊（遥控器能聚焦）
        val hintRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (compact) 8 else 12), 0, 0)
        }
        hintRow.addView(
            label("▶", if (compact) 10 else 12, Palette.ink3).apply {
                setPadding(0, 0, dp(6), 0)
            }
        )
        hintRow.addView(
            label("OK 全屏播放 · 预览静音", if (compact) 10 else 12, Palette.ink3).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val playPill = label("全屏播放", if (compact) 11 else 13, Palette.card, display = true).apply {
            setPadding(dp(if (compact) 12 else 16), dp(if (compact) 6 else 8),
                dp(if (compact) 12 else 16), dp(if (compact) 6 else 8))
            background = shape(Palette.coral, dp(999).toFloat())
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { playFromPreview() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                    .setDuration(120).start()
                v.background = shape(if (has) Palette.ink else Palette.coral, dp(999).toFloat())
                (v as TextView).setTextColor(if (has) Palette.paper else Palette.card)
            }
        }
        hintRow.addView(playPill)
        right.addView(hintRow)

        // 预览区变窄时别把列表挤没：给预览一个最大宽度
        right.minimumWidth = 0

        if (compact) {
            // 竖屏：预览在上、列表在下
            row.addView(right, MATCH_PARENT, WRAP_CONTENT)
            row.addView(leftCol, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            // 电视：左列表、右预览
            row.addView(leftCol, LinearLayout.LayoutParams(dp(340), MATCH_PARENT))
            row.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        return row
    }

    private fun renderWatch(list: List<Video>) {
        beginRebuild()
        watchAll = list

        val q = watchQuery.trim()
        val shown = if (q.isEmpty()) list else {
            val key = q.lowercase()
            // 随心看这一栏只按「分集名字」筛，整部剧的名字去「视频目录」搜
            list.filter { it.title.lowercase().contains(key) }
        }

        watchList.removeAllViews()
        if (q.isNotEmpty() && shown.isEmpty()) {
            watchList.addView(centered("没找到「$q」", Palette.ink, "换个关键词，或点搜索框右边「清空」"))
            watchRowBinds.clear()
            return
        }
        shuffled = shown
        if (shown.isEmpty()) {
            watchList.addView(centered("这里还是空的", Palette.ink2, "去「视频目录」看看"))
            return
        }
        watchRowBinds.clear()
        // 刷新后尽量停回原来那一集，而不是跳回第一行
        val keepUrl = previewUrl
        selectedWatch = keepUrl?.let { u -> shown.indexOfFirst { it.url == u } }?.takeIf { it >= 0 } ?: 0
        // 搜索时不显示"最近看过"，免得干扰结果
        if (q.isEmpty()) addRecentStrip()
        shown.forEachIndexed { i, v -> watchList.addView(watchRow(v, i)) }
        watchRowBinds.forEachIndexed { i, bind -> bind(i == selectedWatch) }
        if (tab == 0) {
            watchList.post { watchList.getChildAt(selectedWatch)?.requestFocus() }
            shown.getOrNull(selectedWatch)?.let { showPreview(it, force = true) }
        }
    }

    /** 列表项：左边封面，右边上面剧名、下面集名 */
    private fun watchRow(v: Video, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), dp(6))
            background = shape(Color.TRANSPARENT, dp(14).toFloat())
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.bottomMargin = dp(2)
        row.layoutParams = lp

        val thumbW = dp(if (compact) 76 else 92)
        val thumbH = dp(if (compact) 43 else 52)
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
        bindCover(img, v.thumb, coverUrlFor[seriesOf[v.url]], thumbW, thumbH)
        row.addView(thumb, thumbW, thumbH)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val seriesName = seriesOf[v.url].orEmpty().ifBlank { "随心看" }
        val seriesLabel = label(seriesName, if (compact) 12 else 15, Palette.ink2, display = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val episodeLabel = label(v.title, if (compact) 10 else 12, Palette.ink3).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        texts.addView(seriesLabel)
        texts.addView(episodeLabel)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // 选中行右侧浮出的珊瑚色播放圆点（电视上提示"按 OK 就播这一集"）
        val dotSize = dp(if (compact) 24 else 30)
        val playDot = label("▶", if (compact) 10 else 12, Palette.card, display = true).apply {
            gravity = Gravity.CENTER
            background = shape(Palette.coral, dp(999).toFloat())
            visibility = View.GONE
        }
        row.addView(playDot, LinearLayout.LayoutParams(dotSize, dotSize))

        fun bind(on: Boolean) {
            row.background = shape(
                if (on) Palette.card else Color.TRANSPARENT,
                dp(14).toFloat(),
                if (on) dp(2.5f) else 0,
                Palette.ink
            )
            seriesLabel.setTextColor(if (on) Palette.ink else Palette.ink2)
            episodeLabel.setTextColor(if (on) Palette.ink2 else Palette.ink3)
            playDot.visibility = if (on) View.VISIBLE else View.GONE
        }
        watchRowBinds.add(::bind)
        bind(false)

        // 遥控器：焦点即预览，OK 直接播
        row.setOnFocusChangeListener { _, has ->
            bind(has)
            if (has) {
                selectedWatch = index
                showPreview(v)
            }
        }
        // 触摸：点一行只是"选中"并预览；播放交给大画面或「全屏播放」
        row.setOnClickListener {
            if (watchQuery.isNotBlank()) SearchHistory.add(this, watchQuery)
            if (row.isInTouchMode) selectRow(index) else playList(shuffled, index)
        }
        return row
    }

    private var previewUrl: String? = null
    /** 触摸模式下当前"选中"的是第几行（手机上没有焦点概念） */
    private var selectedWatch = 0
    /** 每一行怎么根据选中状态重绘自己 */
    private val watchRowBinds = ArrayList<(Boolean) -> Unit>()

    /** 顶部「最近看过」横向条 */
    private fun addRecentStrip() {
        val items = History.load(this)
        if (items.isEmpty()) return
        watchList.addView(
            label("最近看过", if (compact) 12 else 15, Palette.ink2, display = true).apply {
                setPadding(dp(8), dp(2), 0, dp(6))
            }
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        items.take(12).forEach { h -> row.addView(recentCard(h)) }
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, WRAP_CONTENT, WRAP_CONTENT)
        }
        watchList.addView(
            strip,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        )
    }

    private fun recentCard(h: HistoryItem): View {
        val w = dp(if (compact) 132 else 176)
        val hgt = w * 9 / 16
        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, hgt).apply { rightMargin = dp(10) }
            background = shape(Palette.cover(h.url.hashCode()), dp(10).toFloat())
            clipToOutline = true
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(img, MATCH_PARENT, MATCH_PARENT)
        videoByUrl[h.url]?.thumb?.let { url ->
            val cached = Thumbs.cached(url)
            if (cached != null) img.setImageBitmap(cached)
            else Thumbs.load(url, cfg.user, cfg.pass, w, hgt) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
        }
        val caption = label(h.title, if (compact) 10 else 12, Palette.card).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = shape(0xCC000000.toInt(), 0f, radii = floatArrayOf(0f, 0f, 0f, 0f, dp(10).toFloat(), dp(10).toFloat(), 0f, 0f))
        }
        card.addView(caption, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        card.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
        }
        card.setOnClickListener {
            val sec = sections.firstOrNull { s -> s.videos.any { it.url == h.url } }
            if (sec != null) {
                val i = sec.videos.indexOfFirst { it.url == h.url }
                playList(sec.videos, i.coerceAtLeast(0))
            } else {
                playList(listOf(Video(h.title, h.url, null)), 0)
            }
        }
        return card
    }

    /** 手机上点一行：只切换预览，不直接播 */
    private fun selectRow(index: Int) {
        if (index !in shuffled.indices) return
        selectedWatch = index
        watchRowBinds.forEachIndexed { i, bind -> bind(i == index) }
        showPreview(shuffled[index], force = true)
    }

    /** 点/聚焦「全屏播放」胶囊：从当前预览的这一集开始播 */
    private fun playFromPreview() {
        val url = previewUrl ?: return
        val i = shuffled.indexOfFirst { it.url == url }
        if (i >= 0) playList(shuffled, i)
    }

    /** 焦点落到哪一行，右边就播哪一集（静音、循环、260ms 防抖） */
    private fun showPreview(v: Video, force: Boolean = false) {
        previewUrl = v.url
        // 只有「随心看」那套标签需要跟着变；视频目录有自己的标题行
        if (previewHost == previewBox || previewHost == null) {
            previewSeries.text = seriesOf[v.url].orEmpty().ifBlank { "随心看" }
            previewEpisode.text = v.title
        }
        // 先把播放器（PlayerView）挪到当前容器的位置，再判断要不要换片源 ——
        // 否则两个 tab 预览同一集时，画面会留在上一个 tab 里
        val p = ensurePreviewPlayer()
        if (!force && currentPreview == v.url) return
        currentPreview = v.url
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(if (force) 0L else 260L)
            p.setMediaItem(MediaItem.fromUri(v.url))
            p.prepare()
            p.play()
        }
    }

    private fun ensurePreviewPlayer(): ExoPlayer {
        val host = previewHost ?: previewBox
        previewPlayer?.let {
            val pv = previewView
            if (pv != null && pv.parent !== host) {
                (pv.parent as? ViewGroup)?.removeView(pv)
                host.addView(pv, MATCH_PARENT, MATCH_PARENT)
            }
            return it
        }
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
            setShutterBackgroundColor(Palette.bg)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            player = p
            // 预览层盖在 previewBox 上面，点击得挂它自己，否则父布局收不到
            isClickable = true
            setOnClickListener { playFromPreview() }
        }
        previewView = pv
        host.addView(pv, MATCH_PARENT, MATCH_PARENT)
        previewHost = host
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

    // ---------------- Tab 2：视频目录 ----------------

    private fun buildAllPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val box = searchBar(
            "搜系列名（比如 小猪佩奇 / Little Fox）", videoQuery,
            onChange = { q ->
                videoQuery = q
                selectedSeries = 0
                renderAll(sections)
            },
            onBox = { allSearchBox = it }
        )
        searchAll = box
        page.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 二级 tab：一个系列一个胶囊，焦点一到就切换
        seriesStripHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
            clipChildren = false
        }
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(seriesStripHost, WRAP_CONTENT, WRAP_CONTENT)
        }
        page.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val body = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            clipChildren = false
        }
        page.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        episodeList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(if (compact) 4 else 12), dp(16))
        }
        scroller.addView(episodeList, MATCH_PARENT, WRAP_CONTENT)
        allHost = episodeList

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 4 else 20), 0, 0, 0)
        }
        libPreviewBox = aspectBox()
        right.addView(libPreviewBox, MATCH_PARENT, WRAP_CONTENT)

        libPreviewSeries = label("", if (compact) 18 else 26, Palette.ink, display = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(if (compact) 8 else 14), 0, 0)
        }
        right.addView(libPreviewSeries)

        libPreviewEpisode = label("", if (compact) 12 else 15, Palette.ink2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        right.addView(libPreviewEpisode)

        val playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (compact) 8 else 12), 0, 0)
        }
        playRow.addView(label("▶", if (compact) 10 else 12, Palette.ink3).apply { setPadding(0, 0, dp(6), 0) })
        playRow.addView(
            label("OK 全屏播放 · 预览静音", if (compact) 10 else 12, Palette.ink3),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        playRow.addView(playPill("看正片") { libSection?.let { sec -> playList(sec.videos, libIndex) } })
        right.addView(playRow)

        if (compact) {
            body.addView(right, MATCH_PARENT, WRAP_CONTENT)
            body.addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            body.addView(scroller, LinearLayout.LayoutParams(dp(340), MATCH_PARENT))
            body.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        return page
    }

    /** 16:9 预览框，高度不超过屏幕的 42%（手机上横屏时给列表留位置） */
    private fun aspectBox(): FrameLayout = object : FrameLayout(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cap = (resources.displayMetrics.heightPixels * 0.42f).toInt()
            setMeasuredDimension(w, minOf((w * 9) / 16, cap))
        }
    }.apply {
        background = shape(Palette.bg, dp(if (compact) 12 else 20).toFloat())
        clipToOutline = true
    }

    /** 珊瑚色「看正片」胶囊；焦点态变成浅色实心（和顶部 tab 一致） */
    private fun playPill(text: String, onClick: () -> Unit): TextView =
        label(text, if (compact) 11 else 13, Palette.onAccent, display = true).apply {
            setPadding(
                dp(if (compact) 12 else 16), dp(if (compact) 6 else 8),
                dp(if (compact) 12 else 16), dp(if (compact) 6 else 8)
            )
            background = shape(Palette.coral, dp(999).toFloat())
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                    .setDuration(120).start()
                v.background = shape(if (has) Palette.ink else Palette.coral, dp(999).toFloat())
                (v as TextView).setTextColor(Palette.onAccent)
            }
        }

    /**
     * 搜索区：输入框 + 历史记录。
     * 遥控器按 OK 调出输入法；框里为空时把最近搜过的词摆出来，点一下就填回去。
     */
    private fun searchBar(
        hint: String,
        initial: String,
        onChange: (String) -> Unit,
        onBox: ((EditText) -> Unit)? = null
    ): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val box = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = if (compact) 13f else 15f
            setTextColor(Palette.ink)
            setHintTextColor(Palette.ink3)
            background = shape(Palette.card, dp(22).toFloat(), dp(1), Palette.paper2)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            isFocusableInTouchMode = true
            // 不主动弹键盘：遥控器/手指按一下才出来，否则一进页面就被输入法糊住
            showSoftInputOnFocus = false
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnClickListener { showKeyboard(this) }
            if (initial.isNotEmpty()) {
                setText(initial)
                setSelection(initial.length)
            }
        }
        line.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        onBox?.invoke(box)

        val clear = label("清空", if (compact) 11 else 13, Palette.ink2).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = shape(Palette.card, dp(18).toFloat(), dp(1), Palette.paper2)
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            visibility = if (box.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            setOnClickListener {
                box.setText("")
                onChange("")
            }
            setOnFocusChangeListener { v, has ->
                v.background = shape(
                    if (has) Palette.ink else Palette.card,
                    dp(18).toFloat(), dp(1), if (has) Palette.ink else Palette.paper2
                )
                (v as TextView).setTextColor(if (has) Palette.paper else Palette.ink2)
            }
        }
        line.addView(clear, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        wrap.addView(line)

        // 历史记录
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val chipScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(10), 0, 0)
            addView(chips)
        }
        wrap.addView(chipScroller)

        fun refreshChips() {
            chips.removeAllViews()
            val recent = SearchHistory.load(this)
            val show = recent.isNotEmpty() && box.text.isEmpty()
            chipScroller.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) return

            chips.addView(label("最近", if (compact) 11 else 13, Palette.ink3).apply {
                setPadding(0, 0, dp(10), 0)
            })

            recent.take(6).forEach { q ->
                val chip = label(q, if (compact) 11 else 13, Palette.ink2).apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = shape(Palette.card, dp(16).toFloat(), dp(1), Palette.paper2)
                    isFocusable = true
                    isClickable = true
                    noDefaultFocusRing()
                    setOnClickListener {
                        box.setText(q)
                        box.setSelection(q.length)
                        onChange(q)
                    }
                    setOnFocusChangeListener { v, has ->
                        v.background = shape(
                            if (has) Palette.coral else Palette.card,
                            dp(16).toFloat(), dp(1), if (has) Palette.coral else Palette.paper2
                        )
                        (v as TextView).setTextColor(if (has) Palette.paper else Palette.ink2)
                    }
                }
                chips.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
        }
        refreshChips()

        box.setOnFocusChangeListener { _, has ->
            if (has) refreshChips()
        }

        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                chipScroller.visibility =
                    if (s.isNullOrEmpty() && SearchHistory.load(this@MainActivity).isNotEmpty()) View.VISIBLE else View.GONE
                onChange(s?.toString() ?: "")
            }
        })

        // 输入法按「搜索」：记一条历史，收键盘
        box.setOnEditorActionListener { _, _, _ ->
            val q = box.text.toString().trim()
            if (q.isNotEmpty()) {
                SearchHistory.add(this, q)
                refreshChips()
            }
            hideKeyboard()
            true
        }

        wrap.setPadding(0, 0, 0, dp(12))
        return wrap
    }

    private fun showKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun renderAll(list: List<Section>) {
        beginRebuild()
        seriesStripHost.removeAllViews()
        episodeList.removeAllViews()
        seriesTabBinds.clear()
        libSection = null

        val q = videoQuery.trim()
        val shown = if (q.isEmpty()) list else list.filter { it.title.lowercase().contains(q.lowercase()) }

        if (shown.isEmpty()) {
            libPreviewSeries.text = ""
            libPreviewEpisode.text = ""
            episodeList.addView(
                centered(
                    if (q.isEmpty()) "NAS 上还没找到视频" else "没有名字包含「$q」的系列",
                    Palette.ink2,
                    if (q.isEmpty()) "去「家长」里检查目录" else "点搜索框右边「清空」看全部"
                )
            )
            return
        }

        if (selectedSeries >= shown.size) selectedSeries = 0
        shown.forEachIndexed { i, sec -> seriesStripHost.addView(seriesTab(sec, i, shown)) }
        paintSeriesTabs()
        renderEpisodes(shown, shown[selectedSeries])
    }

    /** 二级 tab 胶囊 */
    private fun seriesTab(sec: Section, index: Int, shown: List<Section>): View {
        val tv = label(sec.title, if (compact) 12 else 15, Palette.ink2, display = true).apply {
            setPadding(
                dp(if (compact) 10 else 14), dp(if (compact) 5 else 7),
                dp(if (compact) 10 else 14), dp(if (compact) 5 else 7)
            )
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            setOnFocusChangeListener { _, has ->
                if (!has) return@setOnFocusChangeListener
                if (selectedSeries != index) {
                    selectedSeries = index
                    paintSeriesTabs()
                    // 遥控器连按时焦点会快速划过一串 tab：
                    // 停 90ms 再渲染，划过去的那些不白建列表（否则大系列要卡好几秒）
                    seriesRenderJob?.let { handler.removeCallbacks(it) }
                    val job = Runnable { renderEpisodes(shown, shown[index]) }
                    seriesRenderJob = job
                    handler.postDelayed(job, 90)
                } else {
                    paintSeriesTabs()
                }
            }
            setOnClickListener {
                seriesRenderJob?.let { handler.removeCallbacks(it) }
                selectedSeries = index
                renderEpisodes(shown, shown[index], grabFocus = true)
                paintSeriesTabs()
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.rightMargin = dp(8)
        tv.layoutParams = lp
        seriesTabBinds.add { on ->
            tv.setTextColor(if (on) Palette.onAccent else Palette.ink2)
            tv.background = shape(if (on) Palette.coral else Palette.card, dp(999).toFloat())
        }
        return tv
    }

    private fun paintSeriesTabs() {
        seriesTabBinds.forEachIndexed { i, bind -> bind(i == selectedSeries) }
    }

    private fun renderEpisodes(shown: List<Section>, sec: Section, grabFocus: Boolean = false) {
        val same = libSection === sec && episodeList.childCount == sec.videos.size && sec.videos.isNotEmpty()
        libSection = sec
        if (!same) {
            libIndex = 0
            episodeList.removeAllViews()
            sec.videos.forEachIndexed { i, v -> episodeList.addView(libRow(sec, v, i)) }
        }
        if (sec.videos.isNotEmpty()) showLibPreview(sec, libIndex.coerceIn(0, sec.videos.size - 1))
        // 只有「按 OK 选定这个系列」才把焦点送进分集列表；
        // 遥控器左右滑过 tab 时如果也抢焦点，就会一格都滑不动，直接被拽下来。
        if (grabFocus && tab == 1) episodeList.post { episodeList.getChildAt(0)?.requestFocus() }
    }

    /** 视频目录里的分集行：序号 + 封面 + 集名 */
    private fun libRow(sec: Section, v: Video, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), dp(6))
            background = shape(Color.TRANSPARENT, dp(12).toFloat())
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
        }
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(2) }

        val num = label(String.format("%02d", index + 1), if (compact) 11 else 13, Palette.ink3, display = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(10), 0)
            minWidth = dp(if (compact) 26 else 34)
        }
        row.addView(num)

        val thumbW = dp(if (compact) 76 else 92)
        val thumbH = dp(if (compact) 43 else 52)
        val thumb = FrameLayout(this).apply {
            background = shape(Palette.cover(index), dp(8).toFloat())
            clipToOutline = true
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumb.addView(img, MATCH_PARENT, MATCH_PARENT)
        bindCover(img, v.thumb, coverUrlFor[sec.title], thumbW, thumbH)
        row.addView(thumb, thumbW, thumbH)

        val title = label(v.title, if (compact) 12 else 14, Palette.ink2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val dotSize = dp(if (compact) 24 else 30)
        val playDot = label("▶", if (compact) 10 else 12, Palette.card, display = true).apply {
            gravity = Gravity.CENTER
            background = shape(Palette.coral, dp(999).toFloat())
            visibility = View.GONE
        }
        row.addView(playDot, LinearLayout.LayoutParams(dotSize, dotSize))

        row.setOnFocusChangeListener { _, has ->
            row.background = shape(
                if (has) Palette.card else Color.TRANSPARENT,
                dp(12).toFloat(),
                if (has) dp(2) else 0,
                Palette.ink
            )
            title.setTextColor(if (has) Palette.ink else Palette.ink2)
            playDot.visibility = if (has) View.VISIBLE else View.GONE
            if (has) showLibPreview(sec, index)
        }
        row.setOnClickListener { playList(sec.videos, index) }
        return row
    }

    private fun showLibPreview(sec: Section, index: Int) {
        val v = sec.videos.getOrNull(index) ?: return
        libSection = sec
        libIndex = index
        libPreviewSeries.text = sec.title
        libPreviewEpisode.text = v.title
        // 只有「视频目录」在前台时才真的起播，否则会把随心看的预览抢走
        if (tab != 1) return
        previewHost = libPreviewBox
        showPreview(v)
    }

    /** 卡片间距 */
    private val gridGap: Int get() = dp(if (compact) 10 else 18)

    /** 一行放几张：按可用宽度算，最小卡宽 190dp（手机 170dp），保证不会被裁 */
    private val gridColumns: Int by lazy {
        val content = resources.displayMetrics.widthPixels - 2 * padH
        val minCard = dp(if (compact) 170 else 190)
        ((content + gridGap) / (minCard + gridGap)).coerceIn(2, 6)
    }

    /** 每张卡片实际宽度：把一行铺满，最后一行也不会忽宽忽窄 */
    private val gridCardW: Int by lazy {
        val content = resources.displayMetrics.widthPixels - 2 * padH
        (content - (gridColumns - 1) * gridGap) / gridColumns
    }

    private fun row(gap: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        setPadding(0, 0, 0, dp(gap))
    }

    /** 有同名封面图就用图；没有就从视频里抽一帧；都不行就留彩色占位 */
    private fun bindCover(img: ImageView, coverUrl: String?, frameUrl: String?, w: Int, h: Int) {
        if (coverUrl != null) {
            val cached = Thumbs.cached(coverUrl)
            if (cached != null) {
                img.setImageBitmap(cached)
                return
            }
            Thumbs.load(coverUrl, cfg.user, cfg.pass, w, h) { bmp ->
                if (bmp != null) img.setImageBitmap(bmp)
            }
            return
        }
        if (frameUrl == null) return
        val cached = Frames.cached(frameUrl)
        if (cached != null) {
            img.setImageBitmap(cached)
            return
        }
        Frames.load(frameUrl, cfg.user, cfg.pass, w, h) { bmp ->
            if (bmp != null) img.setImageBitmap(bmp)
        }
    }

    private fun buildBooksPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val box = searchBar("搜绘本名字", bookQuery, onChange = { q ->
            bookQuery = q
            renderBooks(books)
        })
        searchBooks = box
        page.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        booksHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, dp(6), 0, dp(24))
        }
        scroller.addView(booksHost, MATCH_PARENT, WRAP_CONTENT)
        booksHost.addView(centered("正在找绘本…", Palette.ink2, "扫 NAS 上的 PDF"))
        page.addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return page
    }

    private fun loadBooks() {
        if (booksLoaded) return
        if (!cfg.ready) {
            booksHost.removeAllViews()
            booksHost.addView(centered("还没有接入 NAS", Palette.ink2, "点右上角「家长」填一下地址"))
            return
        }
        booksLoaded = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Books.scan(cfg) } }
            result.onSuccess { list ->
                books = list
                renderBooks(list)
            }.onFailure { e ->
                booksHost.removeAllViews()
                booksHost.addView(centered("读不到绘本目录", Palette.ink, e.message ?: "检查 NAS 连接"))
            }
        }
    }

    private fun renderBooks(all: List<Book>) {
        beginRebuild()
        booksHost.removeAllViews()

        val q = bookQuery.trim()
        val list = if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q.lowercase()) }

        // 手机上点之前没有"焦点"这个动作，先把最前面几本预取上
        list.take(3).forEach { BookCache.prefetch(this, it.url) }
        if (q.isNotEmpty() && list.isEmpty()) {
            booksHost.addView(centered("没找到「$q」", Palette.ink, "换个关键词试试"))
            return
        }
        if (list.isEmpty()) {
            booksHost.addView(centered("还没有绘本", Palette.ink2, "往「${cfg.booksRoot}」里放 PDF 就会出现在这里"))
            return
        }
        val perRow = gridColumns
        list.chunked(perRow).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                setPadding(0, 0, 0, dp(if (compact) 8 else 14))
            }
            row.forEachIndexed { i, book ->
                rowView.addView(bookCard(book, rowIndex * perRow + i))
            }
            booksHost.addView(rowView)
        }
    }

    private fun bookCard(book: Book, index: Int): View {
        val w = gridCardW
        val h = w * 131 / 100
        val radius = dp(if (compact) 12 else 16).toFloat()

        val frame = object : FrameLayout(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, heightSpec)
                val width = MeasureSpec.getSize(widthSpec)
                setMeasuredDimension(width, width * 131 / 100)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                rightMargin = gridGap
            }
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            clipToOutline = true
            elevation = dp(2).toFloat()
            background = shape(Palette.card, radius, dp(2), Palette.paper2)
        }

        // 书脊色块
        frame.addView(
            View(this).apply { setBackgroundColor(Palette.cover(index)) },
            FrameLayout.LayoutParams(dp(if (compact) 8 else 12), MATCH_PARENT, Gravity.START)
        )
        frame.addView(
            label(book.title.take(1), if (compact) 44 else 60, 0x33000000, display = true).apply {
                gravity = Gravity.CENTER
            },
            MATCH_PARENT, MATCH_PARENT
        )

        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(cover, MATCH_PARENT, MATCH_PARENT)
        book.cover?.let { url ->
            val cached = Thumbs.cached(url)
            if (cached != null) {
                cover.setImageBitmap(cached)
            } else {
                Thumbs.load(url, cfg.user, cfg.pass, w, h) { bmp ->
                    if (bmp != null) cover.setImageBitmap(bmp)
                }
            }
        }

        val title = label(book.title, if (compact) 11 else 14, Palette.ink, display = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(if (compact) 14 else 20), dp(if (compact) 10 else 14), dp(8), 0)
        }
        frame.addView(title, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        frame.addView(
            label("PDF", if (compact) 9 else 11, Palette.ink3).apply {
                setPadding(dp(if (compact) 14 else 20), 0, 0, dp(if (compact) 8 else 12))
            },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM)
        )

        frame.setOnFocusChangeListener { v, has ->
            // 焦点到这本就先下载，点开基本秒开
            if (has) BookCache.prefetch(this, book.url)
            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
                .setDuration(120).start()
            v.elevation = dp(if (has) 14 else 2).toFloat()
            v.background = shape(
                Palette.card, radius,
                dp(if (has) 4 else 2),
                if (has) Palette.ink else Palette.paper2
            )
        }
        frame.setOnClickListener {
            if (bookQuery.isNotBlank()) SearchHistory.add(this, bookQuery)
            startActivity(Intent(this, BookActivity::class.java).apply {
                putExtra("url", book.url)
                putExtra("title", book.title)
            })
        }
        return frame
    }

    // ---------------- 数据 ----------------

    /** 有缓存就直接渲染并返回 true；没有就返回 false（调用方决定要不要盖启动页） */
    private fun showCached(): Boolean {
        val cached = Library.loadCache(this, cfg)
        if (cached != null && cached.isNotEmpty()) {
            showingDemo = false
            renderData(cached)
            return true
        }
        showStatus("正在连接 NAS…")
        return false
    }

    private fun refresh() {
        startScanTicker()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Library.scan(this@MainActivity, cfg) { step ->
                        scanStep = step
                        runOnUiThread {
                            subtitle.text = scanText()
                            if (splash.visibility == View.VISIBLE) splashStep.text = scanText()
                        }
                    }
                }
            }
            result.onSuccess { list ->
                stopScanTicker()
                // 演示模式下 sections 不是空的，以前用 sections.isEmpty() 判断，
                // 失败时错误会被整个吞掉，界面永远停在「正在连接 NAS…」，家长一脸懵。
                hideSplash()
                val wasDemo = showingDemo
                showingDemo = false
                if (list.isEmpty()) {
                    if (sections.isEmpty() || wasDemo) {
                        showEmpty("NAS 里还没有视频", "把英语视频放进 ${cfg.rootPath} 就行")
                    }
                    return@onSuccess
                }
                Library.saveCache(this@MainActivity, cfg, list)
                renderData(list)
            }.onFailure { e ->
                stopScanTicker()
                hideSplash()
                // 以前出错只改了列表区，顶部还挂着「扫描中…」，看着就像卡死
                subtitle.text = "连不上 NAS"
                if (sections.isEmpty() || showingDemo) {
                    val why = e.message?.takeIf { it.isNotBlank() } ?: "连接超时"
                    showEmptyAction(
                        "连不上 NAS",
                        "$why\n${cfg.base}${cfg.rootPath}",
                        "重试"
                    ) { refresh() }
                }
            }
        }
    }

    private fun renderData(list: List<Section>) {
        sections = list

        seriesOf = list.flatMap { sec -> sec.videos.map { it.url to sec.title } }.toMap()
        coverUrlFor = list.mapNotNull { sec ->
            sec.videos.firstOrNull()?.let { sec.title to it.url }
        }.toMap()
        videoByUrl = list.flatMap { it.videos }.associateBy { it.url }
        val flat = list.flatMap { it.videos }
        subtitle.text = "${list.size} 个目录 · ${flat.size} 集"
        // 随机顺序按"本地日期"固定：同一天怎么刷新都一样，孩子能找到刚看的那集；
        // 跨天自动换一批。素材变了列表自然会变。
        renderWatch(flat.shuffled(dayRandom()).take(if (compact) 30 else 40))
        renderAll(list)
        paintTabs()
    }

    /** 以本地日期为种子的随机源 */
    private fun dayRandom(): kotlin.random.Random {
        val now = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(now)
        return kotlin.random.Random((now + offset) / 86_400_000L)
    }

    private fun renderDemo() {
        showingDemo = true
        runCatching {
            renderData(listOf(Demo.section(this)))
            subtitle.text = if (compact) "演示模式 · 右上角「家长」配置" else "演示模式 · 点右上角「家长」填 NAS 地址"
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

    private fun scanText(): String {
        val sec = if (scanStartAt == 0L) 0 else ((System.currentTimeMillis() - scanStartAt) / 1000).toInt()
        return if (sec < 3) "扫描中：$scanStep" else "扫描中：$scanStep · ${sec}s"
    }

    private fun startScanTicker() {
        stopScanTicker()
        scanStep = "连接 NAS"
        scanStartAt = System.currentTimeMillis()
        subtitle.text = scanText()
        val tick = object : Runnable {
            override fun run() {
                subtitle.text = scanText()
                if (splash.visibility == View.VISIBLE) splashStep.text = scanText()
                handler.postDelayed(this, 1000)
            }
        }
        scanTicker = tick
        handler.postDelayed(tick, 1000)
    }

    private fun stopScanTicker() {
        scanTicker?.let { handler.removeCallbacks(it) }
        scanTicker = null
    }

    /** 空状态 + 一个能聚焦的「重试」按钮（电视上点得到） */
    private fun showEmptyAction(title: String, detail: String, actionLabel: String, action: () -> Unit) {
        watchList.removeAllViews()
        allHost.removeAllViews()

        fun build(): LinearLayout {
            val box = centered(title, Palette.ink, detail) as LinearLayout
            val btn = label(actionLabel, if (compact) 13 else 16, Palette.paper, display = true).apply {
                setPadding(dp(26), dp(10), dp(26), dp(10))
                background = shape(Palette.coral, dp(24).toFloat())
                isFocusable = true
                isClickable = true
                noDefaultFocusRing()
                setOnClickListener { action() }
                setOnFocusChangeListener { v, has ->
                    v.background = shape(if (has) Palette.ink else Palette.coral, dp(24).toFloat())
                }
            }
            box.addView(btn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(18)
            })
            box.tag = btn
            return box
        }

        val a = build()
        val b = build()
        watchList.addView(a)
        allHost.addView(b)
        ((a.tag ?: b.tag) as? View)?.post { ((a.tag ?: b.tag) as? View)?.requestFocus() }
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
        intent.putStringArrayListExtra("series", ArrayList(list.map { seriesOf[it.url] ?: "" }))
        intent.putStringArrayListExtra("subs", ArrayList(list.map { it.sub ?: "" }))
        intent.putExtra("index", index)
        startActivity(intent)
    }

    /** 家长页不再要 PIN，按一下就进 */
    private fun askPin() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

}
