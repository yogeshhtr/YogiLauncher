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
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray

/** One AppWidgetHost for the whole launcher (widgets page + home pages). */
object WidgetHost {
    private var hostRef: AppWidgetHost? = null
    private val sizes = HashMap<Int, Long>()
    private val views = HashMap<Int, AppWidgetHostView>()

    fun host(ctx: Context): AppWidgetHost =
        hostRef ?: AppWidgetHost(ctx.applicationContext, 0x5947).also {
            hostRef = it
            try { it.startListening() } catch (_: Exception) {}
        }

    fun manager(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx.applicationContext)

    /** The widget's view, made with the activity so it gets the right theme and fonts. */
    fun create(ctx: Context, id: Int): AppWidgetHostView? {
        val info = try { manager(ctx).getAppWidgetInfo(id) } catch (_: Exception) { null } ?: return null
        views[id]?.let { old ->
            if (old.context === ctx && old.appWidgetInfo?.provider == info.provider) {
                (old.parent as? android.view.ViewGroup)?.removeView(old)
                return old
            }
        }
        return try {
            host(ctx).createView(ctx, id, info).also {
                it.setPadding(0, 0, 0, 0)
                views[id] = it
            }
        } catch (_: Exception) {
            null
        }
    }

    fun delete(ctx: Context, id: Int) {
        if (id < 0) return
        sizes.remove(id)
        views.remove(id)
        try { host(ctx).deleteAppWidgetId(id) } catch (_: Exception) {}
    }

