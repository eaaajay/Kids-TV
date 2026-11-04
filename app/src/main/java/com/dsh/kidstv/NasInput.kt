package com.dsh.kidstv

/**
 * 把家长输入/粘贴的一段地址拆成 协议 / 主机 / 端口 / 路径。
 *
 * 为什么要单独拆：FN Connect 之类的中继给的是 `https://dav.example.com:443` 这种整段地址，
 * 直接当主机名用会拼出 `http://dav.example.com:443:5005` 这种废地址。
 * 这件事网页表单和 App 内输入框都要做，所以放在这里共用。
 */
object NasInput {

    data class Addr(
        val scheme: String,
        val host: String,
        val port: Int,
        /** 地址里带的路径，没有就是空串 */
        val path: String,
        /** 用户是不是粘了一段带 http(s):// 前缀的完整地址 */
        val pastedUrl: Boolean
    )

    fun parse(rawInput: String, fallbackScheme: String = "http", fallbackPort: Int? = null): Addr {
        val raw = rawInput.trim()
        var scheme = if (fallbackScheme.trim().equals("https", true)) "https" else "http"
        var rest = raw
        var pasted = false
        val lower = raw.lowercase()
        if (lower.startsWith("https://")) {
            scheme = "https"
            rest = raw.substring(8)
            pasted = true
        } else if (lower.startsWith("http://")) {
            scheme = "http"
            rest = raw.substring(7)
            pasted = true
        }

        val path = rest.substringAfter('/', "").trim()
        val hostPort = rest.substringBefore('/')
        var host = hostPort
        var urlPort: Int? = null

        if (hostPort.startsWith("[")) {
            // [IPv6]:port —— 方括号要留着，拼 URL 时靠它
            val end = hostPort.indexOf(']')
            if (end > 0) {
                host = hostPort.substring(0, end + 1)
                val tail = hostPort.substring(end + 1)
                if (tail.startsWith(":")) urlPort = tail.substring(1).toIntOrNull()
            }
        } else if (hostPort.count { it == ':' } == 1) {
            val idx = hostPort.lastIndexOf(':')
            urlPort = hostPort.substring(idx + 1).toIntOrNull()
            if (urlPort != null) host = hostPort.substring(0, idx)
        }

        val defaultPort = if (scheme == "https") 443 else 5005
        // 整段粘进来时以粘进来的为准：带了端口用它，没带按协议给默认值，
        // 免得表单里预填的 5005 把 https 的 443 盖掉。
        val port = if (pasted) urlPort ?: defaultPort else urlPort ?: fallbackPort ?: defaultPort

        return Addr(scheme, host.trim(), port, path, pasted)
    }

    /** 存回配置用：路径为空时保留原来的根路径 */
    fun apply(base: NasConfig, rawInput: String, user: String, pass: String): NasConfig {
        val a = parse(rawInput, base.scheme, if (base.host.isBlank()) null else base.port)
        return base.copy(
            scheme = a.scheme,
            host = a.host,
            port = a.port,
            rootPath = if (a.path.isNotBlank()) {
                if (a.path.startsWith("/")) a.path else "/${a.path}"
            } else base.rootPath,
            user = user,
            pass = pass
        )
    }
}
