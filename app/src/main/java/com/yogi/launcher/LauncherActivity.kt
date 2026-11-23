package com.yogi.launcher

import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min

/** The home screen. */
class LauncherActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    lateinit var pager: Pager
    private lateinit var dots: PageDots
    private lateinit var dock: LinearLayout
    private lateinit var editBar: LinearLayout
    private lateinit var highlight: View
    private val builtinViews = ArrayList<BuiltinWidget>()
    private val liveViews = ArrayList<IconView>()
    lateinit var widgets: WidgetsPage
    private var library: Library? = null
    private var folderView: View? = null
    private var popup: PopupWindow? = null

    var editing = false
        private set
    var iconPx = 0
        private set
    var insetTop = 0
        private set
    var insetBottom = 0
        private set
    private var ready = false
    private var builtVersion = -1
    private var firstBuild = true
    private val icons = ArrayList<IconView>()

    /** What is being dragged. */
    class DragState(val src: Slot?, val item: Item)
    private var drag: DragState? = null
    private var dropped = false
    private var edgeDir = 0

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { closeAll() }
    }

    private val laCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String?, user: UserHandle?) { reloadApps() }
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) { reloadApps() }
        override fun onPackageChanged(packageName: String?, user: UserHandle?) { reloadApps() }
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { reloadApps() }
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { reloadApps() }
    }

    private lateinit var dim: View
    private var profileState = ""

    /** Private space locked / unlocked, work apps paused / added: reload the app list. */
    private val profileReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { reloadApps() }
    }

    private val minuteTick = object : Runnable {
        override fun run() {
            for (b in builtinViews) b.invalidate()
            for (v in liveViews) v.invalidate()
            val now = System.currentTimeMillis()
            h.postDelayed(this, 60_000L - now % 60_000L + 50)
        }
    }

    // ===================================================================== start

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        WallColors.load(this)
        edgeToEdge()
        computeSizes()
        Home.load()

        root = FrameLayout(this)
        dim = View(this)
        root.addView(dim, FrameLayout.LayoutParams(MATCH, MATCH))
        home = vbox()
        pager = Pager(this)
        dots = PageDots(this)
        dock = hbox()
        widgets = WidgetsPage(this)
        home.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
        home.addView(dots, LinearLayout.LayoutParams(MATCH, dp(22)))
        val dockH = iconPx + dp(26)
        home.addView(dock, LinearLayout.LayoutParams(MATCH, dockH).apply { setMargins(dp(12), dp(2), dp(12), dp(10)) })
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))

        highlight = View(this)
        highlight.visibility = View.GONE
        root.addView(highlight, FrameLayout.LayoutParams(10, 10))

        editBar = hbox()
        editBar.visibility = View.GONE
        editBar.setPadding(dp(14), 0, dp(14), 0)
        editBar.addView(barButton("＋ Widget") { widgets.openPicker(true) })
        editBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        editBar.addView(barButton("⚙ Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        editBar.addView(barButton("Done") { setEdit(false) }.apply { setTextColor(Color.WHITE); background = ripple(C.ACCENT, 16f) })
        root.addView(editBar, FrameLayout.LayoutParams(MATCH, dp(44), Gravity.TOP))

        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins -> onInsets(ins) }

        pager.onPage = { i ->
            dots.current = i
            dots.invalidate()
        }
        pager.onSwipeUp = { openLibrary(false) }
        pager.onSwipeDown = {
            if (Prefs.swipeDown == 1 || !System2.expandNotifications(this)) openLibrary(true)
        }
        pager.onDoubleTap = { doubleTap() }
        pager.onTwoFingerUp = { openHidden() }
        pager.onLongPress = { if (Prefs.longPress == 1 && !Prefs.lockLayout) setEdit(true) else homeMenu() }
        dots.onTap = { openLibrary(true) }

        root.setOnDragListener { _, e -> onDragEvent(e) }
        onBackPressedDispatcher.addCallback(this, back)
        Badges.listener = {
            updateBadges()
            library?.refreshBadges()
        }
        val pf = android.content.IntentFilter()
        for (act in listOf(Intent.ACTION_MANAGED_PROFILE_AVAILABLE, Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
            Intent.ACTION_MANAGED_PROFILE_ADDED, Intent.ACTION_MANAGED_PROFILE_REMOVED,
            "android.intent.action.PROFILE_AVAILABLE", "android.intent.action.PROFILE_UNAVAILABLE",
            "android.intent.action.PROFILE_ADDED", "android.intent.action.PROFILE_REMOVED")) pf.addAction(act)
        try {
            androidx.core.content.ContextCompat.registerReceiver(this, profileReceiver, pf,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Exception) {
        }
        try {
            (getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps).registerCallback(laCallback, h)
        } catch (_: Exception) {
        }
        loadApps()
    }

    override fun onDestroy() {
        Badges.listener = null
        try { unregisterReceiver(profileReceiver) } catch (_: Exception) {}
        try {
            (getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps).unregisterCallback(laCallback)
        } catch (_: Exception) {
        }
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun onInsets(ins: WindowInsets): WindowInsets {
        val changed = insetTop != ins.systemWindowInsetTop || insetBottom != ins.systemWindowInsetBottom
        insetTop = ins.systemWindowInsetTop
        insetBottom = ins.systemWindowInsetBottom
        if (changed) widgets.build()
        home.setPadding(0, insetTop, 0, insetBottom)
        (editBar.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop + dp(4)
        editBar.requestLayout()
        library?.applyInsets()
        return ins
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    /** Space at the left and right of each page. */
    private fun sideMargin(): Int = when (Prefs.margins) { 0 -> 0; 2 -> dp(22); else -> dp(8) }

    private fun computeSizes() {
        val w = resources.displayMetrics.widthPixels - 2 * sideMargin()
        val cellW = w / Prefs.cols
        iconPx = (min(cellW * 0.64f, dp(60).toFloat()) * Prefs.iconScale).toInt()
        Icons.size = iconPx
    }

    private fun barButton(text: String, onClick: () -> Unit) =
        label(text, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = ripple(0x66333336, 16f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
        }.lin(WRAP, WRAP, l = dp(4), r = dp(4))

    // ===================================================================== apps

    private fun loadApps() {
        Thread {
            val apps = try { Apps.load(this) } catch (_: Exception) { emptyList() }
            // icons for the home screen first, so it appears quickly
            for (k in homeKeys()) Apps.get(k)?.let { Icons.get(this, it) }
            runOnUiThread { onAppsLoaded(apps) }
            for (a in apps) Icons.get(this, a)   // the rest for the App Library
        }.start()
    }

    private fun homeKeys(): List<String> {
        val out = ArrayList<String>()
        fun add(it: Item?) {
            when (it) {
                is AppItem -> out.add(it.key)
                is FolderItem -> out.addAll(it.apps.take(12))
                else -> Unit
            }
        }
        Home.pages.take(2).forEach { p -> p.forEach { add(it) } }
        Home.dock.forEach { add(it) }
        return out
    }

    private fun onAppsLoaded(apps: List<AppInfo>) {
        ready = true
        pager.post {
            fitRows()
            if (Home.isEmpty()) {
                Home.firstRun(this, apps)
                h.postDelayed({ welcome() }, 800)
            } else {
                Home.sync(apps.map { it.key }.toSet())
                for (k in Prefs.hidden) Home.removeKey(k)
                Home.compact()
                Home.save()
            }
            builtVersion = Prefs.version
            buildHome()
        }
    }

    /** Works out how many rows fit on this screen. */
    private fun fitRows() {
        val ph = pager.height
        if (ph <= 0) return
        val gap = when (Prefs.spacing) { 0 -> dp(6); 2 -> dp(30); else -> dp(16) }
        val cellH = iconPx + (if (Prefs.labels) dp(Prefs.labelSize + 16) else dp(10)) + gap
        val rows = (ph / cellH).coerceIn(4, 8)
        if (Home.cols != Prefs.cols || Home.rows != rows) Home.resize(Prefs.cols, rows)
    }

    private fun profileSig(): String {
        val pu = Profiles.privateUser
        val wu = Profiles.workUser
        return (if (pu != null) Profiles.isLocked(this, pu).toString() else "-") + (if (wu != null) Profiles.isLocked(this, wu).toString() else "-")
    }

    fun reloadApps() {
        Thread {
            val before = Apps.list.map { it.key }.toSet()
            val apps = try { Apps.load(this) } catch (_: Exception) { return@Thread }
            for (a in apps) Icons.get(this, a)
            runOnUiThread {
                val keys = apps.map { it.key }.toSet()
                if (Prefs.autoAdd && before.isNotEmpty()) {
                    for (k in keys - before) if (!Home.contains(k) && k !in Prefs.hidden && Apps.get(k)?.profile != Profiles.PRIVATE) Home.place(AppItem(k))
                }
                Home.sync(keys)
                Home.compact(editing)
                Home.save()
                profileState = profileSig()
                if (drag == null) buildHome()
                library?.reload()
            }
        }.start()
    }

    // ===================================================================== building the screen

    fun buildHome() {
        if (!ready) return
        val keep = if (firstBuild) pager.firstHome else pager.current
        firstBuild = false
        icons.clear()
        (widgets.view.parent as? ViewGroup)?.removeView(widgets.view)
        pager.removeAllViews()
        pager.addView(widgets.view, FrameLayout.LayoutParams(MATCH, MATCH))
        val showLabels = Prefs.labels
        builtinViews.clear()
        liveViews.clear()
        suggestViews.clear()
        val margin = sideMargin()
        for ((p, page) in Home.pages.withIndex()) {
            val g = GridPage(this, Home.cols, Home.rows)
            g.setPadding(margin, 0, margin, 0)
            for ((i, it) in page.withIndex()) {
                if (it == null) continue
                val v: View = if (it is WidgetItem) {
                    makeWidget(it, Slot(Slot.PAGE, p, i)) ?: continue
                } else {
                    makeIcon(it, Slot(Slot.PAGE, p, i), showLabels) ?: continue
                }
                g.addCell(v, i, it.w, it.h)
            }
            pager.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        dock.removeAllViews()
        dock.background = rounded(WallColors.glass(Prefs.dockAlpha * 255 / 100), dpf(Prefs.dockRadius.toFloat()))
        dock.setPadding(dp(6), 0, dp(6), 0)
        for (i in 0 until Home.DOCK_SIZE) {
            val it = Home.dock[i]
            val v: View = (if (it != null) makeIcon(it, Slot(Slot.DOCK, 0, i), false) else null) ?: View(this)
            dock.addView(v, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        pager.setPage(keep.coerceIn(0, pager.childCount - 1), false)
        dots.count = pager.childCount
        dots.current = pager.current
        dots.invalidate()
        updateBadges()
    }

    private fun makeIcon(item: Item, slot: Slot, showLabel: Boolean): IconView? {
        val v = IconView(this)
        v.item = item
        v.slot = slot
        v.iconPx = iconPx
        v.showLabel = showLabel
        when (item) {
            is AppItem -> {
                val app = Apps.get(item.key) ?: return null
                v.label = app.label
                v.icon = Icons.cached(app) ?: Icons.get(this, app)
                v.live = Apps.liveKind(app)
                if (v.live != 0) liveViews.add(v)
            }
            is FolderItem -> {
                v.label = item.name
                v.minis = item.apps.take(12).mapNotNull { k -> Apps.get(k)?.let { Icons.cached(it) ?: Icons.get(this, it) } }
            }
            is WidgetItem -> return null
        }
        v.onTap = { tapped(it) }
        v.onLong = { showMenu(it) }
        v.onDrag = { startDrag(it) }
        v.onRemove = { removeIcon(it) }
        v.editing = editing
        val d = drag
        if (d != null && d.src == slot) v.hiddenForDrag = true
        if (slot.area != Slot.FOLDER) icons.add(v)
        return v
    }

    private fun tapped(v: IconView) {
        when (val it = v.item) {
            is AppItem -> if (!editing) Apps.get(it.key)?.let { app ->
                Apps.launch(this, app, v)
                if (v.slot?.area == Slot.FOLDER) h.postDelayed({ closeFolder() }, 300)
            }
            is FolderItem -> {
                // large folder: tap an app directly; the last place ("more") opens the folder
                val cell = if (v.isBig && !editing) v.bigCellAt(v.upX, v.upY) else -1
                val direct = cell >= 0 && (cell < 8 || it.apps.size <= 9) && cell < it.apps.size
                val app = if (direct) Apps.get(it.apps[cell]) else null
                if (app != null) Apps.launch(this, app, v) else openFolder(it, v)
            }
            else -> Unit
        }
    }

    fun updateBadges() {
        for (v in icons) {
            v.badge = when (val it = v.item) {
                is AppItem -> Apps.get(it.key)?.let { Badges.count(it.pkg) } ?: 0
                is FolderItem -> it.apps.sumOf { k -> Apps.get(k)?.let { a -> Badges.count(a.pkg) } ?: 0 }
                else -> 0
            }
        }
    }

    // ===================================================================== edit mode

    /** One UI "Lock home screen layout": nothing can be moved or removed. */
    private fun layoutLocked(): Boolean {
        if (!Prefs.lockLayout) return false
        toast("Home screen layout is locked · Settings → Home screen")
        return true
    }

    /** OxygenOS-style Hidden space: asks for the screen lock first (if set), then shows hidden apps. */
    fun openHidden() {
        if (editing) return
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (Prefs.hiddenLock && km.isKeyguardSecure) {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Hidden space", "Unlock to see your hidden apps")
            if (i != null) {
                try {
                    startActivityForResult(i, REQ_HIDDEN)
                    return
                } catch (_: Exception) {
                }
            }
        }
        showHidden()
    }

    private fun showHidden() {
        val lib = library ?: Library(this).also { library = it }
        lib.openTab(Library.HIDDEN)
    }

    private val REQ_HIDDEN = 4300

    fun setEdit(on: Boolean) {
        if (on && !editing && layoutLocked()) return
        if (editing == on) return
        editing = on
        popup?.dismiss()
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) Home.ensureTrailingPage() else Home.compact()
        Home.save()
        buildHome()
    }

    private fun removeIcon(v: IconView) {
        val slot = v.slot ?: return
        when (val it = v.item) {
            is AppItem -> {
                Home.removeAt(slot)
                Home.save()
                buildHome()
            }
            is WidgetItem -> Unit
            is FolderItem -> AlertDialog.Builder(this)
                .setTitle("Ungroup “${it.name}”?")
                .setMessage("The apps go back onto the home screen.")
                .setPositiveButton("Ungroup") { _, _ ->
                    Home.ungroup(it)
                    Home.save()
                    buildHome()
                }
                .setNegativeButton("Cancel", null)
                .show()
            null -> Unit
        }
    }

    // ===================================================================== drag & drop

    private class Shadow(private val v: IconView) : View.DragShadowBuilder(v) {
        private val bmp = v.snapshot()
        private val scale = 1.18f
        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            val s = (bmp.width * scale).toInt().coerceAtLeast(1)
            size.set(s, s)
            touch.set(s / 2, s / 2)
        }
        override fun onDrawShadow(c: Canvas) {
            c.save()
            c.scale(scale, scale)
            c.drawBitmap(bmp, 0f, 0f, null)
            c.restore()
        }
    }

    /** Moves a widget (long-press menu → Move). */
    private fun startWidgetDrag(v: View, item: WidgetItem, slot: Slot) {
        popup?.dismiss()
        drag = DragState(slot, item)
        dropped = false
        @Suppress("DEPRECATION")
        val ok = try {
            val shadow = View.DragShadowBuilder(v)
            if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(null, shadow, drag, View.DRAG_FLAG_OPAQUE)
            else v.startDrag(null, shadow, drag, 0)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            drag = null
            return
        }
        v.alpha = 0.25f
        if (!editing) h.post { setEdit(true) }
    }

    private fun startDrag(v: IconView) {
        val item = v.item ?: return
        if (layoutLocked()) return
        popup?.dismiss()
        drag = DragState(v.slot, item)
        dropped = false
        @Suppress("DEPRECATION")
        val ok = try {
            if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(null, Shadow(v), drag, View.DRAG_FLAG_OPAQUE)
            else v.startDrag(null, Shadow(v), drag, 0)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            drag = null
            return
        }
        v.hiddenForDrag = true
        if (v.slot?.area == Slot.FOLDER) h.post { closeFolder(rebuild = false) }
        if (!editing) h.post { setEdit(true) }
    }

    /** Library icons can be dragged onto the home screen too. */
    fun startDragFromLibrary(v: IconView, app: AppInfo) {
        if (layoutLocked()) return
        if (app.profile == Profiles.PRIVATE) {
            toast("Private apps stay in the App Library")
            return
        }
        v.item = AppItem(app.key)
        v.slot = null
        drag = DragState(null, AppItem(app.key))
        dropped = false
        @Suppress("DEPRECATION")
        val ok = try {
            if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(null, Shadow(v), drag, View.DRAG_FLAG_OPAQUE)
            else v.startDrag(null, Shadow(v), drag, 0)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            drag = null
            return
        }
        h.post {
            library?.close()
            setEdit(true)
        }
    }

    private fun rectIn(v: View): Rect {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val x = a[0] - b[0]
        val y = a[1] - b[1]
        return Rect(x, y, x + v.width, y + v.height)
    }

    /**
     * Which slot is under the finger, the rectangle to highlight, and whether it means "make a folder".
     * Items bigger than one cell are placed with the finger at their centre.
     */
    private fun target(x: Float, y: Float, item: Item?): Triple<Slot, Rect, Boolean>? {
        val iw = item?.w ?: 1
        val ih = item?.h ?: 1
        val dr = rectIn(dock)
        if (dr.contains(x.toInt(), y.toInt())) {
            if (iw > 1 || ih > 1) return null
            val cw = dr.width() / Home.DOCK_SIZE
            val i = ((x - dr.left) / cw).toInt().coerceIn(0, Home.DOCK_SIZE - 1)
            val cell = Rect(dr.left + i * cw, dr.top, dr.left + (i + 1) * cw, dr.bottom)
            val fx = (x - cell.left) / cw
            val merge = Home.dock[i] != null && fx in 0.22f..0.78f
            return Triple(Slot(Slot.DOCK, 0, i), cell, merge)
        }
        if (!pager.isHome()) return null
        val pr = rectIn(pager)
        if (!pr.contains(x.toInt(), y.toInt())) return null
        val m = sideMargin()
        val left = pr.left + m
        val cw = (pr.width() - 2 * m) / Home.cols
        val ch = pr.height() / Home.rows
        if (cw <= 0 || ch <= 0) return null
        val page = pager.current - pager.firstHome
        var col = ((x - left) / cw).toInt().coerceIn(0, Home.cols - 1)
        var row = ((y - pr.top) / ch).toInt().coerceIn(0, Home.rows - 1)
        if (iw > 1 || ih > 1) {
            col = (col - (iw - 1) / 2).coerceIn(0, Home.cols - iw)
            row = (row - (ih - 1) / 2).coerceIn(0, Home.rows - ih)
            val slot = Slot(Slot.PAGE, page, row * Home.cols + col)
            val cell = Rect(left + col * cw, pr.top + row * ch, left + (col + iw) * cw, pr.top + (row + ih) * ch)
            return Triple(slot, cell, false)
        }
        val idx = row * Home.cols + col
        val slot = Slot(Slot.PAGE, page, idx)
        if (!Home.usable(slot)) return null
        // a cell covered by a big item highlights the whole item
        val anchor = Home.anchorAt(page, idx)
        val covering = if (anchor >= 0) Home.pages[page][anchor] else null
        if (covering != null && (covering.w > 1 || covering.h > 1)) {
            val ac = anchor % Home.cols
            val ar = anchor / Home.cols
            val r = Rect(left + ac * cw, pr.top + ar * ch, left + (ac + covering.w) * cw, pr.top + (ar + covering.h) * ch)
            return Triple(slot, r, covering is FolderItem && item is AppItem)
        }
        val cell = Rect(left + col * cw, pr.top + row * ch, left + (col + 1) * cw, pr.top + (row + 1) * ch)
        val fx = (x - cell.left) / cw
        val fy = (y - cell.top) / ch
        val merge = covering != null && item is AppItem && fx in 0.2f..0.8f && fy in 0.05f..0.75f
        return Triple(slot, cell, merge)
    }

    private val edgeRun = Runnable {
        val t = pager.current + edgeDir
        if (edgeDir != 0 && t >= pager.firstHome && t < pager.childCount) pager.setPage(t, true)
        edgeDir = 0
    }

    private fun onDragEvent(e: DragEvent): Boolean {
        val st = e.localState as? DragState
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return st != null
            DragEvent.ACTION_DRAG_LOCATION -> {
                val t = target(e.x, e.y, st?.item)
                if (t == null) {
                    highlight.visibility = View.GONE
                } else {
                    val (_, r, merge) = t
                    val lp = highlight.layoutParams as FrameLayout.LayoutParams
                    val inset = dp(6)
                    lp.width = r.width() - inset * 2
                    lp.height = r.height() - inset * 2
                    lp.leftMargin = r.left + inset
                    lp.topMargin = r.top + inset
                    highlight.layoutParams = lp
                    highlight.background = if (merge) rounded(0x55FFFFFF, dpf(22f)) else rounded(0x00000000, dpf(22f), dp(2), 0x99FFFFFF.toInt())
                    highlight.visibility = View.VISIBLE
                }
                // near the screen edge: go to the next / previous page
                val edge = dp(28)
                val dir = when {
                    e.x < edge -> -1
                    e.x > root.width - edge -> 1
                    else -> 0
                }
                if (dir != edgeDir) {
                    h.removeCallbacks(edgeRun)
                    edgeDir = dir
                    if (dir != 0) h.postDelayed(edgeRun, 550)
                }
                return true
            }
            DragEvent.ACTION_DROP -> {
                if (st == null) return false
                val t = target(e.x, e.y, st.item) ?: return false
                dropped = Home.drop(st.src, st.item, t.first, t.third)
                if (dropped) Home.save()
                return dropped
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                highlight.visibility = View.GONE
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                h.removeCallbacks(edgeRun)
                edgeDir = 0
                highlight.visibility = View.GONE
                drag = null
                if (editing) Home.ensureTrailingPage()
                h.post { buildHome() }
                return true
            }
        }
        return true
    }

    // ===================================================================== long-press menu

    private fun menuRow(box: LinearLayout, icon: String, text: String, color: Int = Color.WHITE, onClick: () -> Unit) {
        val r = hbox()
        r.background = ripple(0x00000000, 12f)
        r.setPadding(dp(14), dp(12), dp(14), dp(12))
        r.addView(label(text, 15f, color).lin(0, WRAP, 1f))
        r.addView(label(icon, 16f, color))
        r.setOnClickListener {
            popup?.dismiss()
            onClick()
        }
        box.addView(r)
    }

    private fun divider(box: LinearLayout) {
        val v = View(this)
        v.setBackgroundColor(0x33FFFFFF)
        box.addView(v, LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(8), dp(2), dp(8), dp(2)) })
    }

    private fun shortcuts(app: AppInfo): List<ShortcutInfo> {
        if (Build.VERSION.SDK_INT < 25) return emptyList()
        return try {
            val la = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            if (!la.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.pkg)
                .setActivity(app.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            (la.getShortcuts(q, app.user) ?: emptyList()).sortedBy { it.rank }.take(4)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Per-app icon: follow the icon pack, the original, a Yogi style or any icon pack that has this app. */
    private fun pickIcon(app: AppInfo) {
        Thread {
            val packs = IconPacks.installed(this).filter { IconPacks.has(this, app, it.first) }
            runOnUiThread {
                val ids = ArrayList<String?>()
                val names = ArrayList<String>()
                ids.add(null); names.add("Follow the icon pack (default)")
                ids.add(""); names.add("Original")
                for (y in listOf(YogiIcons.PACK, YogiIcons.DARK, YogiIcons.LIGHT)) { ids.add(y); names.add(YogiIcons.name(y)) }
                for ((p, n) in packs) { ids.add(p); names.add(n) }
                val cur = AppLook.pack(app.key)
                AlertDialog.Builder(this)
                    .setTitle("Icon for " + app.label)
                    .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(cur).coerceAtLeast(0)) { d, i ->
                        AppLook.setPack(app.key, ids[i])
                        d.dismiss()
                        buildHome()
                        library?.reload()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    /** Per-app name (Home, folders, App Library and search). Empty = the app's own name. */
    private fun renameApp(app: AppInfo) {
        val original = try { app.info.label?.toString() ?: app.label } catch (_: Exception) { app.label }
        val e = input(original, value = AppLook.name(app.key) ?: "")
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(e.apply { setPadding(dp(18), dp(12), dp(18), dp(12)) })
            .setPositiveButton("Save") { _, _ ->
                AppLook.setName(app.key, e.text.toString())
                reloadApps()
            }
            .setNeutralButton("Original name") { _, _ ->
                AppLook.setName(app.key, null)
                reloadApps()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showMenu(v: View, appOverride: AppInfo? = null, fromLibrary: Boolean = false) {
        popup?.dismiss()
        val iv = v as? IconView
        val item = iv?.item
        val box = vbox()
        box.background = rounded(0xF2242426.toInt(), dpf(18f))
        box.setPadding(0, dp(4), 0, dp(4))
        val app = appOverride ?: (item as? AppItem)?.let { Apps.get(it.key) }
        if (app != null) {
            // newest notification of this app
            Badges.notes[app.pkg]?.let { n ->
                if (n.title.isNotEmpty() || n.text.isNotEmpty()) {
                    val nb = vbox()
                    nb.background = ripple(0x22FFFFFF, 12f)
                    nb.setPadding(dp(14), dp(10), dp(14), dp(10))
                    nb.addView(label("🔔 " + n.title, 14f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    if (n.text.isNotEmpty()) nb.addView(label(n.text, 13f, 0xCCFFFFFF.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    nb.setOnClickListener {
                        popup?.dismiss()
                        try { n.intent?.send() } catch (_: Exception) { Apps.launch(this, app, v) }
                    }
                    box.addView(nb.lin(l = dp(6), r = dp(6), b = dp(4)))
                }
            }
            // app shortcuts
            val sc = shortcuts(app)
            if (sc.isNotEmpty() && Build.VERSION.SDK_INT >= 25) {
                val la = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                for (s in sc) {
                    val r = hbox()
                    r.background = ripple(0x00000000, 12f)
                    r.setPadding(dp(14), dp(10), dp(14), dp(10))
                    r.addView(label(s.shortLabel?.toString() ?: "", 15f, Color.WHITE).lin(0, WRAP, 1f))
                    val ic = ImageView(this)
                    try {
                        ic.setImageDrawable(la.getShortcutIconDrawable(s, resources.displayMetrics.densityDpi))
                    } catch (_: Exception) {
                    }
                    r.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
                    r.setOnClickListener {
                        popup?.dismiss()
                        try {
                            la.startShortcut(s, null, null)
                        } catch (_: Exception) {
                            toast("Shortcut not available")
                        }
                    }
                    box.addView(r)
                }
                divider(box)
            }
            if (fromLibrary) {
                if (app.profile != Profiles.PRIVATE) menuRow(box, "＋", "Add to Home Screen") {
                    if (!Home.contains(app.key)) {
                        Home.place(AppItem(app.key))
                        Home.save()
                        buildHome()
                        toast("Added to the home screen")
                    } else toast("Already on the home screen")
                }
            } else {
                menuRow(box, "✎", "Edit Home Screen") { setEdit(true) }
                val slot = iv?.slot
                if (slot != null && !Prefs.lockLayout) {
                    menuRow(box, "⊖", if (slot.area == Slot.FOLDER) "Remove from folder" else "Remove from Home") {
                        if (slot.area == Slot.FOLDER) {
                            Home.removeAt(slot)
                            Home.place(AppItem(app.key))
                            Home.save()
                            closeFolder()
                        } else {
                            Home.removeAt(slot)
                            Home.save()
                            buildHome()
                        }
                    }
                }
            }
            if (app.profile != Profiles.PRIVATE) {
                menuRow(box, "🎨", "Change icon") { pickIcon(app) }
                menuRow(box, "✎", "Rename") { renameApp(app) }
            }
            menuRow(box, "ⓘ", "App info") { Apps.appInfo(this, app) }
            if (app.key in Prefs.hidden) menuRow(box, "👁", "Unhide (back to the App Library)") {
                Store.setSet("hidden", Prefs.hidden - app.key)
                library?.reload()
                toast("Back in the App Library")
            } else menuRow(box, "🙈", "Hide app") {
                Store.setSet("hidden", Prefs.hidden + app.key)
                Home.removeKey(app.key)
                Home.save()
                closeFolder(rebuild = false)
                buildHome()
                library?.reload()
                toast("Moved to Hidden space · two-finger swipe up to open it")
            }
            if (!Apps.isSystem(app)) menuRow(box, "🗑", "Uninstall", 0xFFFF6961.toInt()) { Apps.uninstall(this, app) }
        } else if (item is FolderItem) {
            menuRow(box, "✎", "Rename folder") { renameFolder(item) }
            if (iv?.slot?.area == Slot.PAGE) {
                menuRow(box, if (item.big) "▫" else "▣", if (item.big) "Make small (1 × 1)" else "Make large (2 × 2)") {
                    if (Home.resize(item, if (item.big) 1 else 2, if (item.big) 1 else 2)) {
                        Home.save()
                        buildHome()
                    } else toast("No 2 × 2 space on this page — make room first")
                }
            }
            menuRow(box, "▦", "Edit Home Screen") { setEdit(true) }
            menuRow(box, "⊟", "Ungroup", 0xFFFF6961.toInt()) {
                Home.ungroup(item)
                Home.save()
                buildHome()
            }
        } else {
            return
        }
        val w = dp(250)
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val bh = box.measuredHeight
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val x = (loc[0] + v.width / 2 - w / 2).coerceIn(dp(8), screenW - w - dp(8))
        val above = loc[1] - bh - dp(6)
        val y = if (above > insetTop + dp(8)) above else loc[1] + v.height + dp(6)
        val p = PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.elevation = dpf(12f)
        p.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        p.isOutsideTouchable = true
        popup = p
        try {
            p.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        } catch (_: Exception) {
        }
    }

    private fun renameFolder(f: FolderItem) {
        val inp = input("Folder name", value = f.name)
        AlertDialog.Builder(this)
            .setTitle("Rename folder")
            .setView(padded(inp))
            .setPositiveButton("Save") { _, _ ->
                f.name = inp.text.toString().trim().ifEmpty { f.name }
                Home.save()
                if (folderView != null) {
                    closeFolder(rebuild = false)
                    openFolder(f)
                }
                buildHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===================================================================== folders (large, blurred, easy to tap)

    /** Blur radius in dp for a level 0–4. */
    fun blurDp(level: Int): Float = when (level) { 0 -> 0f; 1 -> 8f; 3 -> 30f; 4 -> 48f; else -> 18f }

    /**
     * Blurs the home screen behind folders / the App Library: our icons with RenderEffect, and on phones that allow it
     * the wallpaper too (window blur-behind, like iOS and HyperOS).
     */
    private var blurNow = 0f
    private var blurAnim: android.animation.ValueAnimator? = null

    private fun blur(on: Boolean, level: Int = Prefs.blurFolder) {
        val r = if (on) blurDp(level) else 0f
        if (Build.VERSION.SDK_INT >= 31) {
            // the blur fades in and out with the folder / library instead of jumping
            blurAnim?.cancel()
            blurAnim = android.animation.ValueAnimator.ofFloat(blurNow, r).apply {
                duration = if (on) 220 else 160
                addUpdateListener {
                    blurNow = it.animatedValue as Float
                    home.setRenderEffect(if (blurNow > 0.5f) RenderEffect.createBlurEffect(dpf(blurNow), dpf(blurNow), Shader.TileMode.CLAMP) else null)
                }
                start()
            }
            home.alpha = if (on && r == 0f) 0.4f else 1f
            try {
                val wm = getSystemService(android.view.WindowManager::class.java)
                if (wm?.isCrossWindowBlurEnabled == true) {
                    val lp = window.attributes
                    if (r > 0f) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        lp.blurBehindRadius = dp(r.toInt())
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        lp.blurBehindRadius = 0
                    }
                    window.attributes = lp
                }
            } catch (_: Throwable) {
            }
        } else {
            home.alpha = if (on) 0.35f else 1f
        }
    }

    /** One UI-style menu when you hold empty space (also the way to Settings while the layout is locked). */
    private fun homeMenu() {
        if (editing) return
        popup?.dismiss()
        val box = vbox()
        box.background = rounded(0xF2242426.toInt(), dpf(18f))
        box.setPadding(0, dp(4), 0, dp(4))
        if (Prefs.lockLayout) {
            menuRow(box, "🔓", "Unlock home screen layout") {
                Store.setBool("lock_layout", false)
                toast("Layout unlocked")
            }
        } else {
            menuRow(box, "✎", "Edit home screen") { setEdit(true) }
            menuRow(box, "＋", "Add a widget") { widgets.openPicker(true) }
        }
        menuRow(box, "🔒", "Lock screen now") { lockNow() }
        menuRow(box, "🖼", "Wallpapers") { startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "wallpaper")) }
        menuRow(box, "⚙", "Launcher settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        val w = dp(250)
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val p = PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.elevation = dpf(12f)
        p.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        p.isOutsideTouchable = true
        popup = p
        try {
            p.showAtLocation(root, Gravity.CENTER, 0, 0)
        } catch (_: Exception) {
        }
    }

    private var folderFrom: View? = null

    @Suppress("UNUSED_PARAMETER")
    private fun folderPanelR(def: Float): Float = dpf(Prefs.folderRadius * 1.5f)

    /** what the open folder looked like when it opened: if nothing changed, closing doesn't rebuild Home */
    private var folderSig = ""
    private var openItem: FolderItem? = null
    private fun sigOf(f: FolderItem?): String = if (f == null) "" else f.name + "|" + f.big + "|" + f.apps.joinToString(",") +
        "|" + (Home.slotOf(f)?.toString() ?: Home.dock.indexOfFirst { it === f }.toString())

    /**
     * Opens a home folder. iOS style: 3 × 3 pages you swipe sideways, zooming out of the folder icon.
     * Scrolling style: one tall grid.
     */
    fun openFolder(f: FolderItem, from: View? = null) {
        popup?.dismiss()
        closeFolder(rebuild = false)
        val ov = FrameLayout(this)
        ov.setBackgroundColor(0x40000000)
        ov.setOnClickListener { closeFolder() }
        val col = vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        val title = label(f.name, 28f, Color.WHITE, true)
        title.gravity = Gravity.CENTER
        title.setShadowLayer(6f, 0f, 1f, 0x88000000.toInt())
        title.setOnClickListener { renameFolder(f) }
        col.addView(title.lin(WRAP, WRAP, b = dp(16)))
        val sw = resources.displayMetrics.widthPixels
        val cellH = iconPx + dp(40)
        if (Prefs.folderStyle == 0) {
            val per = 9
            val pagesN = ((f.apps.size + per - 1) / per).coerceAtLeast(1)
            val fp = Pager(this)
            fp.firstHome = 0
            val fd = PageDots(this)
            fd.hollowFirst = false
            for (pg in 0 until pagesN) {
                val g = GridPage(this, 3, 3)
                for (i in 0 until per) {
                    val idx = pg * per + i
                    val k = f.apps.getOrNull(idx) ?: break
                    val app = Apps.get(k) ?: continue
                    val v = makeIcon(AppItem(k), Slot(Slot.FOLDER, 0, idx, f.id), true) ?: continue
                    v.label = app.label
                    g.addCell(v, i)
                }
                fp.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            fp.onPage = {
                fd.current = it
                fd.invalidate()
            }
            fd.count = pagesN
            val panel = vbox()
            panel.background = rounded(WallColors.glass(0x47), folderPanelR(38f))
            panel.setPadding(dp(12), dp(18), dp(12), dp(8))
            panel.isClickable = true
            panel.addView(fp, LinearLayout.LayoutParams(MATCH, cellH * 3))
            panel.addView(fd, LinearLayout.LayoutParams(MATCH, if (pagesN > 1) dp(20) else dp(6)))
            col.addView(panel, LinearLayout.LayoutParams((sw * 0.8f).toInt(), WRAP))
            fp.post { fp.setPage(0, false) }
        } else {
            val panel = vbox()
            panel.background = rounded(WallColors.glass(0x40), folderPanelR(34f))
            panel.setPadding(dp(14), dp(16), dp(14), dp(10))
            panel.isClickable = true
            val perRow = if (f.apps.size > 12) 4 else 3
            var row: LinearLayout? = null
            for ((i, k) in f.apps.withIndex()) {
                if (i % perRow == 0) {
                    row = hbox()
                    panel.addView(row, LinearLayout.LayoutParams(MATCH, cellH))
                }
                val app = Apps.get(k) ?: continue
                val v = makeIcon(AppItem(k), Slot(Slot.FOLDER, 0, i, f.id), true) ?: continue
                v.label = app.label
                row?.addView(v, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
            val last = row
            if (last != null) while (last.childCount < perRow) last.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val sv = ScrollView(this)
            sv.isVerticalScrollBarEnabled = false
            sv.addView(panel)
            val maxH = (resources.displayMetrics.heightPixels * 0.62f).toInt()
            val rowsCount = (f.apps.size + perRow - 1) / perRow
            val ph = min(maxH, rowsCount * cellH + dp(28))
            col.addView(sv, LinearLayout.LayoutParams((sw * 0.86f).toInt(), ph))
        }
        col.addView(label("Tap the name to rename · hold an app to move it out", 12f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }.lin(t = dp(12)))
        ov.addView(col, FrameLayout.LayoutParams(WRAP, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        blur(true)
        folderView = ov
        folderFrom = from
        openItem = f
        folderSig = sigOf(f)
        // zoom out of the folder icon (iOS / HyperOS)
        col.alpha = 0f
        col.post {
            val src = from?.let { rectIn(it) }
            val dst = rectIn(col)
            if (src != null && dst.width() > 0) {
                col.pivotX = 0f
                col.pivotY = 0f
                val sc = (src.width().toFloat() / dst.width()).coerceIn(0.1f, 1f)
                col.scaleX = sc
                col.scaleY = sc
                col.translationX = (src.left - dst.left).toFloat()
                col.translationY = (src.top - dst.top).toFloat()
            } else {
                col.scaleX = 0.85f
                col.scaleY = 0.85f
            }
            col.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
                .setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_CONTACTS) library?.reload()
    }

    fun askContacts() {
        Store.setBool("contacts_asked", true)
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
    }

    private val REQ_CONTACTS = 4400

    fun closeFolder(rebuild: Boolean = true) {
        val v = folderView ?: return
        folderView = null
        val changed = sigOf(openItem) != folderSig
        openItem = null
        blur(false)
        if (rebuild && drag == null && changed) {
            root.removeView(v)
            buildHome()
        } else {
            // quick fade back into the icon
            v.isClickable = false
            v.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(150)
                .withEndAction { root.removeView(v) }.start()
        }
    }

    // ===================================================================== App Library and search

    fun openLibrary(search: Boolean) {
        popup?.dismiss()
        if (editing) return
        val lib = library ?: Library(this).also { library = it }
        lib.open(search)
    }

    fun libraryClosed() {
        blur(false)
    }

    fun libraryOpened() {
        blur(true, Prefs.blurLibrary)
    }

    // ===================================================================== gestures

    /** Double-tap on empty space: lock the screen (accessibility switch), otherwise explain how. */
    private fun doubleTap() {
        if (!Prefs.doubleTapLock || editing) return
        lockNow()
    }

    /** Locks with accessibility (keeps fingerprint) or device admin; explains what to turn on if neither works. */
    private fun lockNow() {
        if (!Locker.lock(this)) Access.ask(this, true)
    }

    private fun closeAll(): Boolean {
        when {
            popup?.isShowing == true -> popup?.dismiss()
            folderView != null -> closeFolder()
            library?.isOpen == true -> library?.back()
            editing -> setEdit(false)
            ready && pager.current != pager.firstHome -> pager.setPage(pager.firstHome, true)
            else -> return false
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // home button while already home: close everything (the App Library fully, so it opens fresh next time)
        if (library?.isOpen == true) library?.close() else closeAll()
    }

    private fun welcome() {
        if (Store.bool("welcomed")) return
        Store.setBool("welcomed", true)
        val body = vbox()
        body.addView(label("A calm, fast home screen. Swipe up for all apps, swipe down for notifications, " +
            "hold an icon to move it, drop it on another icon to make a folder. Private space and work apps have their own tabs in the App Library.", 14f, C.SUB))
        if (!System2.isDefaultLauncher(this)) {
            body.addView(pill("🏠  Make Yogi Launcher my home screen") { System2.askDefault(this) }.lin(t = dp(14)))
        }
        if (!Badges.allowed(this)) {
            body.addView(pill("🔴  Show notification badges", C.CARD2) { Badges.openSettings(this) }.lin(t = dp(10)))
        }
        sheet("Welcome to Yogi Launcher ✨", body)
    }

    // ===================================================================== lifecycle

    override fun onStart() {
        super.onStart()
        widgets.startListening()
    }

    override fun onStop() {
        widgets.stopListening()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        back.isEnabled = true
        // new wallpaper colours: glass of dock, folders and App Library follows them
        if (WallColors.load(this) && ready) {
            library?.close()
            library = null
            buildHome()
        }
        dim.setBackgroundColor(Color.argb(Prefs.wallDim * 255 / 100, 0, 0, 0))
        // private space / work apps locked or unlocked while we were away (PIN screen, quick settings)
        val ps = profileSig()
        if (ready && ps != profileState) {
            profileState = ps
            reloadApps()
        }
        if (ready && builtVersion != Prefs.version) {
            // settings changed: new sizes, new icon shapes
            builtVersion = Prefs.version
            computeSizes()
            Icons.clearMemory()
            library?.close()
            library = null
            if (Store.bool("reset_layout")) {
                Store.setBool("reset_layout", false)
                Store.setStr("layout", "{}")
                Home.load()
            }
            (dock.layoutParams as LinearLayout.LayoutParams).height = iconPx + dp(26)
            dock.requestLayout()
            pager.post {
                fitRows()
                if (Home.isEmpty()) Home.firstRun(this, Apps.list)
                Home.save()
                buildHome()
            }
        } else if (ready) {
            for (b in builtinViews) b.refresh()
            for (v in liveViews) v.invalidate()
            for (s in suggestViews) s.refresh()
        }
        h.removeCallbacks(minuteTick)
        h.post(minuteTick)
    }

    override fun onPause() {
        popup?.dismiss()
        h.removeCallbacks(minuteTick)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HIDDEN) {
            if (resultCode == RESULT_OK) h.postDelayed({ showHidden() }, 150)
            return
        }
        widgets.onResult(requestCode, resultCode, data)
    }

    // ===================================================================== widgets on home pages

    private val suggestViews = ArrayList<SuggestRow>()

    /** The view of one widget (also used for each card of a smart stack). */
    private fun widgetInner(w: WidgetItem, sw: Int, sh: Int): View = when (w.kind) {
        "app" -> WidgetHost.create(this, w.wid) ?: WidgetHost.broken(this) { buildHome() }
        "suggest" -> SuggestRow(this, sw.coerceAtLeast(2), if (sh >= 2) 2 else 1, iconPx).also {
            it.refresh()
            suggestViews.add(it)
        }
        else -> {
            val b = BuiltinWidget(this, w.kind, w.style)
            b.zones = cities(w)
            b.refresh()
            b.setOnClickListener { openBuiltin(w.kind) }
            builtinViews.add(b)
            b
        }
    }

    private fun makeWidget(w: WidgetItem, slot: Slot): View? {
        val inner: View = if (w.kind == "stack") {
            val st = WidgetStack(this)
            st.setCards(w.kids.map { widgetInner(it, w.sw, w.sh) }, w.style)
            st.onChange = { i ->
                w.style = i
                Home.save()
            }
            st
        } else widgetInner(w, w.sw, w.sh)
        val glass = when (w.kind) {
            "app" -> when (w.style) { 1 -> false; 2 -> true; else -> Prefs.widgetGlass }
            "stack" -> Prefs.widgetGlass || w.kids.any { it.kind == "app" }
            else -> false
        }
        val f = WidgetHost.frame(this, inner, 26f, glass = glass)
        f.setPadding(dp(4), dp(4), dp(4), dp(4))
        f.onLong = { widgetMenu(f, w, slot) }
        f.onDrag = {
            popup?.dismiss()
            if (!layoutLocked()) startWidgetDrag(f, w, slot)
        }
        val d = drag
        if (d != null && d.src == slot) f.alpha = 0.25f
        return f
    }

    private fun openBuiltin(kind: String) {
        val i = when (kind) {
            "date" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis()))
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            else -> Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    /** Built-in cards that can go into a smart stack: (name, kind, style). */
    private val STACKABLE = listOf(
        Triple("Smart suggestions", "suggest", 0),
        Triple("Nothing two-line clock", "clock", 4),
        Triple("OxygenOS two-tone clock", "clock", 5),
        Triple("OxygenOS stacked clock", "clock", 9),
        Triple("HyperOS clock", "clock", 10),
        Triple("iOS lock screen clock", "clock", 11),
        Triple("Word clock", "clock", 12),
        Triple("Day bars clock", "clock", 13),
        Triple("Big digits clock", "clock", 0),
        Triple("World clock", "clock", 6),
        Triple("Analog clock", "clock", 2),
        Triple("Month calendar", "month", 0),
        Triple("Date tile", "date", 0),
        Triple("Battery ring", "battery", 0))

    private fun widgetName(o: WidgetItem): String = when (o.kind) {
        "app" -> (try { WidgetHost.manager(this).getAppWidgetInfo(o.wid)?.loadLabel(packageManager) } catch (_: Exception) { null }) ?: "Android widget"
        "stack" -> "Smart stack"
        "suggest" -> "Suggestions"
        else -> o.kind.replaceFirstChar { it.uppercase() }
    }

    /** Adds a card to a stack (turns a single widget into a stack first). */
    private fun stackAdd(w: WidgetItem, slot: Slot) {
        val others = (Home.pages.getOrNull(slot.page) ?: emptyArray()).filterIsInstance<WidgetItem>().filter { it !== w }
        val names = STACKABLE.map { "＋  " + it.first } + others.map { "⇢  Move in: " + widgetName(it) }
        AlertDialog.Builder(this)
            .setTitle(if (w.kind == "stack") "Add to the stack" else "Make a smart stack")
            .setItems(names.toTypedArray()) { _, i ->
                val stack = if (w.kind == "stack") w else {
                    val s = WidgetItem("stack", -1, w.sw, w.sh, 0)
                    s.kids.add(w)
                    Home.pages[slot.page][slot.index] = s
                    s
                }
                if (i < STACKABLE.size) {
                    val (_, kind, style) = STACKABLE[i]
                    stack.kids.add(WidgetItem(kind, -1, stack.sw, stack.sh, style))
                } else {
                    val o = others[i - STACKABLE.size]
                    Home.slotOf(o)?.let { Home.removeAt(it) }
                    stack.kids.addAll(if (o.kind == "stack") o.kids else listOf(o))
                }
                stack.style = stack.kids.size - 1
                Home.save()
                buildHome()
                toast("Swipe up or down on the stack to flip it")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Removes the card that is shown; a stack of one becomes a normal widget again. */
    private fun stackRemoveCard(w: WidgetItem, slot: Slot) {
        val k = w.kids.getOrNull(w.style) ?: return
        w.kids.remove(k)
        if (k.kind == "app") WidgetHost.delete(this, k.wid)
        when {
            w.kids.isEmpty() -> Home.removeAt(slot)
            w.kids.size == 1 -> {
                val only = w.kids[0]
                only.sw = w.sw
                only.sh = w.sh
                Home.pages[slot.page][slot.index] = only
            }
            else -> w.style = w.style.coerceIn(0, w.kids.size - 1)
        }
        Home.save()
        buildHome()
    }

    private fun widgetMenu(v: View, top: WidgetItem, slot: Slot) {
        val box = vbox()
        box.background = rounded(0xF2242426.toInt(), dpf(18f))
        box.setPadding(0, dp(4), 0, dp(4))
        val isStack = top.kind == "stack"
        // in a stack, the card being shown decides the style options
        val w = if (isStack) top.kids.getOrNull(top.style) ?: top else top
        if (isStack) {
            menuRow(box, "🗂", "Smart stack · " + widgetName(w) + " (${top.style + 1}/${top.kids.size})") {}
            if (!Prefs.lockLayout) {
                menuRow(box, "＋", "Add a card to the stack") { stackAdd(top, slot) }
                menuRow(box, "−", "Remove this card", 0xFFFF6961.toInt()) { stackRemoveCard(top, slot) }
            }
            divider(box)
        }
        if (w.kind == "clock") {
            menuRow(box, "🕐", "Clock style: " + CLOCKS[w.style.coerceIn(0, CLOCKS.size - 1)]) {
                AlertDialog.Builder(this)
                    .setTitle("Clock style")
                    .setSingleChoiceItems(CLOCKS.toTypedArray(), w.style) { d, i ->
                        w.style = i
                        Home.save()
                        buildHome()
                        d.dismiss()
                    }
                    .show()
            }
            if (w.style == 6) menuRow(box, "🌍", "Choose cities") { pickCities(w) }
            divider(box)
        }
        if (w.kind == "app") {
            if (!isStack) menuRow(box, "◐", "Background: " + listOf("default", "none", "glass")[w.style.coerceIn(0, 2)]) {
                w.style = (w.style + 1) % 3
                Home.save()
                buildHome()
            }
            val info = try { WidgetHost.manager(this).getAppWidgetInfo(w.wid) } catch (_: Exception) { null }
            if (info?.configure != null) menuRow(box, "⚙", "Widget settings") {
                try {
                    WidgetHost.host(this).startAppWidgetConfigureActivityForResult(this, w.wid, 0, 4199, null)
                } catch (_: Exception) {
                    toast("This widget has no settings")
                }
            }
            divider(box)
        }
        if (!isStack && !Prefs.lockLayout) {
            menuRow(box, "🗂", "Make a smart stack (swipe to flip)") { stackAdd(top, slot) }
            divider(box)
        }
        val sizes = when (top.kind) {
            "suggest" -> listOf(Home.cols to 1, Home.cols to 2)
            "date", "battery" -> listOf(2 to 2, 1 to 1, 2 to 1)
            "month" -> listOf(Home.cols to 3, 2 to 2, Home.cols to 4, (Home.cols - 1).coerceAtLeast(2) to 3)
            "clock" -> listOf(Home.cols to 2, Home.cols to 3, 2 to 2, Home.cols to 1)
            else -> listOf(Home.cols to 2, Home.cols to 3, 2 to 2, Home.cols to 1, Home.cols to 4)
        }
        for ((sw, sh) in sizes) {
            if (sw == top.sw && sh == top.sh) continue
            menuRow(box, "⤢", "Size $sw × $sh") {
                if (Home.resize(top, sw, sh)) {
                    Home.save()
                    buildHome()
                } else toast("Not enough room on this page")
            }
        }
        divider(box)
        menuRow(box, "✥", "Move: hold the widget and drag") { toast("Hold the widget, then drag it where you want") }
        if (!Prefs.lockLayout) menuRow(box, "🗑", if (isStack) "Remove the whole stack" else "Remove widget", 0xFFFF6961.toInt()) {
            Home.removeAt(slot)
            for (x in top.all()) if (x.kind == "app") WidgetHost.delete(this, x.wid)
            Home.save()
            buildHome()
        }
        val pw = dp(240)
        box.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val sw = resources.displayMetrics.widthPixels
        val x = (loc[0] + v.width / 2 - pw / 2).coerceIn(dp(8), sw - pw - dp(8))
        val below = loc[1] + v.height + dp(6)
        val y = if (below + box.measuredHeight < resources.displayMetrics.heightPixels - insetBottom) below
        else (loc[1] - box.measuredHeight - dp(6)).coerceAtLeast(insetTop + dp(8))
        val p = PopupWindow(box, pw, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.elevation = dpf(12f)
        p.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        p.isOutsideTouchable = true
        popup = p
        try {
            p.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        } catch (_: Exception) {
        }
    }

    private fun homePage(): Int = (pager.current - pager.firstHome).coerceAtLeast(0)

    private val CLOCKS = listOf("Big digits", "Dot-matrix (Nothing)", "Analog", "Stacked", "Nothing two-line",
        "OxygenOS two-tone", "World clock", "Flip", "Ring (Pixel)", "OxygenOS stacked", "HyperOS", "iOS lock screen",
        "Word clock", "Day bars (Nothing)")

    private fun cities(w: WidgetItem): List<String> =
        Store.str("wc_" + w.id, "Europe/London,America/New_York,Asia/Tokyo").split(',').filter { it.isNotBlank() }

    /** World clock: pick up to three cities. */
    private fun pickCities(w: WidgetItem) {
        val ids = java.util.TimeZone.getAvailableIDs().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV") }.sorted()
        val names = ids.map { it.substringAfterLast('/').replace('_', ' ') + "  ·  " + it.substringBefore('/') }
        val chosen = cities(w).toMutableList()
        val box = vbox()
        val search = input("🔍  Search a city")
        box.addView(search)
        val listBox = vbox()
        val sv = ScrollView(this)
        sv.addView(listBox)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, (resources.displayMetrics.heightPixels * 0.5f).toInt()))
        val status = label("", 13f, C.SUB)
        box.addView(status.lin(t = dp(6)))
        fun fill(q: String) {
            listBox.removeAllViews()
            status.text = "Chosen: " + chosen.joinToString(", ") { it.substringAfterLast('/').replace('_', ' ') } + "  (max 3)"
            val ql = q.lowercase()
            var n = 0
            for ((i, id) in ids.withIndex()) {
                if (ql.isNotEmpty() && !names[i].lowercase().contains(ql)) continue
                if (++n > 120) break
                val on = id in chosen
                val r = label((if (on) "✅  " else "○  ") + names[i], 15f, C.TEXT)
                r.setPadding(dp(6), dp(10), dp(6), dp(10))
                r.setOnClickListener {
                    if (on) chosen.remove(id) else {
                        if (chosen.size >= 3) chosen.removeAt(0)
                        chosen.add(id)
                    }
                    fill(search.text.toString())
                }
                listBox.addView(r)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { fill(e?.toString() ?: "") }
        })
        fill("")
        AlertDialog.Builder(this)
            .setTitle("World clock cities")
            .setView(box.apply { setPadding(dp(18), dp(6), dp(18), 0) })
            .setPositiveButton("Save") { _, _ ->
                Store.setStr("wc_" + w.id, chosen.joinToString(","))
                buildHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun addBuiltinWidget(kind: String, style: Int) {
        val (w, hgt) = when (kind) {
            "clock", "stack" -> Home.cols to 2
            "month" -> Home.cols to 3
            "suggest" -> Home.cols to 1
            else -> 2 to 2
        }
        val item = WidgetItem(kind, -1, w, hgt, style)
        if (kind == "stack") {
            // a ready-made smart stack: clock, suggestions, calendar, battery
            item.kids.add(WidgetItem("clock", -1, w, hgt, 4))
            item.kids.add(WidgetItem("suggest", -1, w, hgt, 0))
            item.kids.add(WidgetItem("month", -1, w, hgt, 0))
            item.kids.add(WidgetItem("battery", -1, w, hgt, 0))
        }
        Home.place(item, homePage())
        Home.save()
        buildHome()
        Home.slotOf(item)?.let { pager.setPage(it.page + pager.firstHome, true) }
    }

    fun addAppWidget(id: Int, info: android.appwidget.AppWidgetProviderInfo?) {
        val pr = rectIn(pager)
        val cw = ((pr.width() - 2 * sideMargin()) / Home.cols).coerceAtLeast(1)
        val ch = (pr.height() / Home.rows).coerceAtLeast(1)
        val w = if (info == null) Home.cols else ((info.minWidth + dp(16)) / cw + 1).coerceIn(1, Home.cols)
        val hgt = if (info == null) 2 else ((info.minHeight + dp(16)) / ch + 1).coerceIn(1, Home.rows)
        val item = WidgetItem("app", id, w, hgt)
        Home.place(item, homePage())
        Home.save()
        buildHome()
        Home.slotOf(item)?.let { pager.setPage(it.page + pager.firstHome, true) }
    }
}
