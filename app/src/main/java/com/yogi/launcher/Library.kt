package com.yogi.launcher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.abs

/**
 * The App Library (swipe up): Personal · Work · Private tabs like the Pixel launcher,
 * iOS-style category boxes or an A–Z list, and search (apps, settings, maths, web).
 */
class Library(private val a: LauncherActivity) {
    /** type 0 header · 1 app · 2 action row · 3 category box · 4 info text · 5 app line (list with names) */
    private class Row(val type: Int, val text: String = "", val app: AppInfo? = null,
                      val apps: List<AppInfo> = emptyList(), val action: (() -> Unit)? = null)

    var isOpen = false
        private set
    private val frame = SwipeFrame(a)
    private val col = a.vbox()
    private val search: EditText = a.input("🔍  Search apps, settings, maths or the web")
    private val list = RecyclerView(a)
    private val letters = LetterBar(a)
    private var rows: List<Row> = emptyList()
    private val cols = Prefs.drawerCols
    /** the grid has 2 spans per app so two category boxes always fill a row */
    private val spans = cols * 2
    private val ios = Prefs.drawerGroup == 3

    companion object {
        const val PERSONAL = 0
        const val WORK = 1
        const val PRIVATE = 2
        const val HIDDEN = 3
        const val LIB = "\u0000lib"
        const val ALL = "\u0000all"
    }

    private var tab = PERSONAL
    /** LIB (boxes), ALL (every app) or a category name */
    private var view = ALL
    /** private tab shown after searching "private" even when hidden in settings */
    private var revealPrivate = false
    private val tabs = a.hbox()
    private val chips = a.hbox()
    private val chipScroll = a.hscroll(chips)
    private val adapter = Adapter()
    private var overlay: View? = null

