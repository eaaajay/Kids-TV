package com.dsh.kidstv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Video(
    val title: String,
    val url: String,
    val thumb: String?
)

data class Section(val title: String, val videos: List<Video>)

/**
 * 扫描 NAS 上的目录，变成一个"分区 -> 视频列表"的库。
 * 结果会缓存到本地，断网/开机时也能立刻显示上次的内容。
 */
object Library {

    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "flv", "wmv", "mpg", "mpeg", "rmvb"
    )
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
    private fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    fun scan(cfg: NasConfig, onStep: (String) -> Unit = {}): List<Section> {
        val dav = DavClient(cfg)
        val rootHref = DavClient.encodePath(cfg.rootPath)
        onStep("连接 NAS")
        val root = dav.propfind(rootHref, 1)

        val dirs = root.filter { it.isDir }.sortedWith(Comparator { a, b -> natCompare(a.name, b.name) })
        val rootVideos = root.filter { !it.isDir && isVideo(it.name) }

        val sections = ArrayList<Section>()

        if (rootVideos.isNotEmpty()) {
            val art = artMap(root)
            sections += Section(
                cfg.title.ifBlank { "英语时间" },
                rootVideos.sortedWith(Comparator { a, b -> natCompare(a.name, b.name) })
                    .map { toVideo(dav, it, art) }
            )
        }

        dirs.take(40).forEach { dir ->
            onStep(dir.name)
            val children = runCatching { dav.propfind(dir.href, 1) }.getOrNull() ?: return@forEach
            val vids = children.filter { !it.isDir && isVideo(it.name) }
            if (vids.isEmpty()) return@forEach
            val art = artMap(children)
            sections += Section(
                cleanTitle(dir.name),
                vids.sortedWith(Comparator { a, b -> natCompare(a.name, b.name) })
                    .map { toVideo(dav, it, art) }
            )
        }

        return sections
    }

    private fun artMap(entries: List<DavEntry>): Map<String, DavEntry> =
        entries.filter { !it.isDir && isImage(it.name) }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }

    private fun toVideo(dav: DavClient, e: DavEntry, art: Map<String, DavEntry>): Video {
        val base = e.name.substringBeforeLast('.').lowercase()
        val cover = art[base]
        return Video(
            title = cleanTitle(e.name),
            url = dav.urlFor(e.href),
            thumb = cover?.let { dav.urlFor(it.href) }
        )
    }

    fun cleanTitle(fileName: String): String {
        var t = fileName.substringBeforeLast('.')
        t = t.replace('_', ' ').replace(Regex("\\s{2,}"), " ").trim()
        t = t.replace(Regex("^\\d{1,3}\\s*[-.、]\\s*"), "")
        return t.ifBlank { fileName }
    }

    fun natCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var x = i
                while (x < a.length && a[x].isDigit()) x++
                var y = j
                while (y < b.length && b[y].isDigit()) y++
                val na = a.substring(i, x).toLongOrNull() ?: 0L
                val nb = b.substring(j, y).toLongOrNull() ?: 0L
                if (na != nb) return na.compareTo(nb)
                i = x
                j = y
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    // ---------- 缓存 ----------

    private fun cacheFile(ctx: Context, cfg: NasConfig): File {
        val name = md5(cfg.cacheKey)
        return File(ctx.filesDir, "library-$name.json")
    }

    fun saveCache(ctx: Context, cfg: NasConfig, sections: List<Section>) {
        val root = JSONObject()
        root.put("key", cfg.cacheKey)
        root.put("saved", System.currentTimeMillis())
        val arr = JSONArray()
        sections.forEach { s ->
            val so = JSONObject()
            so.put("title", s.title)
            val va = JSONArray()
            s.videos.forEach { v ->
                val vo = JSONObject()
                vo.put("t", v.title)
                vo.put("u", v.url)
                if (v.thumb != null) vo.put("th", v.thumb)
                va.put(vo)
            }
            so.put("videos", va)
            arr.put(so)
        }
        root.put("sections", arr)
        runCatching { cacheFile(ctx, cfg).writeText(root.toString()) }
    }

    fun loadCache(ctx: Context, cfg: NasConfig): List<Section>? {
        val f = cacheFile(ctx, cfg)
        if (!f.exists()) return null
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("sections") ?: return null
            val out = ArrayList<Section>()
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                val va = so.optJSONArray("videos") ?: continue
                val vids = ArrayList<Video>()
                for (k in 0 until va.length()) {
                    val vo = va.getJSONObject(k)
                    vids += Video(
                        vo.optString("t"),
                        vo.optString("u"),
                        vo.optString("th").ifBlank { null }
                    )
                }
                out += Section(so.optString("title"), vids)
            }
            out
        }.getOrNull()
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
