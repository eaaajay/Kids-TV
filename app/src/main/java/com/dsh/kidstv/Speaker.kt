package com.dsh.kidstv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 焦点朗读：遥控器停在哪张卡片上，就把标题念出来。
 *
 * 遥控器连按时焦点会飞快划过一串卡片，所以延迟 220ms 再念，
 * 并且用 QUEUE_FLUSH 只念最后一个，不会排队念一串。
 */
object Speaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lang: Locale? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun init(ctx: Context) {
        if (tts != null) return
        tts = runCatching {
            TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    runCatching {
                        tts?.setSpeechRate(0.95f)
                        tts?.setPitch(1.0f)
                    }
                }
            }
        }.getOrNull()
    }

    /** 焦点落定后朗读；空字符串直接忽略 */
    fun say(text: String, delayMs: Long = 220) {
        val s = text.trim()
        if (s.isEmpty() || tts == null) return
        pending?.let { handler.removeCallbacks(it) }
        val job = Runnable { speakNow(s) }
        pending = job
        handler.postDelayed(job, delayMs)
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        if (!ready) return
        val want = if (text.any { it.code in 0x2E80..0x9FFF }) Locale.CHINA else Locale.US
        if (want != lang) {
            val r = runCatching { t.setLanguage(want) }.getOrNull()
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                runCatching { t.setLanguage(Locale.getDefault()) }
            }
            lang = want
        }
        runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kidstv-$text") }
    }

    fun stop() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        lang = null
    }
}
