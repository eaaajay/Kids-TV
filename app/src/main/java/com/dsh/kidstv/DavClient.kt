package com.dsh.kidstv

import android.util.Xml
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DavEntry(val name: String, val href: String, val isDir: Boolean, val size: Long)

/**
 * 极简 WebDAV 客户端：只做两件事 —— 列目录(PROPFIND)、拼播放地址。
 * 兼容群晖 / 威联通 / 极空间 / 绿联 / Nextcloud / Alist 的 WebDAV 服务端。
 */
class DavClient(private val cfg: NasConfig) {

    val origin: String = cfg.base.trimEnd('/')

    private fun auth(): String = Credentials.basic(cfg.user, cfg.pass, Charsets.UTF_8)

    fun urlFor(href: String): String = origin + href

    fun propfind(href: String, depth: Int = 1): List<DavEntry> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(urlFor(href))
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth.toString())
            .header("Authorization", auth())
            .build()

        client.newCall(request).execute().use { resp ->
            if (resp.code == 401) throw IOException("账号或密码不对")
            if (resp.code == 404) throw IOException("路径不存在：$href")
            if (!resp.isSuccessful) throw IOException("服务器返回 ${resp.code}")
            val text = resp.body?.string() ?: throw IOException("空响应")
            return parseMultiStatus(text, href)
        }
    }

    private fun parseMultiStatus(xml: String, self: String): List<DavEntry> {
        val parser = Xml.newPullParser()
        // 必须开命名空间：真实服务器（群晖 / Alist / 极空间…）返回的都是 <D:href>。
        // 关掉命名空间时 getName() 会给出 "D:href"，跟 "href" 永远对不上。
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        var href: String? = null
        var isDir = false
        var size = 0L
        val out = ArrayList<DavEntry>()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> {
                        href = null; isDir = false; size = 0L
                    }
                    // 文本要在 START_TAG 上取：END_TAG 事件里 getText() 返回 null
                    "href" -> href = runCatching { parser.nextText() }.getOrNull()?.trim()
                    "collection" -> isDir = true
                    "getcontentlength" -> {
                        val v = runCatching { parser.nextText() }.getOrDefault("")
                        size = v.trim().toLongOrNull() ?: 0L
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "response") {
                val raw = href
                if (!raw.isNullOrEmpty()) {
                    val entry = makeEntry(raw, isDir, size)
                    if (entry != null && normalize(entry.href) != normalize(self)) out.add(entry)
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun makeEntry(rawHref: String, isDir: Boolean, size: Long): DavEntry? {
        var href = rawHref
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            val idx = href.indexOf('/', href.indexOf("://") + 3)
            if (idx < 0) return null
            href = href.substring(idx)
        }
        if (!href.startsWith("/")) href = "/$href"
        val seg = href.trimEnd('/').substringAfterLast('/')
        val name = decodeSeg(seg)
        if (name.isEmpty()) return null
        return DavEntry(name, href.trimEnd('/'), isDir, size)
    }

    companion object {
        fun encodePath(path: String): String {
            val clean = if (path.startsWith("/")) path else "/$path"
            return clean.split("/").joinToString("/") { seg ->
                if (seg.isEmpty()) "" else encodeSeg(seg)
            }.ifEmpty { "/" }
        }

        fun encodeSeg(seg: String): String =
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

        fun decodeSeg(seg: String): String = try {
            URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            seg
        }

        private fun normalize(h: String) = decodeSeg(h.trimEnd('/'))

        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
