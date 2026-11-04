package com.dsh.kidstv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Video(
    val title: String,
    val url: String,
    val thumb: String?,
    /** 同名外挂字幕（.srt/.ass/.vtt）；没有就是 null */
    val sub: String? = null
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
    private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
    private fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT
    private fun isSubtitle(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in SUB_EXT

    private val natural = Comparator<DavEntry> { a, b -> natCompare(a.name, b.name) }

    /** 一次扫描最多发多少个 PROPFIND；走中继时每个都要几百毫秒，必须封顶 */
    private const val MAX_REQUESTS = 160
    private const val MAX_SECTIONS = 120
    private const val MAX_DEPTH = 4

    fun scan(cfg: NasConfig, onStep: (String) -> Unit = {}): List<Section> {
        val dav = DavClient(cfg)
        val rootHref = DavClient.encodePath(cfg.rootPath)
        onStep("连接 NAS")
        val root = dav.propfind(rootHref, 1)

        val sections = ArrayList<Section>()

        val rootVideos = root.filter { !it.isDir && isVideo(it.name) }.sortedWith(natural)
        if (rootVideos.isNotEmpty()) {
            val art = artMap(root)
            val subs = subMap(root)
            sections += Section(
                cfg.title.ifBlank { "英语时间" },
                rootVideos.map { toVideo(dav, it, art, subs) }
            )
        }

        // 一层层往下找：哪个目录里直接有视频，就把它当成一个"系列"。
        // 以前只下钻一层，像 /视频资源/儿童视频/小猪佩奇第一部/ 这种四层结构
        // 会整个扫不到，界面只会说"NAS 里还没有视频"。
        val queue = ArrayDeque<Pair<DavEntry, Int>>()
        root.filter { it.isDir }.sortedWith(natural).forEach { queue.addLast(it to 1) }
        val visited = HashSet<String>()
        var budget = MAX_REQUESTS

        while (queue.isNotEmpty() && budget > 0 && sections.size < MAX_SECTIONS) {
            val (dir, depth) = queue.removeFirst()
            if (!visited.add(dir.href)) continue
            budget--
            onStep(cleanTitle(dir.name))
            val children = runCatching { dav.propfind(dir.href, 1) }.getOrNull() ?: continue

            val vids = children.filter { !it.isDir && isVideo(it.name) }.sortedWith(natural)
            if (vids.isNotEmpty()) {
                val art = artMap(children)
                val subs = subMap(children)
                sections += Section(cleanTitle(dir.name), vids.map { toVideo(dav, it, art, subs) })
                // 这个目录自己就有视频，就不再往里钻，免得父子重复
                continue
            }
            if (depth >= MAX_DEPTH) continue
            children.filter { it.isDir }.sortedWith(natural).forEach { queue.addLast(it to depth + 1) }
        }

        return sections
    }

    private fun artMap(entries: List<DavEntry>): Map<String, DavEntry> =
        entries.filter { !it.isDir && isImage(it.name) }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }

    private fun subMap(entries: List<DavEntry>): Map<String, DavEntry> =
        entries.filter { !it.isDir && isSubtitle(it.name) }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }

    private fun toVideo(
        dav: DavClient,
        e: DavEntry,
        art: Map<String, DavEntry>,
        subs: Map<String, DavEntry>
    ): Video {
        val base = e.name.substringBeforeLast('.').lowercase()
        val cover = art[base]
        val sub = subs[base]
        return Video(
            title = cleanTitle(e.name),
            url = dav.urlFor(e.href),
            thumb = cover?.let { dav.urlFor(it.href) },
            sub = sub?.let { dav.urlFor(it.href) }
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
        root.put("v", 3)
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
                if (v.sub != null) vo.put("s", v.sub)
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
            if (root.optInt("v", 1) < 3) return null // 老缓存没有字幕字段，丢掉重扫
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
                        vo.optString("th").ifBlank { null },
                        vo.optString("s").ifBlank { null }
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