    init {
        frame.setBackgroundColor(when (Prefs.libraryTint) { 0 -> 0x40101014; 2 -> 0xE6101014.toInt(); else -> 0x9E101014.toInt() })
        frame.isClickable = true
        search.background = rounded(0x33FFFFFF, a.dpf(14f))
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.doAfterTextChanged { rebuild() }
        search.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                rows.firstOrNull { it.type == 1 || it.type == 2 }?.let { r ->
                    if (r.app != null) launch(r.app, null) else r.action?.invoke()
                }
                true
            } else false
        }
        col.addView(search.lin(l = a.dp(16), t = a.dp(4), r = a.dp(16), b = a.dp(6)))
        tabs.background = rounded(0x26FFFFFF, a.dpf(12f))
        tabs.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3))
        col.addView(tabs.lin(l = a.dp(16), r = a.dp(16), b = a.dp(6)))
        chips.setPadding(a.dp(14), 0, a.dp(14), 0)
        col.addView(chipScroll.lin(b = a.dp(2)))
        val body = FrameLayout(a)
        val glm = GridLayoutManager(a, spans)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = when (rows.getOrNull(position)?.type) {
                1 -> 2
                3 -> cols
                5 -> spans
                else -> spans
            }
        }
        list.layoutManager = glm
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.clipToPadding = false
        list.setPadding(a.dp(8), 0, a.dp(22), a.dp(24))
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        letters.onLetter = { l ->
            val pos = if (l == "★") 0 else rows.indexOfFirst { it.type == 0 && it.text == l }
            if (pos >= 0) glm.scrollToPositionWithOffset(pos, 0)
        }
        body.addView(letters, FrameLayout.LayoutParams(a.dp(20), MATCH, Gravity.END))
        col.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.onSwipeDown = { if (overlay == null && !list.canScrollVertically(-1)) close() }
        applyInsets()
    }

    fun applyInsets() {
        col.setPadding(0, a.insetTop + a.dp(10), 0, a.insetBottom)
    }

    private fun startView(): String = if (ios && Prefs.drawerOpen == 0) LIB else ALL

    fun open(focusSearch: Boolean) {
        if (!isOpen) {
            isOpen = true
            tab = PERSONAL
            view = startView()
            revealPrivate = false
            (frame.parent as? ViewGroup)?.removeView(frame)
            a.root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
            frame.translationY = a.resources.displayMetrics.heightPixels * 0.15f
            frame.alpha = 0f
            frame.animate().translationY(0f).alpha(1f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
            a.libraryOpened()
        }
        search.setText("")
        rebuild()
        list.scrollToPosition(0)
        if (focusSearch) {
            search.requestFocus()
            search.postDelayed({
                (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
            }, 150)
        }
    }

    /** Opens straight onto the Private (or Work) tab. */
    fun openTab(t: Int) {
        open(false)
        if (t == PRIVATE) revealPrivate = true
        tab = t
        rebuild()
    }

    fun close() {
        if (!isOpen) return
        closeOverlay()
        isOpen = false
        (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        frame.animate().translationY(a.resources.displayMetrics.heightPixels * 0.12f).alpha(0f).setDuration(170)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .withEndAction { (frame.parent as? ViewGroup)?.removeView(frame) }.start()
        a.libraryClosed()
    }

    /** Back: category popup → category → library → closed. */
    fun back() {
        when {
            overlay != null -> closeOverlay()
            search.text.isNotEmpty() -> search.setText("")
            tab == PERSONAL && view != startView() -> {
                view = startView()
                rebuild()
                list.scrollToPosition(0)
            }
            tab != PERSONAL -> {
                tab = PERSONAL
                rebuild()
            }
            else -> close()
        }
    }

    fun reload() {
        if (isOpen) rebuild()
    }

    /** New notification counts: redraw what is on screen, without rebuilding the list. */
    fun refreshBadges() {
        if (isOpen) adapter.notifyItemRangeChanged(0, rows.size)
    }

    private fun launch(app: AppInfo, v: View?) {
        Apps.launch(a, app, v)
        a.root.postDelayed({ close() }, 250)
    }

    private fun privateVisible(): Boolean = Profiles.privateUser != null && (Prefs.showPrivate || revealPrivate)

    // ---------------- tabs and chips

    private fun drawTabs() {
        tabs.removeAllViews()
        val items = ArrayList<Pair<Int, String>>()
        items.add(PERSONAL to "Personal")
        if (Profiles.workUser != null) items.add(WORK to "💼 Work")
        if (privateVisible()) items.add(PRIVATE to "🔒 Private")
        if (tab == HIDDEN) items.add(HIDDEN to "🙈 Hidden")
        if (items.size < 2 || search.text.isNotEmpty()) {
            tabs.visibility = View.GONE
            if (tab != PERSONAL && items.none { it.first == tab }) tab = PERSONAL
            return
        }
        tabs.visibility = View.VISIBLE
        for ((t, name) in items) {
            val sel = t == tab
            val l = a.label(name, 14f, if (sel) Color.BLACK else Color.WHITE, true)
            l.gravity = Gravity.CENTER
            l.setPadding(0, a.dp(8), 0, a.dp(8))
            if (sel) l.background = rounded(0xF2FFFFFF.toInt(), a.dpf(10f))
            l.setOnClickListener {
                if (tab != t) {
                    tab = t
                    closeOverlay()
                    rebuild()
                    list.scrollToPosition(0)
                }
            }
            tabs.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun drawChips(apps: List<AppInfo>) {
        chips.removeAllViews()
        val show = Prefs.drawerChips && tab == PERSONAL && search.text.isEmpty()
        chipScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val present = apps.map { Apps.drawerCategory(it) }.toSet()
        val cats = ArrayList<String>()
        if (ios) cats.add(LIB)
        cats.add(ALL)
        cats.addAll(Apps.CATEGORY_ORDER.filter { it in present })
        for (c in cats) {
            val name = when (c) {
                LIB -> "▦ Library"
                ALL -> "All  ${apps.size}"
                else -> c + "  " + apps.count { Apps.drawerCategory(it) == c }
            }
            chips.addView(a.chip(name, c == view) {
                view = c
                rebuild()
                list.scrollToPosition(0)
            })
        }
    }

    // ---------------- list content

    private fun addList(out: ArrayList<Row>, apps: List<AppInfo>, lettersOn: Boolean) {
        if (Prefs.drawerGroup == 2) {
            // by icon colour, like a rainbow
            val groups = apps.groupBy { app ->
                val b = Icons.cached(app) ?: Icons.get(a, app)
                Apps.colourGroup(b, app.key)
            }
            for (g in groups.keys.sorted()) {
                out.add(Row(0, "● " + Apps.COLOUR_NAMES[g]))
                groups[g]?.forEach { out.add(Row(1, app = it)) }
            }
            return
        }
        if (!lettersOn) {
            apps.forEach { out.add(Row(1, app = it)) }
            return
        }
        var last = ""
        for (app in apps) {
            val c = app.label.firstOrNull()?.uppercaseChar() ?: '#'
            val l = if (c.isLetter()) c.toString() else "#"
            if (l != last) {
                out.add(Row(0, l))
                last = l
            }
            out.add(Row(1, app = app))
        }
    }

    private fun rebuild() {
        val q = search.text.toString().trim()
        val hidden = Prefs.hidden
        val all = Apps.list.filter { it.key !in hidden }
        val apps = all.filter { it.profile == Profiles.PERSONAL }
        val out = ArrayList<Row>()
        drawTabs()
        drawChips(apps)
        letters.visibility = View.GONE
        if (q.isNotEmpty()) {
            searchRows(out, q, all)
        } else when (tab) {
            WORK -> workRows(out, all)
            PRIVATE -> privateRows(out, all)
            HIDDEN -> {
                val hid = Apps.list.filter { it.key in hidden }
                if (hid.isEmpty()) out.add(Row(4, "🙈\n\nHidden space is empty\nHold an app → Hide app to move it here."))
                addList(out, hid, false)
                out.add(Row(0, "Hidden space"))
                out.add(Row(2, "⚙  Manage hidden apps") {
                    a.startActivity(Intent(a, SettingsActivity::class.java).putExtra("page", "hidden"))
                    close()
                })
            }
            else -> when (view) {
                LIB -> {
                    // iOS-style App Library: a box per category; the list of every app is one tap away
                    out.add(Row(2, "☰   All apps  A–Z") {
                        view = ALL
                        rebuild()
                        list.scrollToPosition(0)
                    })
                    val top = Apps.smart(8)
                    if (top.size >= 2) out.add(Row(3, "Suggestions", apps = top))
                    val recent = apps.sortedByDescending { it.updated }.take(8)
                    if (recent.isNotEmpty()) out.add(Row(3, "Recently updated", apps = recent))
                    val byCat = apps.groupBy { Apps.drawerCategory(it) }
                    for (c in Apps.CATEGORY_ORDER) byCat[c]?.let { out.add(Row(3, c, apps = it)) }
                    if (Profiles.workUser != null) out.add(Row(3, "💼 Work", apps = all.filter { it.profile == Profiles.WORK }))
                    if (privateVisible()) out.add(Row(3, "🔒 Private", apps = all.filter { it.profile == Profiles.PRIVATE }))
                }
                ALL -> {
                    val classic = Prefs.drawerGroup == 1
                    val top = Apps.smart(cols * 2)
                    if (top.isNotEmpty()) {
                        out.add(Row(0, "Suggestions"))
                        top.forEach { out.add(Row(1, app = it)) }
                    }
                    // List style: new & updated apps (last 3 days)
                    if (classic && Prefs.listNew) {
                        val fresh = apps.filter { System.currentTimeMillis() - it.updated < 3 * 86400000L }
                            .sortedByDescending { it.updated }.take(cols)
                        if (fresh.isNotEmpty()) {
                            out.add(Row(0, "New & updated"))
                            fresh.forEach { out.add(Row(1, app = it)) }
                        }
                    }
                    // List style: sort A–Z, most used or recently updated
                    val sort = if (classic) Prefs.listSort else 0
                    val sorted = when (sort) {
                        1 -> apps.sortedByDescending { Apps.launches(it.key) }
                        2 -> apps.sortedByDescending { it.updated }
                        else -> apps
                    }
                    val lettersOn = Prefs.drawerLetters && sort == 0
                    if (out.isNotEmpty()) out.add(Row(0, if (lettersOn) "" else when (sort) {
                        1 -> "Most used"
                        2 -> "Recently updated"
                        else -> "All apps"
                    }))
                    addList(out, sorted, lettersOn)
                    letters.visibility = if (lettersOn && Prefs.drawerGroup != 2) View.VISIBLE else View.GONE
                }
                else -> {
                    out.add(Row(0, view))
                    addList(out, categoryApps(view, apps), false)
                }
            }
        }
        // List style: one app per line with its name (classic Android drawer)
        val lines = Prefs.drawerGroup == 1 && Prefs.listLayout == 1 && q.isEmpty() && tab == PERSONAL && view == ALL
        rows = out.filter { !(it.type == 0 && it.text.isEmpty()) }.map { if (lines && it.type == 1) Row(5, app = it.app) else it }
        adapter.notifyDataSetChanged()
    }

    private fun categoryApps(c: String, apps: List<AppInfo>): List<AppInfo> = when (c) {
        "Suggestions" -> Apps.top(40)
        "Recently updated" -> apps.sortedByDescending { it.updated }.take(30)
        else -> apps.filter { Apps.drawerCategory(it) == c }
    }

    private fun workRows(out: ArrayList<Row>, all: List<AppInfo>) {
        val wu = Profiles.workUser ?: return
        val paused = Profiles.isLocked(a, wu)
        if (paused) {
            out.add(Row(4, "💼\n\nWork apps are paused\nYou won't get notifications from them."))
            out.add(Row(2, "▶  Turn on work apps") { toggle(wu, false) })
        } else {
            addList(out, all.filter { it.profile == Profiles.WORK }, false)
            out.add(Row(2, "⏸  Pause work apps") { toggle(wu, true) })
        }
    }

    private fun privateRows(out: ArrayList<Row>, all: List<AppInfo>) {
        val pu = Profiles.privateUser ?: return
        if (Profiles.isLocked(a, pu)) {
            out.add(Row(4, "🔒\n\nPrivate space is locked\nUnlock with your screen lock to see your private apps."))
            out.add(Row(2, "🔓  Unlock private space") { toggle(pu, false) })
            out.add(Row(2, "⚙  Private space settings") { Profiles.openPrivateSettings(a) })
        } else {
            val mine = all.filter { it.profile == Profiles.PRIVATE }
            if (mine.isEmpty()) out.add(Row(4, "No apps in private space yet"))
            addList(out, mine, false)
            out.add(Row(0, "Private space"))
            out.add(Row(2, "🔒  Lock private space") {
                toggle(pu, true)
                tab = PERSONAL
            })
            out.add(Row(2, "＋  Install apps in private space") { Profiles.installApps(a, pu) })
            out.add(Row(2, "⚙  Private space settings") { Profiles.openPrivateSettings(a) })
        }
    }

    /** Lock / unlock (Android asks for the PIN), then refresh a few times while Android switches the profile. */
    private fun toggle(u: android.os.UserHandle, lock: Boolean) {
        if (!Profiles.setLocked(a, u, lock)) return
        for (t in listOf(700L, 1800L, 4000L)) a.root.postDelayed({ a.reloadApps() }, t)
    }

    private fun searchRows(out: ArrayList<Row>, q: String, all: List<AppInfo>) {
        Calc.eval(q)?.let { v ->
            val txt = if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else String.format(Locale.US, "%.6g", v).trimEnd('0').trimEnd('.')
            out.add(Row(2, "🧮  $q = $txt   (tap to copy)") { a.copy(txt, "Copied $txt") })
        }
        val ql = q.lowercase()
        val pool = all.filter { it.profile != Profiles.PRIVATE || (Prefs.privateInSearch && privateVisible()) }
        val found = pool.mapNotNull { app -> score(app, ql).takeIf { it > 0 }?.let { app to it } }
            .sortedWith(compareByDescending<Pair<AppInfo, Int>> { it.second }.thenByDescending { Apps.launches(it.first.key) })
        if (found.isNotEmpty()) {
            out.add(Row(0, "Apps"))
            found.forEach { out.add(Row(1, app = it.first)) }
        }
        // "private" finds the private space even when it is hidden (like Pixel)
        val pu = Profiles.privateUser
        if (pu != null && ql.length >= 3 && ("private space".startsWith(ql) || ql.startsWith("priv") || ql == "secret")) {
            out.add(Row(0, "Private space"))
            out.add(Row(2, if (Profiles.isLocked(a, pu)) "🔒  Private space · tap to open" else "🔓  Private space · open") {
                search.setText("")
                revealPrivate = true
                tab = PRIVATE
                rebuild()
            })
        }
        if (ql.length >= 3 && ("hidden space".startsWith(ql) || "hidden apps".startsWith(ql))) {
            out.add(Row(0, "Hidden space"))
            out.add(Row(2, "🙈  Open hidden space") {
                search.setText("")
                a.openHidden()
            })
        }
        val sets = SettingsSearch.find(q).filter { it.third != "yogi:private" || pu != null }.filter { it.third != "yogi:work" || Profiles.workUser != null }
        if (sets.isNotEmpty()) {
            out.add(Row(0, "Settings"))
            for ((ic, name, action) in sets) out.add(Row(2, "$ic  $name") { runSetting(action) })
        }
        out.add(Row(0, "More"))
        out.add(Row(2, "🌐  Search the web for “$q”") { web(q) })
        out.add(Row(2, "🛍  Search Play Store for “$q”") {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q))))
        })
        out.add(Row(2, "▶  Search YouTube for “$q”") {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        })
    }

    private fun runSetting(action: String) {
        when (action) {
            "yogi:private" -> {
                search.setText("")
                revealPrivate = true
                tab = PRIVATE
                rebuild()
            }
            "yogi:hidden" -> {
                search.setText("")
                a.openHidden()
            }
            "yogi:work" -> {
                search.setText("")
                tab = WORK
                rebuild()
            }
            "yogi:settings" -> {
                a.startActivity(Intent(a, SettingsActivity::class.java))
                close()
            }
            else -> open(Intent(action))
        }
    }

    /** 4 = name starts with it · 3 = a word starts with it · 2 = initials · 1 = contains. */
    private fun score(app: AppInfo, q: String): Int {
        val l = app.label.lowercase()
        if (l.startsWith(q)) return 4
        val words = l.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 3
        if (q.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(q)) return 2
        if (l.contains(q) || app.pkg.lowercase().contains(q)) return 1
        return 0
    }

    private fun web(q: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        } catch (_: Exception) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            return
        }
        close()
    }

    private fun open(i: Intent) {
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            close()
        } catch (_: Exception) {
            a.toast("No app can open this")
        }
    }

    // ---------------- category popup (iOS App Library folder)

    private fun openBox(title: String, apps: List<AppInfo>) {
        when {
            title == "💼 Work" -> {
                tab = WORK
                rebuild()
                list.scrollToPosition(0)
            }
            title == "🔒 Private" -> {
                tab = PRIVATE
                rebuild()
                list.scrollToPosition(0)
            }
            Prefs.libFolder == 2 -> {
                view = title
                rebuild()
                list.scrollToPosition(0)
            }
            else -> showOverlay(title, if (title == "Suggestions" || title == "Recently updated")
                categoryApps(title, Apps.list.filter { it.profile == Profiles.PERSONAL && it.key !in Prefs.hidden }) else apps)
        }
    }

    private fun appIcon(app: AppInfo): IconView {
        val v = IconView(a)
        v.iconPx = a.iconPx
        v.showLabel = true
        v.item = AppItem(app.key)
        v.label = app.label
        v.icon = Icons.cached(app) ?: Icons.get(a, app)
        v.badge = Badges.count(app.pkg)
        v.onTap = { launch(app, it) }
        v.onLong = { a.showMenu(it, app, true) }
        v.onDrag = {
            closeOverlay()
            a.startDragFromLibrary(it, app)
        }
        return v
    }

    /** Opens a category over the library: a vertical grid, or pages you swipe sideways (HyperOS / iOS folder). */
    private fun showOverlay(title: String, apps: List<AppInfo>) {
        closeOverlay()
        val ov = FrameLayout(a)
        ov.setBackgroundColor(0x99000000.toInt())
        ov.isClickable = true
        ov.setOnClickListener { closeOverlay() }
        val box = a.vbox()
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(a.label(title, 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, 0x88000000.toInt())
        }.lin(WRAP, WRAP, b = a.dp(14)))
        val n = if (apps.size > 16) 4 else if (apps.size > 9) 4 else 3
        val cellH = a.iconPx + a.dp(42)
        val sw = a.resources.displayMetrics.widthPixels
        val panelW = (sw * 0.9f).toInt()
        val panel = FrameLayout(a)
        panel.background = rounded(0x44FFFFFF, a.dpf(Prefs.folderRadius * 1.4f))
        panel.isClickable = true
        panel.setPadding(a.dp(10), a.dp(14), a.dp(10), a.dp(10))
        if (Prefs.libFolder == 1) {
            // horizontal pages of n × n
            val per = n * n
            val pages = (apps.size + per - 1) / per
            val pager = Pager(a)
            pager.firstHome = 0
            val dots = PageDots(a)
            dots.hollowFirst = false
            for (pg in 0 until pages.coerceAtLeast(1)) {
                val g = GridPage(a, n, n)
                for (i in 0 until per) {
                    val app = apps.getOrNull(pg * per + i) ?: break
                    g.addCell(appIcon(app), i)
                }
                pager.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            pager.onPage = {
                dots.current = it
                dots.invalidate()
            }
            dots.count = pager.childCount
            val inner = a.vbox()
            inner.addView(pager, LinearLayout.LayoutParams(MATCH, cellH * n))
            if (pages > 1) inner.addView(dots, LinearLayout.LayoutParams(MATCH, a.dp(18)))
            panel.addView(inner)
            pager.post { pager.setPage(0, false) }
        } else {
            val grid = a.vbox()
            var row: LinearLayout? = null
            for ((i, app) in apps.withIndex()) {
                if (i % n == 0) {
                    row = a.hbox()
                    grid.addView(row, LinearLayout.LayoutParams(MATCH, cellH))
                }
                row?.addView(appIcon(app), LinearLayout.LayoutParams(0, MATCH, 1f))
            }
            val last = row
            if (last != null) while (last.childCount < n) last.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
            val sv = ScrollView(a)
            sv.isVerticalScrollBarEnabled = false
            sv.addView(grid)
            val rowsN = (apps.size + n - 1) / n
            val maxH = (a.resources.displayMetrics.heightPixels * 0.6f).toInt()
            panel.addView(sv, FrameLayout.LayoutParams(MATCH, minOf(maxH, rowsN * cellH)))
        }
        box.addView(panel, LinearLayout.LayoutParams(panelW, WRAP))
        box.addView(a.label("${apps.size} apps · hold an app to add it to Home", 12f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }.lin(t = a.dp(10)))
        ov.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        frame.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        val br = a.blurDp(Prefs.blurFolder)
        if (android.os.Build.VERSION.SDK_INT >= 31 && br > 0f) {
            col.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(a.dpf(br), a.dpf(br), android.graphics.Shader.TileMode.CLAMP))
        }
        box.scaleX = 0.8f
        box.scaleY = 0.8f
        box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
        overlay = ov
    }

    private fun closeOverlay() {
        val ov = overlay ?: return
        overlay = null
        if (android.os.Build.VERSION.SDK_INT >= 31) col.setRenderEffect(null)
        ov.animate().alpha(0f).setDuration(140).withEndAction { frame.removeView(ov) }.start()
    }

    // ---------------- adapter

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = rows.size
        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return when (viewType) {
                5 -> {
                    val r = a.hbox()
                    r.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6))
                    r.background = a.ripple(0x00000000, 16f)
                    val iv = android.widget.ImageView(a)
                    val sz = (a.iconPx * 0.78f).toInt()
                    r.addView(iv, LinearLayout.LayoutParams(sz, sz))
                    val t = a.label("", 16f, Color.WHITE)
                    t.maxLines = 1
                    t.ellipsize = android.text.TextUtils.TruncateAt.END
                    r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.dp(16) })
                    val b = a.label("", 12f, Color.WHITE, true)
                    b.gravity = Gravity.CENTER
                    b.setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2))
                    b.background = rounded(0xFFFF3B30.toInt(), a.dpf(10f))
                    r.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    r.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    Holder(r)
                }
                1 -> {
                    val v = IconView(a)
                    v.iconPx = a.iconPx
                    v.showLabel = true
                    v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.iconPx + a.dp(44))
                    Holder(v)
                }
                3 -> {
                    val v = LibraryBox(a)
                    val w = (a.resources.displayMetrics.widthPixels - a.dp(30)) / 2
                    v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w + a.dp(20))
                    Holder(v)
                }
                0 -> {
                    val t = a.label("", 13f, 0xCCFFFFFF.toInt(), true)
                    t.setPadding(a.dp(12), a.dp(14), a.dp(12), a.dp(4))
                    t.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    Holder(t)
                }
                4 -> {
                    val t = a.label("", 15f, Color.WHITE)
                    t.gravity = Gravity.CENTER
                    t.setPadding(a.dp(20), a.dp(40), a.dp(20), a.dp(20))
                    t.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    Holder(t)
                }
                else -> {
                    val t = a.label("", 15f, Color.WHITE)
                    t.background = a.ripple(0x22FFFFFF, 14f)
                    t.setPadding(a.dp(14), a.dp(13), a.dp(14), a.dp(13))
                    t.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(a.dp(6), a.dp(3), a.dp(6), a.dp(3))
                    }
                    Holder(t)
                }
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = rows[position]
            when (r.type) {
                5 -> {
                    val v = holder.itemView as LinearLayout
                    val app = r.app ?: return
                    val iv = v.getChildAt(0) as android.widget.ImageView
                    iv.setImageBitmap(Icons.cached(app) ?: Icons.get(a, app))
                    (v.getChildAt(1) as android.widget.TextView).text = app.label
                    val n = Badges.count(app.pkg)
                    val b = v.getChildAt(2) as android.widget.TextView
                    b.text = if (n > 99) "99+" else n.toString()
                    b.visibility = if (n > 0 && Prefs.badges != 2) View.VISIBLE else View.GONE
                    v.setOnClickListener { launch(app, iv) }
                    v.setOnLongClickListener {
                        a.showMenu(iv, app, true)
                        true
                    }
                }
                1 -> {
                    val v = holder.itemView as IconView
                    val app = r.app ?: return
                    v.item = AppItem(app.key)
                    v.slot = null
                    v.label = app.label
                    v.icon = Icons.cached(app) ?: Icons.get(a, app)
                    v.badge = Badges.count(app.pkg)
                    v.hiddenForDrag = false
                    v.onTap = { launch(app, it) }
                    v.onLong = { a.showMenu(it, app, true) }
                    v.onDrag = { a.startDragFromLibrary(it, app) }
                    v.invalidate()
                }
                3 -> {
                    val v = holder.itemView as LibraryBox
                    v.title = r.text
                    v.apps = r.apps
                    v.glyph = when {
                        r.text == "🔒 Private" && r.apps.isEmpty() -> "🔒"
                        r.text == "💼 Work" && r.apps.isEmpty() -> "💼"
                        else -> ""
                    }
                    v.onLaunch = { app, view -> launch(app, view) }
                    v.onOpen = { openBox(r.text, r.apps) }
                    v.invalidate()
                }
                0, 4 -> (holder.itemView as TextView).text = r.text
                else -> {
                    val t = holder.itemView as TextView
                    t.text = r.text
                    t.setOnClickListener { r.action?.invoke() }
                }
            }
        }
    }
}

