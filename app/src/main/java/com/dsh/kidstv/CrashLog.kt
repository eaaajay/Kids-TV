package com.dsh.kidstv

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录：没有 adb 的设备（电视）也能事后看到原因。
 *
 * 写两份：
 * 1. 应用私有目录 files/crash/last.txt —— 家长页直接读出来显示；
 * 2. 外部 Download/dsh-kidstv-last-crash.txt —— 方便用文件管理器拷出来发人。
 */
object CrashLog {

    private const val DIR = "crash"
    private const val NAME = "last.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            runCatching { write(app, thread, err) }
            prev?.uncaughtException(thread, err)
        }
    }

    private fun write(ctx: Context, thread: Thread, err: Throwable) {
        val sw = StringWriter()
        err.printStackTrace(PrintWriter(sw))
        val body = buildString {
            appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("线程: ${thread.name}")
            appendLine()
            append(sw.toString())
        }

        runCatching {
            val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
            File(dir, NAME).writeText(body)
        }
        runCatching {
            val ext = ctx.getExternalFilesDir(null) ?: return@runCatching
            File(ext, NAME).writeText(body)
        }
    }

    /** 家长页用：最近一次崩溃的摘要（最多 6 行） */
    fun last(ctx: Context): String? {
        val f = File(File(ctx.filesDir, DIR), NAME)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val ts = text.lineSequence().firstOrNull { it.startsWith("时间") } ?: ""
        val cause = text.lineSequence()
            .firstOrNull { it.startsWith("Caused by") }
            ?: text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?: ""
        val at = text.lineSequence().dropWhile { !it.contains("Exception") && !it.contains("Error") }
            .drop(1).firstOrNull { it.trimStart().startsWith("at ") } ?: ""
        return listOf(ts, cause.trim(), at.trim()).filter { it.isNotBlank() }.joinToString("\n").take(600)
    }

    fun clear(ctx: Context) {
        runCatching { File(File(ctx.filesDir, DIR), NAME).delete() }
        runCatching { ctx.getExternalFilesDir(null)?.let { File(it, NAME).delete() } }
    }
}
