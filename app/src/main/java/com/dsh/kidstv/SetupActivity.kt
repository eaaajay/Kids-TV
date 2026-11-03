package com.dsh.kidstv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 家长配对页：电视上显示一个网址，手机浏览器打开就能配 NAS。
 */
class SetupActivity : Activity() {

    private var server: SetupServer? = null
    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var configView: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            server?.saved?.let { cfg ->
                statusView.text = "已保存 ✓  现在按返回键就能看到视频"
                statusView.setTextColor(Palette.leaf)
                configView.text = describe(cfg)
            }
            ui.postDelayed(this, 900)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        setContentView(buildUi())
        startServer()
        ui.post(poller)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
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

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(DottedPaperView(this), MATCH_PARENT, MATCH_PARENT)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(90), dp(56), dp(90), dp(40))
        }
        root.addView(box, MATCH_PARENT, MATCH_PARENT)

        box.addView(label("家长设置", 36, Palette.ink, display = true))
        box.addView(
            label("手机连上同一个 WiFi，用浏览器打开下面的地址，填 NAS 的 WebDAV 信息。", 16, Palette.ink2)
                .apply { setPadding(0, dp(10), 0, dp(26)) }
        )

        urlView = label("正在启动配对服务…", 34, Palette.ink, display = true).apply {
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = shape(Palette.card, dp(18).toFloat(), dp(2), Palette.paper2)
        }
        box.addView(urlView, WRAP_CONTENT, WRAP_CONTENT)

        statusView = label("等待手机上填写…", 16, Palette.ink2).apply {
            setPadding(0, dp(22), 0, dp(6))
        }
        box.addView(statusView)

        configView = label(describe(Prefs.load(this)), 14, Palette.ink3)
        box.addView(configView)

        val back = label("回到视频墙", 17, Palette.paper, display = true).apply {
            setPadding(dp(30), dp(14), dp(30), dp(14))
            background = shape(Palette.ink, dp(14).toFloat())
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener { finish() }
            setOnFocusChangeListener { v, has ->
                v.background = shape(if (has) Palette.coral else Palette.ink, dp(14).toFloat())
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.topMargin = dp(28)
        box.addView(back, lp)

        box.addView(
            label(
                "提示：视频文件放同一个文件夹；想要封面就放一张同名 .jpg；\n" +
                    "每个子文件夹会变成电视上的一行。",
                14, Palette.ink3
            ).apply { setPadding(0, dp(26), 0, 0) }
        )
        return root
    }

    private fun describe(cfg: NasConfig): String {
        if (!cfg.ready) return "当前：还没有配置 NAS"
        val p = if (cfg.rootPath == "/") "" else cfg.rootPath
        return "当前：${cfg.host}:${cfg.port}$p   ${if (cfg.user.isBlank()) "免密" else "账号 " + cfg.user}"
    }

    private fun startServer() {
        val s = SetupServer(this)
        if (!s.start()) {
            server = null
            urlView.text = "配对服务启动失败"
            statusView.text = "请重启这个应用再试"
            return
        }
        server = s
        val ip = localIpv4().firstOrNull()
        if (ip == null) {
            urlView.text = "电视还没有连上 WiFi"
            statusView.text = "请先在电视设置里连上家里 WiFi"
            return
        }
        urlView.text = "http://${ip}:${s.port}"
    }

    private fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
