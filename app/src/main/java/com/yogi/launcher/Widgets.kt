package com.yogi.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray

/** The page left of the home screen: real Android widgets (clock, weather, calendar, notes, music…). */
class WidgetsPage(private val a: LauncherActivity) {
    private val host = AppWidgetHost(a.applicationContext, 0x5947)
    private val awm = AppWidgetManager.getInstance(a)
    val view = ScrollView(a)
    private val col = a.vbox()
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null

    companion object {
        const val REQ_BIND = 4101
        const val REQ_CONF = 4102
    }

    init {
        view.isVerticalScrollBarEnabled = false
        view.addView(col)
        build()
    }

    private fun ids(): List<Int> {
        val arr = try { JSONArray(Store.str("widgets", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optInt(it) }
    }

    private fun saveIds(list: List<Int>) {
        Store.setStr("widgets", JSONArray(list).toString())
    }

    fun build() {
        col.removeAllViews()
        col.setPadding(a.dp(14), a.insetTop + a.dp(64), a.dp(14), a.insetBottom + a.dp(24))
        col.addView(a.label("Widgets", 30f, Color.WHITE, true).apply { setShadowLayer(6f, 0f, 1f, 0x88000000.toInt()) })
        col.addView(a.label("Hold a widget to remove it", 13f, 0xCCFFFFFF.toInt()).lin(b = a.dp(12)))
        val keep = ArrayList<Int>()
        for (id in ids()) {
            val info = try { awm.getAppWidgetInfo(id) } catch (_: Exception) { null }
            if (info == null) {
                try { host.deleteAppWidgetId(id) } catch (_: Exception) {}
                continue
            }
            keep.add(id)
            val frame = LongPressFrame(a)
            frame.background = rounded(0x33FFFFFF, a.dpf(24f))
            frame.clipToOutline = true
            frame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, a.dpf(24f))
                }
            }
            val hv = (try { host.createView(a.applicationContext, id, info) } catch (_: Exception) { null }) ?: continue
            frame.addView(hv, FrameLayout.LayoutParams(MATCH, MATCH))
            frame.onLong = { remove(id, info) }
            val hgt = maxOf(info.minHeight, a.dp(110)) + a.dp(16)
            col.addView(frame, LinearLayout.LayoutParams(MATCH, hgt).apply { setMargins(0, a.dp(6), 0, a.dp(6)) })
        }
        if (keep.size != ids().size) saveIds(keep)
        col.addView(a.pill("＋  Add a widget", 0x553A3A3C) { openPicker() }.lin(t = a.dp(12)))
        if (keep.isEmpty()) {
            col.addView(a.label("Add your favourite widgets: clock, weather, calendar, notes, music, Yogi apps…",
                13f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }.lin(t = a.dp(10)))
        }
    }

    fun openPicker() {
        val pm = a.packageManager
        val providers = try { awm.installedProviders } catch (_: Exception) { emptyList<AppWidgetProviderInfo>() }
        if (providers.isEmpty()) {
            a.toast("No widgets found")
            return
        }
        val named = providers.map { p ->
            val app = try { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() } catch (_: Exception) { p.provider.packageName }
            val w = try { p.loadLabel(pm) } catch (_: Exception) { "" } ?: ""
            Pair("$app · $w", p)
        }.sortedBy { it.first.lowercase() }
        AlertDialog.Builder(a)
            .setTitle("Add a widget")
            .setItems(named.map { it.first }.toTypedArray()) { _, i -> choose(named[i].second) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choose(info: AppWidgetProviderInfo) {
        val id = try { host.allocateAppWidgetId() } catch (_: Exception) { return }
        val ok = try { awm.bindAppWidgetIdIfAllowed(id, info.provider) } catch (_: Exception) { false }
        if (ok) {
            configure(id, info)
        } else {
            pendingId = id
            pendingInfo = info
            try {
                a.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), REQ_BIND)
            } catch (_: Exception) {
                delete(id)
                a.toast("This phone doesn't allow adding that widget")
            }
        }
    }

    private fun configure(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            pendingId = id
            pendingInfo = info
            try {
                host.startAppWidgetConfigureActivityForResult(a, id, 0, REQ_CONF, null)
                return
            } catch (_: Exception) {
            }
        }
        add(id)
    }

    private fun add(id: Int) {
        saveIds(ids() + id)
        build()
        a.pager.setPage(0, true)
    }

    private fun delete(id: Int) {
        try { host.deleteAppWidgetId(id) } catch (_: Exception) {}
    }

    private fun remove(id: Int, info: AppWidgetProviderInfo) {
        AlertDialog.Builder(a)
            .setTitle("Remove this widget?")
            .setPositiveButton("Remove") { _, _ ->
                delete(id)
                saveIds(ids() - id)
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun onResult(req: Int, res: Int, data: Intent?) {
        val id = pendingId
        val info = pendingInfo
        when (req) {
            REQ_BIND -> if (res == Activity.RESULT_OK && info != null) configure(id, info) else delete(id)
            REQ_CONF -> if (res == Activity.RESULT_OK) add(id) else delete(id)
        }
    }

    fun startListening() {
        try { host.startListening() } catch (_: Exception) {}
    }

    fun stopListening() {
        try { host.stopListening() } catch (_: Exception) {}
    }
}
