package com.dsh.kidstv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.Credentials

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var spinner: ProgressBar
    private var urls = ArrayList<String>()
    private var titles = ArrayList<String>()
    private var startIndex = 0
    private var backAt = 0L
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        urls = intent.getStringArrayListExtra("urls") ?: ArrayList()
        titles = intent.getStringArrayListExtra("titles") ?: ArrayList()
        startIndex = intent.getIntExtra("index", 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))

        setContentView(buildUi())
        startPlayback()
        if (titles.isNotEmpty()) titleView.text = titles.getOrElse(startIndex) { "" }
        fadeTitleLater()
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

        spinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(0xCCFFF7ED.toInt())
            visibility = View.GONE
        }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        titleView = label("", 24, 0xFFFFF7ED.toInt(), display = true).apply {
            setShadowLayer(12f, 0f, 2f, 0xCC000000.toInt())
            setPadding(dp(52), dp(36), dp(52), 0)
        }
        root.addView(
            titleView,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP or Gravity.START)
        )

        val hint = label("长按返回键 2 秒退出", 14, 0x88FFF7ED.toInt()).apply {
            setPadding(dp(40), 0, dp(48), dp(34))
        }
        root.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.BOTTOM or Gravity.END))
        return root
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

        // DefaultDataSource 让 file:// 演示片和 http:// NAS 视频走同一套播放器
        val base = DefaultDataSource.Factory(this, dataSource)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(base))
            .build()

        exo.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
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
                        showTitle()
                    } else {
                        finish()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = exo.currentMediaItemIndex
                titleView.text = titles.getOrElse(idx) { "" }
                showTitle()
            }

            override fun onPlayerError(error: PlaybackException) {
                spinner.visibility = View.GONE
                toast("播放失败：${error.errorCodeName}，可能格式不支持")
            }
        })

        val items = urls.map { u -> MediaItem.fromUri(u) }
        exo.setMediaItems(items, startIndex, 0L)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        playerView.player = exo
    }

    private fun showTitle() {
        titleView.animate().alpha(1f).setDuration(180).start()
        fadeTitleLater()
    }

    private fun fadeTitleLater() {
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ titleView.animate().alpha(0f).setDuration(600).start() }, 4000)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            showTitle()
        }
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

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
