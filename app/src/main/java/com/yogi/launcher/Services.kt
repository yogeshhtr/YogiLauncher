package com.yogi.launcher

import android.app.Activity
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog

/** The optional add-on app "Yogi Launcher Plus" (badges + double-tap lock). */
object Plus {
    const val PKG = "com.yogi.launcher.plus"
    const val PERM = "com.yogi.permission.LAUNCHER_PLUS"
    const val ACTION_STATE = "com.yogi.launcher.BADGES"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun send(ctx: Context, action: String) {
        try {
            ctx.sendBroadcast(Intent(action).setPackage(PKG), PERM)
        } catch (_: Exception) {
        }
    }

    /** Asks the add-on to send its current state. */
    fun sync(ctx: Context) = send(ctx, "com.yogi.launcher.plus.SYNC")

    fun lock(ctx: Context) = send(ctx, "com.yogi.launcher.plus.LOCK")

    fun open(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** Latest notification of an app (shown in the long-press menu). */
class Note(val title: String, val text: String, val time: Long, val intent: PendingIntent?)

/** Unread notification counts per app, sent by Yogi Launcher Plus. */
object Badges {
    @Volatile var counts: Map<String, Int> = emptyMap()
        private set
    @Volatile var notes: Map<String, Note> = emptyMap()
        private set
    @Volatile var connected = false
        private set
    @Volatile var lockReady = false
        private set
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun fromIntent(i: Intent) {
        val b = i.getBundleExtra("counts") ?: Bundle()
        val c = HashMap<String, Int>()
        for (k in b.keySet()) c[k] = b.getInt(k)
        val n = HashMap<String, Note>()
        val list: ArrayList<Bundle> = i.getParcelableArrayListExtra("notes") ?: ArrayList()
        for (nb in list) {
            val pkg = nb.getString("pkg") ?: continue
            val pi: PendingIntent? = nb.getParcelable("intent")
            n[pkg] = Note(nb.getString("title") ?: "", nb.getString("text") ?: "", nb.getLong("time"), pi)
        }
        connected = i.getBooleanExtra("enabled", false)
        lockReady = i.getBooleanExtra("lock", false)
        counts = c
        notes = n
        main.post { listener?.invoke() }
    }

    fun count(pkg: String): Int = counts[pkg] ?: 0

    fun allowed(ctx: Context): Boolean = Plus.installed(ctx) && connected

    /** Opens the add-on's setup, or explains how to get it. */
    fun openSettings(ctx: Context) {
        if (Plus.installed(ctx) && Plus.open(ctx)) return
        val a = ctx as? Activity
        if (a == null) {
            ctx.toast("Install Yogi Launcher Plus for badges")
            return
        }
        AlertDialog.Builder(a)
            .setTitle("Badges & double-tap lock")
            .setMessage("These extras need notification access and an accessibility service. Google blocks such apps when " +
                "they are installed from a browser, so they live in a separate small app: Yogi Launcher Plus " +
                "(YogiLauncherPlus.apk on the same release page). Install it from a computer with:\n\n" +
                "adb install YogiLauncherPlus.apk\n\nThe launcher itself needs no special permissions.")
            .setPositiveButton("Open release page") { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yogeshhtr/YogiLauncher/releases/latest")))
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
