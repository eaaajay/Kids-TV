package com.dsh.kidstv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机尽量自动拉起（部分电视系统会拦截后台启动，属于正常情况）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        val cfg = Prefs.load(context)
        if (!cfg.ready) return
        runCatching {
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }
}
