package com.yogi.launcher.plus

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

/** Sends the current state (badges, notification previews, lock ready) to Yogi Launcher. */
object Bridge {
    const val LAUNCHER = "com.yogi.launcher"
    const val PERM = "com.yogi.permission.LAUNCHER_PLUS"
    const val ACTION_STATE = "com.yogi.launcher.BADGES"

    @Volatile var counts: Bundle = Bundle()
    @Volatile var notes: ArrayList<Bundle> = ArrayList()
    @Volatile var listening = false

    fun send(ctx: Context) {
        val i = Intent(ACTION_STATE).setPackage(LAUNCHER)
            .putExtra("counts", counts)
            .putParcelableArrayListExtra("notes", notes)
            .putExtra("enabled", listening)
            .putExtra("lock", LockService.instance != null)
        try {
            ctx.sendBroadcast(i, PERM)
        } catch (_: Exception) {
        }
    }
}

class BadgeService : NotificationListenerService() {
    override fun onListenerConnected() {
        Bridge.listening = true
        recount()
    }

    override fun onListenerDisconnected() {
        Bridge.listening = false
        Bridge.counts = Bundle()
        Bridge.notes = ArrayList()
        Bridge.send(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { recount() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { recount() }

    private fun recount() {
        val all = try { activeNotifications } catch (_: Exception) { null } ?: return
        val counts = Bundle()
        val latest = HashMap<String, Bundle>()
        for (s in all) {
            val n = s.notification ?: continue
            if (s.isOngoing) continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val pkg = s.packageName
            counts.putInt(pkg, counts.getInt(pkg, 0) + if (n.number > 0) n.number else 1)
            val prev = latest[pkg]
            if (prev == null || s.postTime > prev.getLong("time")) {
                val ex = n.extras
                val b = Bundle()
                b.putString("pkg", pkg)
                b.putString("title", ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
                b.putString("text", ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
                b.putLong("time", s.postTime)
                n.contentIntent?.let { b.putParcelable("intent", it) }
                latest[pkg] = b
            }
        }
        Bridge.counts = counts
        Bridge.notes = ArrayList(latest.values)
        Bridge.send(this)
    }
}

/** Only used to lock the screen when you double-tap the home screen. */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Bridge.send(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Bridge.send(this)
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: LockService? = null

        fun lock(): Boolean =
            Build.VERSION.SDK_INT >= 28 && instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
    }
}

class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "com.yogi.launcher.plus.LOCK" -> LockService.lock()
            else -> Bridge.send(ctx)
        }
    }
}

/** Setup screen: three simple steps. */
class SetupActivity : Activity() {
    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun step(col: LinearLayout, title: String, done: Boolean, hint: String, onClick: () -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1C1C1E.toInt())
            cornerRadius = dp(18).toFloat()
        }
        box.addView(text((if (done) "✅  " else "⬜  ") + title, 17f, Color.WHITE, true))
        box.addView(text(hint, 13f, 0xFF98989F.toInt()))
        box.setOnClickListener { onClick() }
        col.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) })
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun build() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(40), dp(20), dp(30))
        col.addView(text("Yogi Launcher Plus", 28f, Color.WHITE, true))
        col.addView(text("Optional extras for Yogi Launcher: red notification badges with previews, and double-tap to lock. " +
            "They live in this small add-on so the launcher itself needs no special permissions.", 14f, 0xFF98989F.toInt())
            .apply { setPadding(0, dp(6), 0, dp(14)) })
        val listening = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val lock = LockService.instance != null
        if (Build.VERSION.SDK_INT >= 33) {
            step(col, "1. Allow restricted settings", listening || lock,
                "Android 13+: on the App info page tap ⋮ (top right) → “Allow restricted settings”, then come back.") {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        step(col, "Notification badges", listening, "Turn on “Yogi Launcher badges” in Notification access.") {
            open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        step(col, "Double-tap to lock", lock, "Accessibility → Installed apps → “Yogi Launcher double-tap lock” → On. " +
            "It is only used to lock the screen.") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(text("Status is sent to the launcher automatically. You can close this screen.", 12f, 0xFF98989F.toInt())
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        setContentView(sv)
        Bridge.send(this)
    }
}
