package com.dsh.kidstv

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 内嵌在主页的「家长」页。
 *
 * 只有这个 tab 可见时才启动配对 HTTP 服务；切走或退到后台就关闭。
 * 配置保存后直接通知 MainActivity 重扫，不再靠返回另一个 Activity 触发刷新。
 */
class ParentPanel(
    private val ctx: Context,
    private val onConfigSaved: (NasConfig) -> Unit
) : ScrollView(ctx) {

    private var server: SetupServer? = null
    private var active = false
    private var delivered: NasConfig? = null
    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var configView: TextView
    private lateinit var crashView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val compact: Boolean get() = resources.configuration.screenWidthDp < 600

    private val poller = object : Runnable {
        override fun run() {
            server?.saved?.let { saved ->
                if (saved != delivered) {
                    delivered = saved
                    showSaved(saved)
                    onConfigSaved(saved)
                }
            }
            if (active) ui.postDelayed(this, 900)
        }
    }

    init {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(buildContent(), LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun buildContent(): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 6 else 24), dp(if (compact) 8 else 18),
                dp(if (compact) 6 else 24), dp(if (compact) 20 else 36))
        }

        box.addView(ctx.label("家长设置", if (compact) 22 else 30, Palette.ink, display = true))
        box.addView(
            ctx.label(
                "手机连上同一个 WiFi，用浏览器打开下面的地址就能配置 NAS。\n" +
                    "也可以用遥控器选「在这台设备上直接填写」。保存后视频会自动刷新。",
                if (compact) 13 else 16, Palette.ink2
            ).apply { setPadding(0, dp(8), 0, dp(if (compact) 12 else 20)) }
        )

        val manual = ctx.label("在这台设备上直接填写", if (compact) 14 else 17, Palette.ink, display = true).apply {
            setPadding(dp(24), dp(11), dp(24), dp(11))
            background = shape(Palette.card, dp(14).toFloat(), dp(2), Palette.paper2)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            noDefaultFocusRing()
            setOnClickListener { showManualDialog() }
            setOnFocusChangeListener { v, has ->
                v.background = shape(
                    if (has) Palette.coral else Palette.card,
                    dp(14).toFloat(), dp(2), if (has) Palette.coral else Palette.paper2
                )
                (v as TextView).setTextColor(if (has) Palette.onAccent else Palette.ink)
            }
        }
        box.addView(manual, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            bottomMargin = dp(if (compact) 14 else 22)
        })

        urlView = ctx.label("切换到家长页时启动配对服务…", if (compact) 18 else 28, Palette.ink, display = true).apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = shape(Palette.card, dp(16).toFloat(), dp(2), Palette.paper2)
            setTextIsSelectable(true)
        }
        box.addView(urlView, WRAP_CONTENT, WRAP_CONTENT)

        statusView = ctx.label("等待手机上填写…", if (compact) 13 else 16, Palette.ink2).apply {
            setPadding(0, dp(16), 0, dp(8))
        }
        box.addView(statusView)

        configView = ctx.label(describe(Prefs.load(ctx)), if (compact) 12 else 14, Palette.ink3)
        box.addView(configView)

        crashView = ctx.label("", if (compact) 11 else 13, Palette.coral).apply {
            setPadding(0, dp(16), 0, 0)
            visibility = View.GONE
        }
        box.addView(crashView)

        box.addView(
            ctx.label(
                "视频目录与绘本目录分别扫描；视频旁放同名 .jpg 可作封面。\n" +
                    "配好后切回上面的「随心看」或「视频目录」即可。",
                if (compact) 12 else 14, Palette.ink3
            ).apply { setPadding(0, dp(18), 0, 0) }
        )
        return box
    }

    /** tab 切进来：启动配对服务和状态轮询 */
    fun onShow() {
        if (active) return
        active = true
        delivered = null
        configView.text = describe(Prefs.load(ctx))
        refreshCrash()
        startServer()
        ui.post(poller)
    }

    /** tab 切走：关掉配对服务，不让配置页一直暴露在局域网里 */
    fun onHide() {
        active = false
        ui.removeCallbacks(poller)
        server?.stop()
        server = null
    }

    /** 上次崩溃信息（没有就藏起来） */
    private fun refreshCrash() {
        val info = CrashLog.last(ctx)
        if (info.isNullOrBlank()) {
            crashView.visibility = View.GONE
        } else {
            crashView.visibility = View.VISIBLE
            crashView.text = "上次闪退：\n$info"
        }
    }

    private fun showSaved(cfg: NasConfig) {
        statusView.text = "已保存 ✓  视频正在自动刷新"
        statusView.setTextColor(Palette.leaf)
        configView.text = describe(cfg)
    }

    private fun showManualDialog() {
        val cfg = Prefs.load(ctx)
        val pad = dp(22)
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(4), pad, 0)
        }

        fun field(title: String, value: String, hintText: String, password: Boolean = false): EditText {
            form.addView(ctx.label(title, 13, Palette.ink2).apply { setPadding(0, dp(10), 0, dp(4)) })
            val e = EditText(ctx).apply {
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

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("直接填写 NAS")
            .setView(form)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            // 不用默认 PositiveButton 的自动 dismiss：地址为空时保留表单让家长改。
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = addr.text.toString().trim()
                if (text.isBlank()) {
                    addr.error = "地址不能为空"
                    return@setOnClickListener
                }
                val now = NasInput.apply(
                    cfg, text, user.text.toString().trim(), pass.text.toString().trim()
                )
                Prefs.save(ctx, now)
                delivered = now
                showSaved(now)
                onConfigSaved(now)
                dialog.dismiss()
            }
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
        val s = SetupServer(ctx)
        if (!s.start()) {
            server = null
            urlView.text = "配对服务启动失败"
            statusView.text = "请切走再切回家长页，或重启应用再试"
            return
        }
        server = s
        val ip = localIpv4().firstOrNull()
        if (ip == null) {
            urlView.text = "http://127.0.0.1:${s.port}"
            statusView.text = "当前没有局域网地址（没连 WiFi）\n请用这台设备自己的浏览器打开上面的地址"
            return
        }
        urlView.text = "http://$ip:${s.port}"
        statusView.text = "等待手机上填写…"
        statusView.setTextColor(Palette.ink2)
    }

    private fun isCellular(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("clat") || n.startsWith("dummy")
    }

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
            .filterNot { isCellular(it.name) }
            .sortedBy { ifaceRank(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun dp(v: Number): Int = ctx.dp(v)

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
