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

    /** 增量扫描用的目录指纹：mtime 没变就说明里面的文件没动 */
    private data class CachedDir(
        val mtime: Long,
        val videos: List<Video>,
        val subdirs: List<DavEntry>
    )

    private fun fetchChildren(dav: DavClient, href: String): List<DavEntry>? {
        for (attempt in 0 until 3) {
            try {
                return dav.propfind(href, 1)
            } catch (e: Exception) {
                android.util.Log.w("Library", "读取 $href 失败（第 ${attempt + 1} 次）：${e.message}")
                Thread.sleep(300L * (attempt + 1))
            }
        }
        return null
    }

    fun scan(ctx: Context?, cfg: NasConfig, onStep: (String) -> Unit = {}): List<Section> {
        val dav = DavClient(cfg)
        val rootHref = DavClient.encodePath(cfg.rootPath)
        onStep("连接 NAS")
        val root = dav.propfind(rootHref, 1)

        // 上一次扫描留下的目录指纹；命中就不发 PROPFIND
        val oldIndex = if (ctx != null) loadIndex(ctx, cfg) else emptyMap()
        val newIndex = HashMap<String, CachedDir>()

        val sections = ArrayList<Section>()

        val rootVideos = root.filter { !it.isDir && isVideo(it.name) }.sortedWith(natural)
        if (rootVideos.isNotEmpty()) {
            val art = artMap(root)
            sections += Section(
                cfg.title.ifBlank { "英语时间" },
                rootVideos.map { toVideo(dav, it, art, subMap(root)) }
            )
        }

        val queue = ArrayDeque<Pair<DavEntry, Int>>()
        root.filter { it.isDir && !it.name.startsWith(".") }.sortedWith(natural)
            .forEach { queue.addLast(it to 1) }
        val visited = HashSet<String>()
        var budget = MAX_REQUESTS
        var reused = 0

        while (queue.isNotEmpty() && budget > 0 && sections.size < MAX_SECTIONS) {
            val (dir, depth) = queue.removeFirst()
            if (!visited.add(dir.href)) continue
            budget--

            val cached = oldIndex[dir.href]
            val hit = cached != null && dir.lastModified > 0 && cached.mtime == dir.lastModified

            val vids: List<Video>
            val subdirs: List<DavEntry>
            if (hit) {
                vids = cached!!.videos
                subdirs = cached.subdirs
                newIndex[dir.href] = cached
                reused++
            } else {
                onStep(cleanTitle(dir.name))
                val kids = fetchChildren(dav, dir.href) ?: continue
                val list = kids.filter { !it.isDir && isVideo(it.name) }.sortedWith(natural)
                vids = list.map { toVideo(dav, it, artMap(kids), subMap(kids)) }
                subdirs = kids.filter { it.isDir && !it.name.startsWith(".") }.sortedWith(natural)
                newIndex[dir.href] = CachedDir(dir.lastModified, vids, subdirs)
            }

            if (vids.isNotEmpty()) {
                sections += Section(cleanTitle(dir.name), vids)
                // 这个目录自己就有视频，就不再往里钻，免得父子重复
                continue
            }
            if (depth >= MAX_DEPTH) continue
            subdirs.forEach { queue.addLast(it to depth + 1) }
        }

        if (ctx != null) saveIndex(ctx, cfg, newIndex)
        if (reused > 0) android.util.Log.d("Library", "增量扫描：复用 $reused 个目录")
        return sections
    }

    // ---------- 增量扫描索引 ----------

    private fun indexFile(ctx: Context, cfg: NasConfig) =
        File(ctx.filesDir, "scan-${md5(cfg.cacheKey)}.json")

    private fun loadIndex(ctx: Context, cfg: NasConfig): Map<String, CachedDir> {
        val f = indexFile(ctx, cfg)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(f.readText())
            val out = HashMap<String, CachedDir>()
            val it = root.keys()
            while (it.hasNext()) {
                val href = it.next()
                val o = root.optJSONObject(href) ?: continue
                val videos = ArrayList<Video>()
                val va = o.optJSONArray("v")
                if (va != null) {
                    for (i in 0 until va.length()) {
                        val vo = va.getJSONObject(i)
                        videos += Video(
                            vo.optString("t"),
                            vo.optString("u"),
                            vo.optString("th").ifBlank { null },
                            vo.optString("sub").ifBlank { null }
                        )
                    }
                }
                val subdirs = ArrayList<DavEntry>()
                val da = o.optJSONArray("d")
                if (da != null) {
                    for (i in 0 until da.length()) {
                        val de = da.getJSONObject(i)
                        subdirs += DavEntry(
                            name = de.optString("n"),
                            href = de.optString("h"),
                            isDir = true,
                            size = 0L,
                            lastModified = de.optLong("m")
                        )
                    }
                }
                out[href] = CachedDir(o.optLong("mt"), videos, subdirs)
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun saveIndex(ctx: Context, cfg: NasConfig, index: Map<String, CachedDir>) {
        runCatching {
            val root = JSONObject()
            index.forEach { (href, c) ->
                val o = JSONObject()
                o.put("mt", c.mtime)
                val va = JSONArray()
                c.videos.forEach { v ->
                    val vo = JSONObject()
                    vo.put("t", v.title)
                    vo.put("u", v.url)
                    if (v.thumb != null) vo.put("th", v.thumb)
                    if (v.sub != null) vo.put("sub", v.sub)
                    va.put(vo)
                }
                o.put("v", va)
                val da = JSONArray()
                c.subdirs.forEach { d ->
                    val de = JSONObject()
                    de.put("n", d.name)
                    de.put("h", d.href)
                    de.put("m", d.lastModified)
                    da.put(de)
                }
                o.put("d", da)
                root.put(href, o)
            }
            indexFile(ctx, cfg).writeText(root.toString())
        }
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