/** Closes the library when you pull down at the top of the list. */
class SwipeFrame(ctx: Context) : FrameLayout(ctx) {
    var onSwipeDown: (() -> Unit)? = null
    private var y0 = 0f
    private var x0 = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                y0 = e.y
                x0 = e.x
                fired = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - y0
                if (!fired && dy > slop * 4 && dy > abs(e.x - x0) * 1.5f) {
                    fired = true
                    onSwipeDown?.invoke()
                }
            }
        }
        return false
    }
}

/** A–Z strip on the right: slide a finger to jump. */
class LetterBar(ctx: Context) : View(ctx) {
    var onLetter: ((String) -> Unit)? = null
    private val letters = listOf("★") + ('A'..'Z').map { it.toString() } + listOf("#")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.density
    }
    private var last = ""

    override fun onDraw(c: Canvas) {
        val step = height / letters.size.toFloat()
        for ((i, l) in letters.withIndex()) c.drawText(l, width / 2f, step * i + step * 0.75f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val i = (e.y / height * letters.size).toInt().coerceIn(0, letters.size - 1)
            val l = letters[i]
            if (l != last) {
                last = l
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onLetter?.invoke(l)
            }
        } else {
            last = ""
        }
        return true
    }
}

/** Tiny calculator for the search box: + − × ÷ % ^ and brackets. */
object Calc {
    private var s = ""
    private var i = 0

