package com.dsh.kidstv

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 电视上跑一个极简 HTTP 服务（不依赖第三方库）：
 * 家长用手机浏览器填 NAS 配置，比用遥控器敲 IP 和密码舒服一百倍。
 * 每个请求一个短连接，响应完就关，不会堆积。
 */
class SetupServer(private val ctx: Context, private val startPort: Int = 8765) {

    @Volatile
    var saved: NasConfig? = null
        private set

    @Volatile
    var port: Int = startPort
        private set

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        var p = startPort
        while (p < startPort + 20) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p), 40)
                serverSocket = ss
                port = p
                running = true
                acceptThread = Thread({ acceptLoop(ss) }, "kidstv-setup").apply {
                    isDaemon = true
                    start()
                }
                return true
            } catch (e: Exception) {
                p++
            }
        }
        return false
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            Thread({ runCatching { handle(socket) } }, "kidstv-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15000
            val input = s.getInputStream()

            // 头按字节读到空行。不能用 BufferedReader：它会把 body 一起吞进缓冲区，
            // 后面再按字节读就会少数据。
            fun readLine(): String? {
                val buf = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
                    if (b == '\n'.code) break
                    if (b != '\r'.code) buf.write(b)
                    if (buf.size() > 8192) break
                }
                return buf.toString("UTF-8")
            }

            val requestLine = readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            // Content-Length 是字节数，必须按字节读完再 UTF-8 解码。
            // 以前按字符数读：路径里有中文（/视频资源/儿童视频）时会一直等到超时。
            val body = if (contentLength <= 0) "" else {
                val bytes = ByteArray(contentLength.coerceAtMost(256 * 1024))
                var read = 0
                while (read < bytes.size) {
                    val n = input.read(bytes, read, bytes.size - read)
                    if (n <= 0) break
                    read += n
                }
                bytes.copyOf(read).toString(Charsets.UTF_8)
            }

            when (path) {
                "/", "/index.html" -> respond(s, 200, "OK", "text/html; charset=utf-8", pageHtml(Prefs.load(ctx)))
                "/save" -> {
                    val cfg = parse(body)
                    Prefs.save(ctx, cfg)
                    saved = cfg
                    respond(s, 200, "OK", TXT, "已保存 ✓ 电视上按返回键就能看到视频了")
                }
                "/test" -> respond(s, 200, "OK", TXT, test(body))
                else -> respond(s, 404, "Not Found", TXT, "not found")
            }
        }
    }

    private fun test(body: String): String {
        val cfg = parse(body)
        if (cfg.host.isBlank()) return "请先填 NAS 地址"
        return try {
            val dav = DavClient(cfg)
            val entries = dav.propfind(DavClient.encodePath(cfg.rootPath), 1)
            val dirs = entries.count { it.isDir }
            val vids = entries.count { !it.isDir && Library.isVideo(it.name) }
            // 大部分人是「根目录只放文件夹，视频在子文件夹里」，这时报 0 个视频会吓到人
            when {
                vids == 0 && dirs > 0 -> "连接成功：$dirs 个文件夹（视频都在子文件夹里，电视上会一行一个）"
                dirs == 0 && vids == 0 -> "连接成功，但这个路径下没找到视频：${cfg.rootPath}"
                else -> "连接成功：$dirs 个文件夹，$vids 个视频"
            }
        } catch (e: Exception) {
            "连接失败：" + (e.message ?: "未知错误")
        }
    }

    private fun parse(body: String): NasConfig {
        val map = HashMap<String, String>()
        body.split("&").forEach { pair ->
            if (pair.isNotEmpty()) {
                val i = pair.indexOf('=')
                if (i >= 0) map[dec(pair.substring(0, i))] = dec(pair.substring(i + 1))
            }
        }
        // 拆地址的活统一交给 NasInput：整段粘贴（含协议/端口/路径）也能认。
        val addr = NasInput.parse(
            rawInput = map["host"] ?: "",
            fallbackScheme = map["scheme"] ?: "http",
            fallbackPort = map["port"]?.trim()?.toIntOrNull()
        )
        val formRoot = (map["root"] ?: "").trim()
        // 粘的地址里带了路径就听它的，否则用表单里填的根路径
        val root = (if (addr.pastedUrl && addr.path.isNotBlank()) addr.path else formRoot).let {
            if (it.isBlank()) "/" else if (it.startsWith("/")) it else "/$it"
        }
        return NasConfig(
            host = addr.host,
            port = addr.port,
            scheme = addr.scheme,
            rootPath = root,
            user = (map["user"] ?: "").trim(),
            pass = (map["pass"] ?: "").trim(),
            title = (map["title"] ?: "").trim().ifBlank { "英语时间" },
            booksPath = (map["books"] ?: "").trim().let { if (it.isBlank()) "" else if (it.startsWith("/")) it else "/$it" }
        )
    }

    private fun dec(s: String) = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun respond(socket: Socket, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out: OutputStream = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun pageHtml(cfg: NasConfig): String {
        val port = if (cfg.host.isBlank()) "" else cfg.port.toString()
        val root = if (cfg.host.isBlank()) "" else cfg.rootPath
        return """
<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>夏夏看电视 · 家长设置</title>
<style>
  :root{--paper:#F7EDDC;--ink:#17324D;--ink2:#5B7186;--coral:#E85C43;--line:#E2D2B4;--card:#FFFBF4}
  *{box-sizing:border-box}
  body{margin:0;background:var(--paper);color:var(--ink);padding:26px 18px 60px;
       font-family:-apple-system,"PingFang SC",system-ui,sans-serif;
       background-image:radial-gradient(rgba(0,0,0,.06) 1px, transparent 1px);background-size:22px 22px}
  h1{font-size:26px;margin:0 0 4px}
  .lead{margin:0 0 22px;color:var(--ink2);font-size:14px;line-height:1.65}
  .card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:18px 18px 6px;margin-bottom:14px}
  label{display:block;font-size:13px;color:var(--ink2);margin:0 0 6px}
  input,select{width:100%;padding:13px 14px;font-size:16px;border-radius:12px;border:1.5px solid var(--line);
        background:#fff;color:var(--ink);margin-bottom:14px;font-family:inherit}
  select{appearance:none;-webkit-appearance:none}
  input:focus,select:focus{outline:none;border-color:var(--coral)}
  .row{display:flex;gap:10px}
  .row>div{flex:1}
  .row>div.narrow{flex:0 0 104px}
  button{width:100%;padding:15px;font-size:16px;font-weight:600;border:0;border-radius:14px;
         background:var(--coral);color:#fff;font-family:inherit}
  button.ghost{background:#fff;color:var(--ink);border:1.5px solid var(--line);margin-bottom:10px}
  #msg{margin-top:14px;font-size:14px;line-height:1.6;color:var(--ink2);white-space:pre-wrap}
  .note{font-size:12.5px;color:var(--ink2);line-height:1.7;margin:18px 4px 0}
</style></head>
<body>
<h1>夏夏看电视</h1>
<p class="lead">把 NAS 上的儿童英语视频接上电视。<br>
支持 WebDAV：群晖、威联通、极空间、绿联、Alist 都行。</p>

<form id="f" onsubmit="return false">
  <div class="card">
    <div class="row">
      <div class="narrow"><label>协议</label>
        <select name="scheme">
          <option value="http"${if (cfg.scheme == "https") "" else " selected"}>http</option>
          <option value="https"${if (cfg.scheme == "https") " selected" else ""}>https</option>
        </select></div>
      <div><label>NAS 地址（可以整段粘进来）</label>
        <input name="host" inputmode="url" autocomplete="off" spellcheck="false"
               autocapitalize="off" autocorrect="off"
               placeholder="192.168.1.10 或 https://dav.example.com:443" value="${esc(cfg.host)}"></div>
      <div class="narrow"><label>端口</label>
        <input name="port" inputmode="numeric" placeholder="5005" value="$port"></div>
    </div>
    <label>WebDAV 根路径</label>
    <input name="root" placeholder="/video/kids" value="${esc(root)}">
    <div class="row">
      <div><label>账号（没有就留空）</label>
        <input name="user" autocapitalize="off" autocorrect="off" value="${esc(cfg.user)}"></div>
      <div><label>密码</label>
        <input name="pass" type="password" value="${esc(cfg.pass)}"></div>
    </div>
    <label>绘本目录（放 PDF 的文件夹，留空就和视频同目录）</label>
    <input name="books" placeholder="/books/绘本" value="${esc(cfg.booksPath)}">

    <label>桌面标题（孩子看到的名字）</label>
    <input name="title" placeholder="英语时间" value="${esc(cfg.title)}">
  </div>

  <button class="ghost" id="test" type="button">先测一下能不能连上</button>
  <button id="save" type="button">保存到电视</button>
  <div id="msg"></div>
</form>

<p class="note">端口看你的 NAS：fnOS / 群晖 / 威联通常见 5005，
极空间在「系统设置 → 文件共享 → WebDAV」里能看到。<br>
整段地址直接粘进「NAS 地址」也行，比如 <b>https://dav.example.com:443</b>，
协议和端口会自动认出来（走 https 需要证书能被系统信任，自签证书不行）。<br>
路径填视频所在的文件夹；视频和同名 .jpg 放一起就会有缩略图。</p>

<script>
var f = document.getElementById('f');
var msg = document.getElementById('msg');
function post(url) {
  var body = new URLSearchParams(new FormData(f)).toString();
  return fetch(url, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: body
  }).then(function (r) { return r.text(); });
}
document.getElementById('test').onclick = function () {
  msg.textContent = '正在连接…';
  post('/test').then(function (t) { msg.textContent = t; })
    .catch(function (e) { msg.textContent = '请求失败：' + e; });
};
document.getElementById('save').onclick = function () {
  msg.textContent = '正在保存…';
  post('/save').then(function (t) { msg.textContent = t; })
    .catch(function (e) { msg.textContent = '请求失败：' + e; });
};
</script>
</body></html>
        """.trimIndent()
    }

    companion object {
        private const val TXT = "text/plain; charset=utf-8"
    }
}
