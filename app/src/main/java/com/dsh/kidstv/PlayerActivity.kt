package com.dsh.kidstv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.Credentials
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var subsOn = true
    private val subtitleUrls = ArrayList<String>()
    private lateinit var playerView: PlayerView
    private lateinit var spinner: ProgressBar
    private lateinit var pauseIcon: FrameLayout

    /** 底部横幅：标题 + 缓存进度 + 下载速度 + 倍速，3 秒后自动收起 */
    private lateinit var banner: LinearLayout
    private lateinit var statsView: TextView
    private lateinit var bufferBar: ProgressBar
    private val speedChips = ArrayList<TextView>()

    private var urls = ArrayList<String>()
    private var startIndex = 0

    private val ui = Handler(Looper.getMainLooper())

    // ---- 倍速 ----
    private val speeds = floatArrayOf(0.75f, 1f, 1.25f)
    private val speedLabels = arrayOf("0.75", "1", "1.25")
    private var speedIndex = 1

    // ---- 缓存 / 速度 ----
    private val transferred = AtomicLong(0)
    private var lastBytes = 0L

    private var resumeAfterPause = false

    private val hideBanner = Runnable { fadeOutBanner() }
    private val ticker = object : Runnable {
        override fun run() {
            updateStats()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        urls = intent.getStringArrayListExtra("urls") ?: ArrayList()
        startIndex = intent.getIntExtra("index", 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        speedIndex = Prefs.speedIndex(this).coerceIn(0, speeds.size - 1)

        setContentView(buildUi())
        startPlayback()
        paintSpeedChips()
        showBanner()
        ui.post(ticker)
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

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
        }
        root.addView(playerView, MATCH_PARENT, MATCH_PARENT)
        styleSubtitles()

        spinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(0xCCFFF7ED.toInt())
            visibility = View.GONE
        }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        // 暂停图标：圆底两条竖杠，不依赖字体
        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bars.addView(View(this).apply { setBackgroundColor(0xFFFFF7ED.toInt()) }, dp(7), dp(34))
        bars.addView(
            View(this).apply { setBackgroundColor(0xFFFFF7ED.toInt()) },
            LinearLayout.LayoutParams(dp(7), dp(34)).apply { leftMargin = dp(7) }
        )
        pauseIcon = FrameLayout(this).apply {
            visibility = View.GONE
            background = shape(0x99000000.toInt(), dp(999).toFloat())
        }
        pauseIcon.addView(bars, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(pauseIcon, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))

        root.addView(
            buildBanner(),
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(28)
                rightMargin = dp(28)
                bottomMargin = dp(22)
            }
        )

        // 手机/触摸屏：点一下就是播放暂停，同时把横幅叫回来
        root.isClickable = true
        root.setOnClickListener { togglePlay() }
        return root
    }

    private fun buildBanner(): View {
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = shape(0xD9101316.toInt(), dp(16).toFloat(), dp(1), 0x33FFF7ED)
        }

        // 一行放完：左边缓存进度和下载速度，右边倍速
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statsView = label("", 13, 0xCCFFF7ED.toInt())
        row.addView(statsView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        speeds.indices.forEach { i ->
            val chip = label(speedLabels[i], 13, 0xCCFFF7ED.toInt(), display = true).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                isClickable = true
                setOnClickListener {
                    speedIndex = i
                    applySpeed()
                }
            }
            speedChips += chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) }
            )
        }
        banner.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        bufferBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Palette.coral)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        }
        banner.addView(
            bufferBar,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { topMargin = dp(8) }
        )
        return banner
    }

    // ---------------- 横幅显隐 ----------------

    private fun showBanner() {
        banner.animate().cancel()
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(150).start()
        ui.removeCallbacks(hideBanner)
        // 暂停时别自动消失，否则屏幕上什么信息都没有
        if (player?.playWhenReady != false) ui.postDelayed(hideBanner, 3000)
    }

    private fun fadeOutBanner() {
        if (player?.playWhenReady == false) return
        banner.animate().alpha(0f).setDuration(280)
            .withEndAction { if (banner.alpha < 0.05f) banner.visibility = View.GONE }
            .start()
    }

    private fun startPlayback() {
        if (urls.isEmpty()) {
            toast("没有可播放的视频")
            finish()
            return
        }
        val cfg = Prefs.load(this)
        val headers = HashMap<String, String>()
        if (cfg.user.isNotBlank()) {
            headers["Authorization"] = Credentials.basic(cfg.user, cfg.pass, Charsets.UTF_8)
        }
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setTransferListener(object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onBytesTransferred(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                    byteCount: Int
                ) {
                    if (isNetwork) transferred.addAndGet(byteCount.toLong())
                }
            })

        // DefaultDataSource 让 file:// 演示片和 http(s):// NAS 视频走同一套播放器
        val base = DefaultDataSource.Factory(this, dataSource)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(base))
            .build()

        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_ENDED) {
                    if (exo.hasNextMediaItem()) {
                        exo.seekToNextMediaItem()
                        exo.play()
                        showBanner()
                    } else {
                        finish()
                    }
                }
                updateStats()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                showBanner()
                updateStats()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePauseIcon()
                showBanner()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePauseIcon()
            }

            override fun onPlayerError(error: PlaybackException) {
                spinner.visibility = View.GONE
                toast("播放失败：${error.errorCodeName}，可能格式不支持")
            }
        })

        val subs = intent.getStringArrayListExtra("subs")
        subtitleUrls.clear()
        urls.forEachIndexed { i, _ -> subtitleUrls.add(subs?.getOrNull(i) ?: "") }

        val items = urls.mapIndexed { i, u ->
            val cfg = subtitleFor(subtitleUrls[i])
            if (cfg == null) {
                MediaItem.fromUri(u)
            } else {
                MediaItem.Builder()
                    .setUri(u)
                    .setSubtitleConfigurations(listOf(cfg))
                    .build()
            }
        }
        exo.setMediaItems(items, startIndex, 0L)
        exo.playbackParameters = PlaybackParameters(speeds[speedIndex], 1f)
        // 有字幕就默认选上英文轨；没字幕时这段没有副作用
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
            .build()
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        playerView.player = exo
    }

    // ---------------- 播放 / 暂停 ----------------

    private fun togglePlay() {
        val p = player ?: return
        if (p.playWhenReady) p.pause() else p.play()
        updatePauseIcon()
        showBanner()
    }

    private fun updatePauseIcon() {
        val paused = player?.playWhenReady == false
        pauseIcon.visibility = if (paused) View.VISIBLE else View.GONE
        if (paused) ui.removeCallbacks(hideBanner)
    }

    // ---------------- 倍速 ----------------

    private fun changeSpeed(delta: Int) {
        speedIndex = ((speedIndex + delta) % speeds.size + speeds.size) % speeds.size
        applySpeed()
    }

    private fun applySpeed() {
        player?.playbackParameters = PlaybackParameters(speeds[speedIndex], 1f)
        Prefs.setSpeedIndex(this, speedIndex)
        paintSpeedChips()
        updateStats()
        showBanner()
    }

    private fun paintSpeedChips() {
        speedChips.forEachIndexed { i, chip ->
            val on = i == speedIndex
            chip.setTextColor(if (on) Palette.ink else 0xCCFFF7ED.toInt())
            chip.background = shape(if (on) Palette.paper else 0x22FFFFFF, dp(999).toFloat())
        }
    }

    // ---------------- 缓存 / 速度 ----------------

    private fun updateStats() {
        val p = player ?: return
        val bytes = transferred.get()
        val perSec = (bytes - lastBytes).coerceAtLeast(0L)
        lastBytes = bytes
        val rate = human(perSec) + "/s"

        val dur = p.duration
        val buf = p.bufferedPosition
        if (dur > 0 && dur != C.TIME_UNSET) {
            val pct = (buf.coerceAtMost(dur) * 100 / dur).toInt().coerceIn(0, 100)
            bufferBar.progress = pct
            statsView.text = "缓存 $pct% · $rate"
        } else {
            // 时长还没拿到（刚起播）就报已缓存多少
            bufferBar.progress = 0
            statsView.text = "已缓存 ${human(bytes)} · $rate"
        }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 同名外挂字幕 → ExoPlayer 的 SubtitleConfiguration */
    private fun subtitleFor(url: String?): MediaItem.SubtitleConfiguration? {
        if (url.isNullOrBlank()) return null
        val mime = when {
            url.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
            url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            url.endsWith(".ass", true) || url.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
            else -> return null
        }
        return runCatching {
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(mime)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }.getOrNull()
    }

    private fun styleSubtitles() {
        playerView.subtitleView?.apply {
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    null
                )
            )
            setFractionalTextSize(0.055f)
            setBottomPaddingFraction(0.08f)
            setApplyEmbeddedStyles(true)
        }
    }

    private fun toggleSubs() {
        subsOn = !subsOn
        player?.let { exo ->
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
                .build()
        }
        toast(if (subsOn) "字幕：开" else "字幕：关")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    changeSpeed(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    changeSpeed(1)
                    return true
                }
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_CAPTIONS -> {
                    toggleSubs()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlay()
                    return true
                }
            }
            showBanner()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        resumeAfterPause = player?.playWhenReady == true
        player?.pause()
        showBanner()
    }

    override fun onResume() {
        super.onResume()
        if (resumeAfterPause) player?.play()
        resumeAfterPause = false
        showBanner()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
