package com.yogi.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat

/** Latest notification of an app (shown in the long-press menu). */
class Note(val title: String, val text: String, val time: Long, val intent: PendingIntent?)

/** Unread notification counts per app, from [BadgeService] (built into the launcher since v6). */
object Badges {
    @Volatile var counts: Map<String, Int> = emptyMap()
        private set
    @Volatile var notes: Map<String, Note> = emptyMap()
        private set
    @Volatile var connected = false
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    val lockReady: Boolean get() = LockService.instance != null

    fun update(c: Map<String, Int>, n: Map<String, Note>) {
        counts = c
        notes = n
        main.post { listener?.invoke() }
    }

    fun count(pkg: String): Int = counts[pkg] ?: 0

    /** Notification access is turned on for Yogi Launcher. */
    fun allowed(ctx: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    fun lockAllowed(ctx: Context): Boolean = try {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        s.contains(ctx.packageName + "/")
    } catch (_: Exception) {
        false
    }

    /** Opens notification access, with a hint for Android 13+ "restricted settings". */
    fun openSettings(a: android.app.Activity) = Access.ask(a, false)
}

/** Explains and opens notification access / accessibility for badges and double-tap lock. */
object Access {
    fun ask(a: android.app.Activity, lock: Boolean) {
        val what = if (lock) "Accessibility → Installed apps → “Yogi Launcher double-tap lock” → On. It is only used to lock the screen."
        else "Notification access → “Yogi Launcher” → Allow. Used only for the red badges and the notification preview in the long-press menu."
        val restricted = if (Build.VERSION.SDK_INT >= 33)
            "\n\nIf Android says “Restricted setting”: tap App info below → ⋮ (top right) → “Allow restricted settings”, then try again." else ""
        AlertDialog.Builder(a)
            .setTitle(if (lock) "Double-tap to lock" else "Notification badges")
            .setMessage(what + restricted)
            .setPositiveButton("Open settings") { _, _ ->
                open(a, if (lock) Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            .setNeutralButton("App info") { _, _ ->
                open(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun open(a: android.app.Activity, i: Intent) {
        try {
            a.startActivity(i)
        } catch (_: Exception) {
            try { a.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }
}

/** Red badges + notification previews. */
class BadgeService : NotificationListenerService() {
    override fun onListenerConnected() {
        Badges.connected = true
        recount()
    }

    override fun onListenerDisconnected() {
        Badges.connected = false
        Badges.update(emptyMap(), emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { recount() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { recount() }

    private fun recount() {
        val all = try { activeNotifications } catch (_: Exception) { null } ?: return
        val counts = HashMap<String, Int>()
        val latest = HashMap<String, Note>()
        for (s in all) {
            val n = s.notification ?: continue
            if (s.isOngoing) continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val pkg = s.packageName
            counts[pkg] = (counts[pkg] ?: 0) + if (n.number > 0) n.number else 1
            val prev = latest[pkg]
            if (prev == null || s.postTime > prev.time) {
                val ex = n.extras
                latest[pkg] = Note(
                    ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
                    ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
                    s.postTime, n.contentIntent)
            }
        }
        Badges.update(counts, latest)
    }
}

/** Only used to lock the screen when you double-tap the home screen. */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: LockService? = null

        fun lock(): Boolean =
            Build.VERSION.SDK_INT >= 28 && instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
    }
}

/** Device admin, used only to lock the screen when accessibility is off or not running. */
class AdminReceiver : android.app.admin.DeviceAdminReceiver()

object Locker {
    private fun comp(ctx: Context) = android.content.ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager

    fun adminActive(ctx: Context): Boolean = try { dpm(ctx).isAdminActive(comp(ctx)) } catch (_: Exception) { false }

    fun askAdmin(a: android.app.Activity) {
        try {
            a.startActivity(Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp(a))
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets Yogi Launcher lock the screen when you double-tap the home screen. It is used for nothing else."))
        } catch (_: Exception) {
            a.toast("This phone doesn't allow it")
        }
    }

    fun removeAdmin(ctx: Context) {
        try { dpm(ctx).removeActiveAdmin(comp(ctx)) } catch (_: Exception) {}
    }

    /** Accessibility first (keeps fingerprint unlock), then device admin. */
    fun lock(ctx: Context): Boolean {
        if (LockService.lock()) return true
        if (adminActive(ctx)) {
            return try {
                dpm(ctx).lockNow()
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
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
