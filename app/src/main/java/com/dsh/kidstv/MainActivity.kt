package com.dsh.kidstv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var watchCoverBox: FrameLayout
    private lateinit var watchCover: ImageView
    private lateinit var watchCoverLetter: TextView
    private lateinit var watchSeries: TextView
    private lateinit var watchEpisode: TextView
    private lateinit var subtitle: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sections: List<Section> = emptyList()
    private var books: List<Book> = emptyList()
    private var booksLoaded = false
    private var shuffled: List<Video> = emptyList()
    private var tab = 0

    /** 全部目录里点开某个系列后的状态；null 表示还在系列列表 */
    private var expanded: Section? = null
    /** 每集属于哪个系列（随心看只拿得到扁平列表） */
    private var seriesOf: Map<String, String> = emptyMap()
    /** 从哪个系列点进去的，返回时把焦点放回去 */
    private var expandedFrom: Int = 0
    private var pendingSeriesFocus: Int = -1

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
        val now = Prefs.load(this)
        if (now.ready && now.cacheKey != cfg.cacheKey) {
            cfg = now
            booksLoaded = false
            books = emptyList()
            showCached()
            refresh()
        }
    }

    override fun onDestroy() {
        scope.cancel()
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
        return root
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(if (compact) 8 else 14))
        }

        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("夏夏看电视", if (compact) 16 else 21, Palette.ink, display = true))
        subtitle = label("", if (compact) 10 else 12, Palette.ink3).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle.setPadding(0, dp(2), 0, 0)
        brand.addView(subtitle)
        row.addView(brand, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabWatch = tabPill("随心看", 0)
        tabAll = tabPill("全部目录", 1)
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
        val tv = label(text, if (compact) 12 else 17, Palette.ink2, display = true).apply {
            setPadding(
                dp(if (compact) 11 else 20), dp(if (compact) 6 else 9),
                dp(if (compact) 11 else 20), dp(if (compact) 6 else 9)
            )
            background = shape(Color.TRANSPARENT, dp(24).toFloat())
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { switchTab(index) }
            // 焦点一到 tab 就切页，不用再按 OK
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && tab != index) switchTab(index) else paintTabs()
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
        if (index == 2) loadBooks()
    }

    private fun focusFirst(host: ViewGroup) {
        host.post { firstFocusable(host)?.requestFocus() }
    }

    /** 把焦点给到第 n 个可聚焦控件（系列卡片/剧集卡片按顺序数） */
    private fun focusNth(host: ViewGroup, n: Int) {
        host.post {
            val all = ArrayList<View>()
            fun walk(g: ViewGroup) {
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c.isFocusable) all.add(c) else if (c is ViewGroup) walk(c)
                }
            }
            walk(host)
            (all.getOrNull(n) ?: all.firstOrNull())?.requestFocus()
        }
    }

    override fun onBackPressed() {
        if (expanded != null) {
            collapse()
            return
        }
        super.onBackPressed()
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

        // 右侧：左边大封面，右边上面剧名、下面集名
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 4 else 20), 0, 0, 0)
        }

        watchCoverBox = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val w = MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(w, (w * 9) / 16)
            }
        }.apply {
            background = shape(Palette.ink, dp(if (compact) 12 else 22).toFloat())
            clipToOutline = true
        }
        watchCoverLetter = label("", if (compact) 44 else 78, 0x44FFFFFF, display = true).apply {
            gravity = Gravity.CENTER
        }
        watchCoverBox.addView(watchCoverLetter, MATCH_PARENT, MATCH_PARENT)
        watchCover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        watchCoverBox.addView(watchCover, MATCH_PARENT, MATCH_PARENT)

        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 14 else 30), dp(if (compact) 2 else 6), 0, dp(if (compact) 2 else 6))
        }
        watchSeries = label("", if (compact) 16 else 32, Palette.ink, display = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        names.addView(watchSeries)
        names.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        watchEpisode = label("", if (compact) 13 else 22, Palette.ink2).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        names.addView(watchEpisode)
        names.addView(
            label("按 OK 全屏播放", if (compact) 10 else 13, Palette.ink3).apply {
                setPadding(0, dp(if (compact) 6 else 10), 0, 0)
            }
        )

        detail.addView(watchCoverBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.15f))
        detail.addView(names, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))

        if (compact) {
            // 竖屏：详情在上、列表在下
            row.addView(detail, MATCH_PARENT, WRAP_CONTENT)
            row.addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            // 电视：左列表、右详情
            row.addView(scroller, LinearLayout.LayoutParams(dp(330), MATCH_PARENT))
            row.addView(detail, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
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
            list.firstOrNull()?.let { showDetail(it) }
        }
    }

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
            if (has) showDetail(v)
        }
        row.setOnClickListener { playList(shuffled, index) }
        return row
    }

    private fun showDetail(v: Video) {
        val series = seriesOf[v.url].orEmpty()
        watchSeries.text = series.ifBlank { "随心看" }
        watchEpisode.text = v.title
        watchCoverLetter.text = (series.ifBlank { v.title }).take(1)
        watchCoverBox.background = shape(
            Palette.cover(series.hashCode()), dp(if (compact) 12 else 22).toFloat()
        )

        val url = v.thumb
        watchCover.tag = url
        watchCover.setImageDrawable(null)
        if (url == null) return
        val cached = Thumbs.cached(url)
        if (cached != null) {
            watchCover.setImageBitmap(cached)
        } else {
            Thumbs.load(url, cfg.user, cfg.pass, dp(420), dp(236)) { bmp ->
                if (bmp != null && watchCover.tag == url) watchCover.setImageBitmap(bmp)
            }
        }
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
        val open = expanded

        if (open == null) {
            if (list.isEmpty()) {
                allHost.addView(centered("NAS 上还没找到视频", Palette.ink2, "去「家长」里检查目录"))
                return
            }
            val perRow = if (compact) 2 else 4
            list.chunked(perRow).forEachIndexed { rowIndex, rowSections ->
                val rowView = row(if (compact) 8 else 16)
                rowSections.forEachIndexed { i, sec -> rowView.addView(seriesCard(sec, rowIndex * perRow + i)) }
                allHost.addView(rowView)
            }
        } else {
            allHost.addView(expandHeader(open))
            val perRow = if (compact) 2 else 4
            open.videos.chunked(perRow).forEachIndexed { rowIndex, rowVideos ->
                val rowView = row(if (compact) 8 else 16)
                rowVideos.forEachIndexed { i, v -> rowView.addView(card(v, rowIndex * perRow + i, open)) }
                allHost.addView(rowView)
            }
        }

        if (tab == 1) {
            val target = if (open == null) {
                val n = pendingSeriesFocus
                pendingSeriesFocus = -1
                n.coerceAtLeast(0)
            } else 1
            focusNth(allHost, target)
        }
    }

    private fun row(gap: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        setPadding(0, 0, 0, dp(gap))
    }

    private fun expand(section: Section, fromIndex: Int) {
        expanded = section
        expandedFrom = fromIndex
        renderAll(sections)
    }

    private fun collapse() {
        expanded = null
        pendingSeriesFocus = expandedFrom
        renderAll(sections)
    }

    private fun expandHeader(section: Section): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(if (compact) 6 else 10), dp(4), dp(if (compact) 10 else 16))
        }
        val back = label("← 全部剧集", if (compact) 13 else 17, Palette.ink, display = true).apply {
            setPadding(
                dp(if (compact) 14 else 20), dp(if (compact) 8 else 11),
                dp(if (compact) 14 else 20), dp(if (compact) 8 else 11)
            )
            background = shape(Palette.card, dp(999).toFloat(), dp(2), Palette.paper2)
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { collapse() }
            setOnFocusChangeListener { v, has ->
                (v as TextView).setTextColor(if (has) Palette.paper else Palette.ink)
                v.background = shape(
                    if (has) Palette.ink else Palette.card, dp(999).toFloat(), dp(2),
                    if (has) Palette.ink else Palette.paper2
                )
            }
        }
        line.addView(back)
        line.addView(
            label("${section.title}  ·  ${section.videos.size} 集", if (compact) 13 else 19, Palette.ink2).apply {
                setPadding(dp(if (compact) 12 else 18), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        return line
    }

    private fun seriesCard(section: Section, index: Int): View =
        tile(
            title = section.title,
            badge = "${section.videos.size} 集",
            coverUrl = section.videos.firstNotNullOfOrNull { it.thumb },
            initial = section.title.take(1),
            index = index,
            onClick = { expand(section, index) }
        )

    private fun card(video: Video, index: Int, section: Section): View =
        tile(
            title = video.title,
            badge = null,
            coverUrl = video.thumb,
            initial = video.title.take(1),
            index = index,
            onClick = { playList(section.videos, index) }
        )

    private fun tile(
        title: String,
        badge: String?,
        coverUrl: String?,
        initial: String,
        index: Int,
        onClick: () -> Unit
    ): View {
        val w = dp(if (compact) 208 else 318)
        val h = dp(if (compact) 117 else 179)
        val radius = dp(if (compact) 14 else 20).toFloat()

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                rightMargin = dp(if (compact) 10 else 18)
            }
            isFocusable = true
            isClickable = true
            noDefaultFocusRing()
            clipToOutline = true
            elevation = dp(2).toFloat()
            background = shape(Palette.card, radius, dp(2), Palette.paper2)
        }

        frame.addView(
            View(this).apply { setBackgroundColor(Palette.cover(index)) },
            MATCH_PARENT, MATCH_PARENT
        )
        frame.addView(
            label(initial, if (compact) 40 else 64, 0x55FFFFFF, display = true).apply {
                gravity = Gravity.CENTER
            },
            MATCH_PARENT, MATCH_PARENT
        )

        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)

        val caption = label(title, if (compact) 12 else 16, Palette.ink, display = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(if (badge == null) 14 else 56), 0)
            background = shape(
                0xF7FFFBF4.toInt(), 0f,
                radii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
            )
        }
        frame.addView(
            caption,
            FrameLayout.LayoutParams(MATCH_PARENT, dp(if (compact) 34 else 44), Gravity.BOTTOM)
        )
        if (badge != null) {
            frame.addView(
                label(badge, if (compact) 10 else 13, Palette.ink3).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setPadding(0, 0, dp(14), 0)
                },
                FrameLayout.LayoutParams(WRAP_CONTENT, dp(if (compact) 34 else 44), Gravity.BOTTOM or Gravity.END)
            )
        }

        if (coverUrl != null) {
            val cached = Thumbs.cached(coverUrl)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                Thumbs.load(coverUrl, cfg.user, cfg.pass, w, h) { bmp ->
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
        frame.setOnClickListener { onClick() }
        return frame
    }

    // ---------------- Tab 3：绘本 ----------------

    private fun buildBooksPage(): View {
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
        return scroller
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

    private fun renderBooks(list: List<Book>) {
        booksHost.removeAllViews()
        if (list.isEmpty()) {
            booksHost.addView(centered("还没有绘本", Palette.ink2, "往「${cfg.booksRoot}」里放 PDF 就会出现在这里"))
            return
        }
        val perRow = if (compact) 2 else 4
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
        val w = dp(if (compact) 150 else 200)
        val h = dp(if (compact) 196 else 262)
        val radius = dp(if (compact) 12 else 16).toFloat()

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                rightMargin = dp(if (compact) 10 else 18)
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
            startActivity(Intent(this, BookActivity::class.java).apply {
                putExtra("url", book.url)
                putExtra("title", book.title)
            })
        }
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
        expanded = null
        seriesOf = list.flatMap { sec -> sec.videos.map { it.url to sec.title } }.toMap()
        val flat = list.flatMap { it.videos }
        subtitle.text = "${list.size} 个目录 · ${flat.size} 集"
        renderWatch(flat.shuffled().take(if (compact) 30 else 40))
        renderAll(list)
        paintTabs()
    }

    private fun renderDemo() {
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

    /** 家长页不再要 PIN，按一下就进 */
    private fun askPin() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

}
