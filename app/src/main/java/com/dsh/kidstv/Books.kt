package com.dsh.kidstv

data class Book(
    val title: String,
    val url: String,
    /** 同名封面图（jpg/png），没有就只用色块 */
    val cover: String?
)

/**
 * 绘本：跟视频共用一套 WebDAV 扫描，只是挑 .pdf 出来。
 * 目录默认跟视频同一个根，也可以在配置里单独指一个（比如 /books）。
 */
object Books {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    private fun isPdf(name: String) = name.substringAfterLast('.', "").equals("pdf", true)
    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    fun scan(cfg: NasConfig): List<Book> {
        val dav = DavClient(cfg)
        val out = ArrayList<Book>()

        // 没单独配绘本目录时，先看 NAS 根下的 /绘本；那里没有 PDF 再退回视频根目录
        val primary = if (cfg.booksPath.isBlank()) DEFAULT_ROOT else cfg.booksRoot
        scanInto(dav, primary, out)
        if (out.isEmpty() && cfg.booksPath.isBlank() && primary != cfg.rootPath) {
            scanInto(dav, cfg.rootPath, out)
        }
        return out
    }

    private const val DEFAULT_ROOT = "/绘本"

    private fun scanInto(dav: DavClient, path: String, out: MutableList<Book>) {
        val order = Comparator<DavEntry> { a, b -> Library.natCompare(a.name, b.name) }
        val root = runCatching { dav.propfind(DavClient.encodePath(path), 1) }.getOrNull() ?: return
        collect(dav, root, out)

        // 和视频一样要能往下钻：绘本也常常放在好几层目录里
        val queue = ArrayDeque<Pair<DavEntry, Int>>()
        root.filter { it.isDir && !it.name.startsWith(".") }.sortedWith(order).forEach { queue.addLast(it to 1) }
        var budget = 60
        while (queue.isNotEmpty() && budget > 0 && out.size < 300) {
            val (dir, depth) = queue.removeFirst()
            budget--
            val children = runCatching { dav.propfind(dir.href, 1) }.getOrNull() ?: continue
            collect(dav, children, out)
            if (depth >= 4) continue
            children.filter { it.isDir && !it.name.startsWith(".") }.sortedWith(order)
                .forEach { queue.addLast(it to depth + 1) }
        }
    }

    private fun collect(dav: DavClient, entries: List<DavEntry>, out: MutableList<Book>) {
        val art = entries.filter { !it.isDir && isImage(it.name) }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }
        entries.filter { !it.isDir && isPdf(it.name) }
            .sortedWith(Comparator { a, b -> Library.natCompare(a.name, b.name) })
            .forEach { e ->
                val cover = art[e.name.substringBeforeLast('.').lowercase()]
                out += Book(
                    title = Library.cleanTitle(e.name),
                    url = dav.urlFor(e.href),
                    cover = cover?.let { dav.urlFor(it.href) }
                )
            }
    }
}
