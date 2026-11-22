package com.yogi.launcher

import android.app.Notification
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/** Latest notification of an app (shown in the long-press menu). */
class Note(val title: String, val text: String, val time: Long, val intent: PendingIntent?)

/** Unread notification counts per app, from [BadgeService]. */
object Badges {
    @Volatile var counts: Map<String, Int> = emptyMap()
        private set
    @Volatile var notes: Map<String, Note> = emptyMap()
        private set
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(c: Map<String, Int>, n: Map<String, Note>) {
        counts = c
        notes = n
        main.post { listener?.invoke() }
    }

    fun count(pkg: String): Int = counts[pkg] ?: 0

    fun allowed(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ctx.toast("Open Settings → Notifications → Notification access")
        }
    }
}

class BadgeService : NotificationListenerService() {
    override fun onListenerConnected() { recount() }
    override fun onListenerDisconnected() { Badges.update(emptyMap(), emptyMap()) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { recount() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { recount() }

    private fun recount() {
        val all = try { activeNotifications } catch (_: Exception) { null } ?: return
        val counts = HashMap<String, Int>()
        val notes = HashMap<String, Note>()
        for (s in all) {
            val n = s.notification ?: continue
            if (s.isOngoing) continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val pkg = s.packageName
            counts[pkg] = (counts[pkg] ?: 0) + maxOf(1, if (n.number > 0) n.number else 1)
            val ex = n.extras
            val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val prev = notes[pkg]
            if (prev == null || s.postTime > prev.time) notes[pkg] = Note(title, text, s.postTime, n.contentIntent)
        }
        Badges.update(counts, notes)
    }
}

object System2 {
    /** Pulls down the notification shade (works on most phones). */
    fun expandNotifications(ctx: Context): Boolean = try {
        val sb = ctx.getSystemService("statusbar")
        val c = Class.forName("android.app.StatusBarManager")
        c.getMethod("expandNotificationsPanel").invoke(sb)
        true
    } catch (_: Throwable) {
        false
    }

    fun isDefaultLauncher(ctx: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val r = try { ctx.packageManager.resolveActivity(i, 0) } catch (_: Exception) { null }
        return r?.activityInfo?.packageName == ctx.packageName
    }

    /** Opens the system choice for the default home app. */
    fun askDefault(a: android.app.Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = a.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 77)
                    return
                }
            }
            a.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try {
                a.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }
}