    /**
     * Tells the widget how big it really is. Many widgets (Google Clock's world clock, weather, calendar…)
     * draw nothing or the wrong layout until the launcher does this.
     */
    @Suppress("DEPRECATION")
    fun tellSize(ctx: Context, hv: AppWidgetHostView, wPx: Int, hPx: Int) {
        val d = ctx.resources.displayMetrics.density
        val w = (wPx / d).toInt()
        val h = (hPx / d).toInt()
        if (w <= 0 || h <= 0) return
        val key = w.toLong() * 100000 + h
        if (sizes[hv.appWidgetId] == key) return
        sizes[hv.appWidgetId] = key
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                hv.updateAppWidgetSize(Bundle(), listOf(SizeF(w.toFloat(), h.toFloat())))
            } else {
                hv.updateAppWidgetSize(null, w, h, w, h)
            }
        } catch (_: Exception) {
        }
    }

    /** Rounded frame that also notices a long press, and passes its size on to the widget inside. */
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
        if (child is AppWidgetHostView) {
            f.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                tellSize(a, child, r - l - v.paddingLeft - v.paddingRight, b - t - v.paddingTop - v.paddingBottom)
            }
        }
        return f
    }

    /** Shown when a widget can't be loaded (its app was removed or updated). */
    fun broken(a: Context, onTap: () -> Unit): View = a.label("⚠  Widget not available\ntap to reload · hold to remove", 13f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onTap() }
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
            val hv = WidgetHost.create(a, id)
            val frame = WidgetHost.frame(a, hv ?: WidgetHost.broken(a) { build() }, glass = Prefs.widgetGlass)
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

    /** Yogi widgets that are drawn by the launcher itself: (name, kind, style, w, h). */
    private fun builtins(): List<Triple<String, String, Int>> = listOf(
        Triple("Big digits clock", "clock", 0),
        Triple("Dot-matrix clock (Nothing)", "clock", 1),
        Triple("Nothing two-line dot clock", "clock", 4),
        Triple("OxygenOS two-tone clock", "clock", 5),
        Triple("World clock", "clock", 6),
        Triple("Flip clock", "clock", 7),
        Triple("Ring clock (Pixel)", "clock", 8),
        Triple("Analog clock", "clock", 2),
        Triple("Stacked clock", "clock", 3),
        Triple("Month calendar", "month", 0),
        Triple("Date tile (iOS)", "date", 0),
        Triple("Battery ring", "battery", 0)
    )

    /** A searchable list: Yogi widgets first, then every Android widget on the phone, grouped by app. */
    fun openPicker(home: Boolean) {
        forHome = home
        val pm = a.packageManager
        val providers = try { WidgetHost.manager(a).installedProviders } catch (_: Exception) { emptyList<AppWidgetProviderInfo>() }
        val groups = providers.groupBy { it.provider.packageName }.map { (pkg, list) ->
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            Triple(name, pkg, list)
        }.sortedBy { it.first.lowercase() }

        val box = a.vbox()
        val search = a.input("🔍  Search widgets")
        box.addView(search.lin(b = a.dp(8)))
        val list = a.vbox()
        val sv = ScrollView(a)
        sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, (a.resources.displayMetrics.heightPixels * 0.62f).toInt()))
        val dlg = AlertDialog.Builder(a)
            .setTitle(if (home) "Add to this home page" else "Add a widget")
            .setView(box.apply { setPadding(a.dp(18), a.dp(6), a.dp(18), 0) })
            .setNegativeButton("Cancel", null)
            .create()
        val previews = ArrayList<Pair<ImageView, AppWidgetProviderInfo>>()

        fun row(title: String, sub: String, preview: AppWidgetProviderInfo?, emoji: String, onPick: () -> Unit): View {
            val r = a.hbox()
            r.background = a.ripple(0xFF2C2C2E.toInt(), 14f)
            r.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
            if (preview != null) {
                val iv = ImageView(a)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                r.addView(iv, LinearLayout.LayoutParams(a.dp(84), a.dp(56)).apply { rightMargin = a.dp(12) })
                previews.add(iv to preview)
            } else {
                r.addView(a.label(emoji, 26f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(a.dp(84), a.dp(56)).apply { rightMargin = a.dp(12) })
            }
            val t = a.vbox()
            t.addView(a.label(title, 15f, Color.WHITE, true))
            if (sub.isNotEmpty()) t.addView(a.label(sub, 12f, C.SUB))
            r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.setOnClickListener {
                dlg.dismiss()
                onPick()
            }
            return r.lin(t = a.dp(3), b = a.dp(3))
        }

        fun fill(q: String) {
            list.removeAllViews()
            previews.clear()
            val ql = q.lowercase()
            if (home) {
                val bs = builtins().filter { ql.isEmpty() || it.first.lowercase().contains(ql) || "yogi".contains(ql) }
                if (bs.isNotEmpty()) list.addView(a.section("YOGI WIDGETS").apply { setPadding(a.dp(4), a.dp(10), 0, a.dp(4)) })
                val emo = mapOf("clock" to "🕐", "month" to "🗓", "date" to "📅", "battery" to "🔋")
                for ((name, kind, style) in bs) list.addView(row(name, "drawn by Yogi Launcher · no battery cost", null, emo[kind] ?: "▦") {
                    a.addBuiltinWidget(kind, style)
                })
            }
            for ((app, _, ws) in groups) {
                val match = ws.filter { info ->
                    ql.isEmpty() || app.lowercase().contains(ql) ||
                        ((try { info.loadLabel(pm) } catch (_: Exception) { null }) ?: "").lowercase().contains(ql)
                }
                if (match.isEmpty()) continue
                list.addView(a.section(app.uppercase()).apply { setPadding(a.dp(4), a.dp(12), 0, a.dp(4)) })
                for (info in match) {
                    val label = (try { info.loadLabel(pm) } catch (_: Exception) { null }) ?: app
                    val cells = "${((info.minWidth + a.dp(30)) / a.dp(74)).coerceIn(1, 5)} × ${((info.minHeight + a.dp(30)) / a.dp(74)).coerceIn(1, 6)}"
                    list.addView(row(label, cells, info, "▦") { choose(info) })
                }
            }
            if (list.childCount == 0) list.addView(a.label("No widgets match", 14f, C.SUB).lin(t = a.dp(20)))
            // previews load in the background so the list opens at once
            val todo = ArrayList(previews)
            val density = a.resources.displayMetrics.densityDpi
            Thread {
                for ((iv, info) in todo) {
                    val d: Drawable? = try { info.loadPreviewImage(a, density) ?: info.loadIcon(a, density) } catch (_: Exception) { null }
                    if (d != null) iv.post { iv.setImageDrawable(d) }
                }
            }.start()
        }
        search.doAfterTextChanged { fill(it?.toString()?.trim() ?: "") }
        fill("")
        dlg.show()
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
        // Android 12+: widgets that can be set up later are added at once (like the Pixel launcher)
        val optional = Build.VERSION.SDK_INT >= 31 &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure != null && !optional) {
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
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)?.takeIf { it >= 0 } ?: pendingId
        val info = pendingInfo ?: try { WidgetHost.manager(a).getAppWidgetInfo(id) } catch (_: Exception) { null }
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
