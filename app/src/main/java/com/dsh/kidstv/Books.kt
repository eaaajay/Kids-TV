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

        val root = dav.propfind(DavClient.encodePath(cfg.booksRoot), 1)
        collect(dav, root, out)

        root.filter { it.isDir }
            .sortedWith(Comparator { a, b -> Library.natCompare(a.name, b.name) })
            .take(60)
            .forEach { dir ->
                val children = runCatching { dav.propfind(dir.href, 1) }.getOrNull() ?: return@forEach
                collect(dav, children, out)
            }
        return out
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
