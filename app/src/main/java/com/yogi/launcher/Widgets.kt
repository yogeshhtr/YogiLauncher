package com.yogi.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
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

/** One AppWidgetHost for the whole launcher (widgets page + home pages). */
object WidgetHost {
    private var hostRef: AppWidgetHost? = null

    fun host(ctx: Context): AppWidgetHost =
        hostRef ?: AppWidgetHost(ctx.applicationContext, 0x5947).also { hostRef = it }

    fun manager(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx.applicationContext)

    fun create(ctx: Context, id: Int): AppWidgetHostView? {
        val info = try { manager(ctx).getAppWidgetInfo(id) } catch (_: Exception) { null } ?: return null
        return try { host(ctx).createView(ctx.applicationContext, id, info) } catch (_: Exception) { null }
    }

    fun delete(ctx: Context, id: Int) {
        if (id < 0) return
        try { host(ctx).deleteAppWidgetId(id) } catch (_: Exception) {}
    }

    /** Rounded frame that also notices a long press. */
    fun frame(a: Context, child: View, radiusDp: Float = 24f, glass: Boolean = true): LongPressFrame {
        val f = LongPressFrame(a)
        if (glass) f.background = rounded(0x22FFFFFF, a.dpf(radiusDp))
        f.clipToOutline = true
        f.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, a.dpf(radiusDp))
            }
        }
        f.addView(child, FrameLayout.LayoutParams(MATCH, MATCH))
        return f
    }
}

/**
 * The page left of the home screen (a widget stack), and the shared "add a widget" flow.
 * Widgets can go there or onto a home page.
 */
class WidgetsPage(private val a: LauncherActivity) {
    val view = ScrollView(a)
    private val col = a.vbox()
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null
    /** true = the widget being added goes onto the current home page */
    private var forHome = false

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
        col.addView(a.label("Hold a widget to remove it · add widgets to home pages from Edit mode", 13f, 0xCCFFFFFF.toInt()).lin(b = a.dp(12)))
        val keep = ArrayList<Int>()
        val awm = WidgetHost.manager(a)
        for (id in ids()) {
            val info = try { awm.getAppWidgetInfo(id) } catch (_: Exception) { null }
            if (info == null) {
                WidgetHost.delete(a, id)
                continue
            }
            keep.add(id)
            val hv = WidgetHost.create(a, id) ?: continue
            val frame = WidgetHost.frame(a, hv)
            frame.onLong = { remove(id) }
            val hgt = maxOf(info.minHeight, a.dp(110)) + a.dp(16)
            col.addView(frame, LinearLayout.LayoutParams(MATCH, hgt).apply { setMargins(0, a.dp(6), 0, a.dp(6)) })
        }
        if (keep.size != ids().size) saveIds(keep)
        col.addView(a.pill("＋  Add a widget", 0x553A3A3C) { openPicker(false) }.lin(t = a.dp(12)))
        if (keep.isEmpty()) {
            col.addView(a.label("Add your favourite widgets: clock, weather, calendar, notes, music, Yogi apps…",
                13f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }.lin(t = a.dp(10)))
        }
    }

    /** Built-in widgets first, then every Android widget on the phone. */
    fun openPicker(home: Boolean) {
        forHome = home
        val pm = a.packageManager
        val builtins = if (home) listOf(
            "🕐 Yogi clock · big digits" to Pair("clock", 0),
            "⠿ Yogi clock · dot-matrix" to Pair("clock", 1),
            "🕰 Yogi clock · analog" to Pair("clock", 2),
            "🔢 Yogi clock · stacked" to Pair("clock", 3),
            "📅 Yogi date" to Pair("date", 0),
            "🔋 Yogi battery" to Pair("battery", 0)
        ) else emptyList()
        val providers = try { WidgetHost.manager(a).installedProviders } catch (_: Exception) { emptyList<AppWidgetProviderInfo>() }
        val named = providers.map { p ->
            val app = try { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() } catch (_: Exception) { p.provider.packageName }
            val w = (try { p.loadLabel(pm) } catch (_: Exception) { "" }) ?: ""
            Pair("$app · $w", p)
        }.sortedBy { it.first.lowercase() }
        val labels = builtins.map { it.first } + named.map { it.first }
        if (labels.isEmpty()) {
            a.toast("No widgets found")
            return
        }
        AlertDialog.Builder(a)
            .setTitle(if (home) "Add to this home page" else "Add a widget")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < builtins.size) {
                    val (kind, style) = builtins[i].second
                    a.addBuiltinWidget(kind, style)
                } else {
                    choose(named[i - builtins.size].second)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choose(info: AppWidgetProviderInfo) {
        val id = try { WidgetHost.host(a).allocateAppWidgetId() } catch (_: Exception) { return }
        val ok = try { WidgetHost.manager(a).bindAppWidgetIdIfAllowed(id, info.provider) } catch (_: Exception) { false }
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
                WidgetHost.delete(a, id)
                a.toast("This phone doesn't allow adding that widget")
            }
        }
    }

    private fun configure(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            pendingId = id
            pendingInfo = info
            try {
                WidgetHost.host(a).startAppWidgetConfigureActivityForResult(a, id, 0, REQ_CONF, null)
                return
            } catch (_: Exception) {
            }
        }
        added(id, info)
    }

    private fun added(id: Int, info: AppWidgetProviderInfo?) {
        if (forHome) {
            a.addAppWidget(id, info)
        } else {
            saveIds(ids() + id)
            build()
            a.pager.setPage(0, true)
        }
    }

    private fun remove(id: Int) {
        AlertDialog.Builder(a)
            .setTitle("Remove this widget?")
            .setPositiveButton("Remove") { _, _ ->
                WidgetHost.delete(a, id)
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
            REQ_BIND -> if (res == Activity.RESULT_OK && info != null) configure(id, info) else WidgetHost.delete(a, id)
            REQ_CONF -> if (res == Activity.RESULT_OK) added(id, info) else WidgetHost.delete(a, id)
        }
    }

    fun startListening() {
        try { WidgetHost.host(a).startListening() } catch (_: Exception) {}
    }

    fun stopListening() {
        try { WidgetHost.host(a).stopListening() } catch (_: Exception) {}
    }
}
