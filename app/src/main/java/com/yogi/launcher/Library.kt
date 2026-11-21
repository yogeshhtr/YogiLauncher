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
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.abs

/** All apps A–Z with search (apps, maths, web) — opens with a swipe up. */
class Library(private val a: LauncherActivity) {
    private class Row(val type: Int, val text: String = "", val app: AppInfo? = null, val action: (() -> Unit)? = null)

    var isOpen = false
        private set
    private val frame = SwipeFrame(a)
    private val col = a.vbox()
    private val search: EditText = a.input("🔍  Search apps, maths or the web")
    private val list = RecyclerView(a)
    private val letters = LetterBar(a)
    private var rows: List<Row> = emptyList()
    private val cols = Prefs.cols
    private val adapter = Adapter()

    init {
        frame.setBackgroundColor(0xB3101014.toInt())
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
        col.addView(search.lin(l = a.dp(16), t = a.dp(4), r = a.dp(16), b = a.dp(8)))
        val body = FrameLayout(a)
        val glm = GridLayoutManager(a, cols)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (rows.getOrNull(position)?.type == 1) 1 else cols
        }
        list.layoutManager = glm
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.clipToPadding = false
        list.setPadding(a.dp(8), 0, a.dp(22), a.dp(24))
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        letters.onLetter = { l ->
            val pos = rows.indexOfFirst { it.type == 0 && it.text == l }
            if (pos >= 0) glm.scrollToPositionWithOffset(pos, 0)
        }
        body.addView(letters, FrameLayout.LayoutParams(a.dp(20), MATCH, Gravity.END))
        col.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.onSwipeDown = { if (!list.canScrollVertically(-1)) close() }
        applyInsets()
    }

    fun applyInsets() {
        col.setPadding(0, a.insetTop + a.dp(10), 0, a.insetBottom)
    }

    fun open(focusSearch: Boolean) {
        if (!isOpen) {
            isOpen = true
            (frame.parent as? ViewGroup)?.removeView(frame)
            a.root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
            frame.translationY = a.resources.displayMetrics.heightPixels * 0.15f
            frame.alpha = 0f
            frame.animate().translationY(0f).alpha(1f).setDuration(240).start()
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

    fun close() {
        if (!isOpen) return
        isOpen = false
        (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        frame.animate().translationY(a.resources.displayMetrics.heightPixels * 0.12f).alpha(0f).setDuration(180)
            .withEndAction { (frame.parent as? ViewGroup)?.removeView(frame) }.start()
        a.libraryClosed()
    }

    fun reload() {
        if (isOpen) rebuild()
    }

    private fun launch(app: AppInfo, v: View?) {
        Apps.launch(a, app, v)
        a.root.postDelayed({ close() }, 250)
    }

    // ---------------- list content

    private fun rebuild() {
        val q = search.text.toString().trim()
        val hidden = Prefs.hidden
        val apps = Apps.list.filter { it.key !in hidden }
        val out = ArrayList<Row>()
        if (q.isEmpty()) {
            val top = Apps.top(cols * 2)
            if (top.isNotEmpty()) {
                out.add(Row(0, "★"))
                top.forEach { out.add(Row(1, app = it)) }
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
            letters.visibility = View.VISIBLE
        } else {
            letters.visibility = View.GONE
            Calc.eval(q)?.let { v ->
                val txt = if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else String.format(Locale.US, "%.6g", v).trimEnd('0').trimEnd('.')
                out.add(Row(2, "🧮  $q = $txt   (tap to copy)") { a.copy(txt, "Copied $txt") })
            }
            val ql = q.lowercase()
            val found = apps.mapNotNull { app -> score(app, ql).takeIf { it > 0 }?.let { app to it } }
                .sortedWith(compareByDescending<Pair<AppInfo, Int>> { it.second }.thenByDescending { Apps.launches(it.first.key) })
            if (found.isNotEmpty()) {
                out.add(Row(0, "Apps"))
                found.forEach { out.add(Row(1, app = it.first)) }
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
        rows = out
        adapter.notifyDataSetChanged()
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

    // ---------------- adapter

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = rows.size
        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return when (viewType) {
                1 -> {
                    val v = IconView(a)
                    v.iconPx = a.iconPx
                    v.showLabel = true
                    v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.iconPx + a.dp(44))
                    Holder(v)
                }
                0 -> {
                    val t = a.label("", 13f, 0xCCFFFFFF.toInt(), true)
                    t.setPadding(a.dp(12), a.dp(14), a.dp(12), a.dp(4))
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
                0 -> (holder.itemView as TextView).text = if (r.text == "★") "Suggestions" else r.text
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