    fun eval(input: String): Double? {
        val t = input.replace('×', '*').replace('÷', '/').replace(" ", "").replace(',', '.')
        if (t.isEmpty() || !t.any { it.isDigit() } || !t.any { it in "+-*/%^(" } ) return null
        if (!t.all { it.isDigit() || it in "+-*/%^()." }) return null
        return try {
            synchronized(this) {
                s = t
                i = 0
                val v = expr()
                if (i != s.length || v.isNaN() || v.isInfinite()) null else v
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun expr(): Double {
        var v = term()
        while (peek() == '+' || peek() == '-') {
            val op = s[i++]
            val r = term()
            v = if (op == '+') v + r else v - r
        }
        return v
    }

    private fun term(): Double {
        var v = factor()
        while (peek() == '*' || peek() == '/' || peek() == '%') {
            val op = s[i++]
            val r = factor()
            v = when (op) {
                '*' -> v * r
                '/' -> v / r
                else -> v * r / 100.0   // "200*15%" style → 30
            }
        }
        return v
    }

    private fun factor(): Double {
        val b = unary()
        if (peek() == '^') {
            i++
            return Math.pow(b, factor())
        }
        return b
    }

    private fun unary(): Double {
        if (peek() == '-') {
            i++
            return -unary()
        }
        if (peek() == '+') {
            i++
            return unary()
        }
        return primary()
    }

    private fun primary(): Double {
        if (peek() == '(') {
            i++
            val v = expr()
            if (peek() != ')') throw IllegalArgumentException()
            i++
            return v
        }
        val start = i
        while (peek().isDigit() || peek() == '.') i++
        if (start == i) throw IllegalArgumentException()
        return s.substring(start, i).toDouble()
    }
}


/**
 * iOS-style App Library box: three big icons you can tap, and a mini group that opens the whole category.
 */
class LibraryBox(private val a: LauncherActivity) : View(a) {
    var title = ""
    var apps: List<AppInfo> = emptyList()
    var onLaunch: ((AppInfo, View) -> Unit)? = null
    var onOpen: (() -> Unit)? = null
    /** drawn big in the middle when the box has no apps (locked private space, paused work) */
    var glyph = ""
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setShadowLayer(3f * resources.displayMetrics.density, 0f, 1f, 0x88000000.toInt())
    }
    private val rect = android.graphics.RectF()

    private fun side(): Float = kotlin.math.min(width - 12f * d, height - 26f * d)
    private fun left(): Float = (width - side()) / 2f
    private val top = 4f * resources.displayMetrics.density

    private fun icon(app: AppInfo): android.graphics.Bitmap = Icons.cached(app) ?: Icons.get(a, app)

    override fun onDraw(c: Canvas) {
        val s = side()
        if (s <= 0) return
        val l = left()
        rect.set(l, top, l + s, top + s)
        val rr = s * Prefs.folderRadius / 100f
        c.drawRoundRect(rect, rr, rr, bg)
        if (glyph.isNotEmpty()) {
            text.textSize = s * 0.32f
            c.drawText(glyph, l + s / 2, top + s / 2 + s * 0.11f, text)
            text.textSize = 13f * d
            c.drawText(title, width / 2f, top + s + 17f * d, text)
            return
        }
        val pad = s * 0.1f
        val cell = (s - pad * 3) / 2
        for (q in 0 until 4) {
            val cx = l + pad + (q % 2) * (cell + pad)
            val cy = top + pad + (q / 2) * (cell + pad)
            if (q < 3 || apps.size <= 4) {
                val app = apps.getOrNull(q) ?: continue
                rect.set(cx, cy, cx + cell, cy + cell)
                c.drawBitmap(icon(app), null, rect, bmp)
            } else {
                val gap = cell * 0.1f
                val m = (cell - gap) / 2
                for (k in 0 until kotlin.math.min(4, apps.size - 3)) {
                    val mx = cx + (k % 2) * (m + gap)
                    val my = cy + (k / 2) * (m + gap)
                    rect.set(mx, my, mx + m, my + m)
                    c.drawBitmap(icon(apps[3 + k]), null, rect, bmp)
                }
            }
        }
        text.textSize = 13f * d
        c.drawText(title, width / 2f, top + s + 17f * d, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> alpha = 0.75f
            MotionEvent.ACTION_CANCEL -> alpha = 1f
            MotionEvent.ACTION_UP -> {
                alpha = 1f
                val s = side()
                val lx = e.x - left()
                val ly = e.y - top
                if (lx < 0 || ly < 0 || lx > s || ly > s) {
                    onOpen?.invoke()
                } else {
                    val q = (if (lx > s / 2) 1 else 0) + (if (ly > s / 2) 2 else 0)
                    val app = if (q < 3 || apps.size <= 4) apps.getOrNull(q) else null
                    if (app != null) onLaunch?.invoke(app, this) else onOpen?.invoke()
                }
            }
        }
        return true
    }
}
