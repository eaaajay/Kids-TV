package com.dsh.kidstv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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

    /** 窄屏（手机横屏/竖屏）用紧凑排版；电视仍是 10 尺规格 */
    private val compact: Boolean by lazy { resources.configuration.screenWidthDp < 600 }

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

        // 这一页原本是按电视写的固定大边距，手机横屏时内容比屏幕高，
        // 底部按钮直接被挤到屏幕外，家长根本点不到 —— 所以整页必须可滚动。
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(scroller, MATCH_PARENT, MATCH_PARENT)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(if (compact) 26 else 90), dp(if (compact) 18 else 56),
                dp(if (compact) 26 else 90), dp(if (compact) 18 else 40)
            )
        }
        scroller.addView(box, MATCH_PARENT, WRAP_CONTENT)

        box.addView(label("家长设置", if (compact) 24 else 36, Palette.ink, display = true))
        box.addView(
            label("手机连上同一个 WiFi 时，用浏览器打开下面的地址；\n也可以点下面的「在这台设备上直接填写」，直接粘 NAS 地址。", if (compact) 13 else 16, Palette.ink2)
                .apply { setPadding(0, dp(if (compact) 6 else 10), 0, dp(if (compact) 14 else 26)) }
        )
        val manual = label("在这台设备上直接填写", if (compact) 14 else 17, Palette.ink, display = true).apply {
            setPadding(
                dp(if (compact) 20 else 30), dp(if (compact) 10 else 14),
                dp(if (compact) 20 else 30), dp(if (compact) 10 else 14)
            )
            background = shape(Palette.card, dp(14).toFloat(), dp(2), Palette.paper2)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { showManualDialog() }
            setOnFocusChangeListener { v, has ->
                v.background = shape(
                    if (has) Palette.ink else Palette.card,
                    dp(14).toFloat(), dp(2), if (has) Palette.ink else Palette.paper2
                )
                (v as TextView).setTextColor(if (has) Palette.paper else Palette.ink)
            }
        }
        val lpm = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lpm.topMargin = dp(if (compact) 16 else 28)
        box.addView(manual, lpm)


        urlView = label("正在启动配对服务…", if (compact) 20 else 34, Palette.ink, display = true).apply {
            setPadding(
                dp(if (compact) 16 else 28), dp(if (compact) 10 else 20),
                dp(if (compact) 16 else 28), dp(if (compact) 10 else 20)
            )
            background = shape(Palette.card, dp(if (compact) 12 else 18).toFloat(), dp(2), Palette.paper2)
        }
        box.addView(urlView, WRAP_CONTENT, WRAP_CONTENT)

        statusView = label("等待手机上填写…", if (compact) 13 else 16, Palette.ink2).apply {
            setPadding(0, dp(if (compact) 12 else 22), 0, dp(6))
        }
        box.addView(statusView)

        configView = label(describe(Prefs.load(this)), if (compact) 12 else 14, Palette.ink3)
        box.addView(configView)


        val back = label("回到视频墙", if (compact) 14 else 17, Palette.paper, display = true).apply {
            setPadding(
                dp(if (compact) 20 else 30), dp(if (compact) 10 else 14),
                dp(if (compact) 20 else 30), dp(if (compact) 10 else 14)
            )
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
        lp.topMargin = dp(if (compact) 12 else 28)
        box.addView(back, lp)

        box.addView(
            label(
                "提示：视频文件放同一个文件夹；想要封面就放一张同名 .jpg；\n" +
                    "每个子文件夹会变成电视上的一行。",
                if (compact) 12 else 14, Palette.ink3
            ).apply { setPadding(0, dp(if (compact) 14 else 26), 0, 0) }
        )
        return root
    }

    /**
     * 在 App 里直接填。电视上可以用遥控器慢慢敲，手机上是长按粘贴 ——
     * 走系统输入法，比"在浏览器里打开一个网址再填"稳得多。
     */
    private fun showManualDialog() {
        val cfg = Prefs.load(this)
        val pad = dp(22)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(4), pad, 0)
        }

        fun field(title: String, value: String, hintText: String, password: Boolean = false): EditText {
            form.addView(label(title, 13, Palette.ink2).apply { setPadding(0, dp(10), 0, dp(4)) })
            val e = EditText(this).apply {
                setText(value)
                hint = hintText
                isSingleLine = true
                setTextColor(Palette.ink)
                setHintTextColor(Palette.ink3)
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
            }
            form.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return e
        }

        val addr = field(
            "NAS 地址（可以整段粘贴）",
            if (cfg.host.isBlank()) "" else cfg.base + cfg.rootPath,
            "https://dav.example.com:443 或 192.168.1.10"
        )
        val user = field("账号", cfg.user, "没有就留空")
        val pass = field("密码", cfg.pass, "没有就留空", password = true)

        val dialog = AlertDialog.Builder(this)
            .setTitle("直接填写 NAS")
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                val text = addr.text.toString().trim()
                if (text.isBlank()) {
                    toast("地址不能为空")
                    return@setPositiveButton
                }
                val now = NasInput.apply(
                    cfg,
                    text,
                    user.text.toString().trim(),
                    pass.text.toString().trim()
                )
                Prefs.save(this, now)
                statusView.text = "已保存 ✓  按返回键就能看到视频"
                statusView.setTextColor(Palette.leaf)
                configView.text = describe(now)
            }
            .setNegativeButton("取消", null)
            .create()
        // 手机上直接弹键盘，省得家长再点一次输入框
        dialog.setOnShowListener {
            addr.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun describe(cfg: NasConfig): String {
        if (!cfg.ready) return "当前：还没有配置 NAS"
        val p = if (cfg.rootPath == "/") "" else cfg.rootPath
        return "当前：${cfg.scheme}://${cfg.host}:${cfg.port}$p   ${if (cfg.user.isBlank()) "免密" else "账号 " + cfg.user}"
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
            // 手机只开着流量（没连 WiFi）时没有局域网地址，但本机浏览器照样能打开配对页。
            // 以前这里直接说「没连上 WiFi」就没下文了，家长在手机上根本进不去。
            urlView.text = "http://127.0.0.1:${s.port}"
            statusView.text = "当前没有局域网地址（没连 WiFi）\n请用这台设备自己的浏览器打开上面的地址"
            return
        }
        urlView.text = "http://${ip}:${s.port}"
    }

    /** 手机的 4G/5G 接口（ccmni / rmnet 等）不能拿来当配对地址 */
    private fun isCellular(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("clat") || n.startsWith("dummy")
    }

    /** wlan / eth 优先，热点和虚拟网卡靠后 */
    private fun ifaceRank(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("wlan") -> 0
            n.startsWith("eth") -> 1
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("p2p") -> 3
            else -> 2
        }
    }

    private fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            // 以前只取 isSiteLocalAddress()（10./172.16/192.168），
            // 家里 WiFi 如果是公网段（校园网、部分宽带给的 100.x/160.x）会直接报「没连上 WiFi」；
            // 而且手机开着流量时，第一个地址往往是 5G 的 10.x，配对网址就成了废的。
            .filterNot { isCellular(it.name) }
            .sortedBy { ifaceRank(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
