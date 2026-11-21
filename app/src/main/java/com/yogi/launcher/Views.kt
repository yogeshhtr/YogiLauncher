package com.yogi.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** One app or folder icon, drawn by hand (fast: a single view, no layouts inside). */
class IconView(ctx: Context) : View(ctx) {
    var item: Item? = null
    var slot: Slot? = null
    var icon: Bitmap? = null
    var minis: List<Bitmap> = emptyList()
    var label = ""
    var showLabel = true
    var iconPx = 0
    /** > 0 count · -1 dot · 0 none */
    var badge = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var hiddenForDrag = false
        set(v) { field = v; invalidate() }

    var onTap: ((IconView) -> Unit)? = null
    var onLong: ((IconView) -> Unit)? = null
    var onDrag: ((IconView) -> Unit)? = null
    var onRemove: ((IconView) -> Unit)? = null

    var editing = false
        set(v) {
            if (field == v) return
            field = v
            jiggle?.cancel()
            jiggle = null
            rotation = 0f
            if (v) {
                jiggle = ObjectAnimator.ofFloat(this, "rotation", -1.6f, 1.6f).apply {
                    duration = 120L + (Math.random() * 40).toLong()
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            invalidate()
        }
    private var jiggle: ObjectAnimator? = null

    private val d = resources.displayMetrics.density
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f * d, 0f, 1f * d, 0x99000000.toInt())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var shape = Path()
    private var shapeFor = -1
    private val rect = RectF()
    private var pressedDown = false
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var dragStarted = false
    private var minusDown = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val longRun = Runnable {
        longFired = true
        pressedDown = false
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!editing) onLong?.invoke(this)
    }

    fun iconLeft(): Float = (width - iconPx) / 2f
    fun iconTop(): Float = if (showLabel) 4f * d else (height - iconPx) / 2f

    /** Draws just the icon (or the folder tile) at 0,0. */
    fun drawIcon(c: Canvas) {
        if (shapeFor != iconPx * 10 + Prefs.shape) {
            shape = Shapes.path(Prefs.shape, iconPx.toFloat())
            shapeFor = iconPx * 10 + Prefs.shape
        }
        if (item is FolderItem) {
            // frosted tile with up to 9 small icons (3 x 3)
            fill.color = 0x55FFFFFF
            c.drawPath(shape, fill)
            val pad = iconPx * 0.14f
            val gap = iconPx * 0.05f
            val cell = (iconPx - 2 * pad - 2 * gap) / 3f
            for ((i, m) in minis.take(9).withIndex()) {
                val cx = pad + (i % 3) * (cell + gap)
                val cy = pad + (i / 3) * (cell + gap)
                rect.set(cx, cy, cx + cell, cy + cell)
                c.drawBitmap(m, null, rect, bmp)
            }
        } else {
            icon?.let {
                rect.set(0f, 0f, iconPx.toFloat(), iconPx.toFloat())
                c.drawBitmap(it, null, rect, bmp)
            }
        }
    }

    /** Picture of the icon, for the drag shadow. */
    fun snapshot(): Bitmap {
        val s = iconPx.coerceAtLeast(1)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        drawIcon(Canvas(b))
        return b
    }

    override fun onDraw(c: Canvas) {
        if (hiddenForDrag || iconPx <= 0) return
        val x = iconLeft()
        val y = iconTop()
        c.save()
        c.translate(x, y)
        drawIcon(c)
        if (pressedDown) {
            fill.color = 0x55000000
            c.drawPath(shape, fill)
        }
        c.restore()

        if (showLabel && label.isNotEmpty()) {
            val t = TextUtils.ellipsize(label, text, width - 4f * d, TextUtils.TruncateAt.END).toString()
            c.drawText(t, width / 2f, y + iconPx + 16f * d, text)
        }
        // badge
        val mode = Prefs.badges
        if (badge != 0 && mode != 2 && !editing) {
            val r = if (mode == 1 || badge < 0) 6f * d else 10f * d
            val bx = x + iconPx - r * 0.55f
            val by = y + r * 0.55f
            fill.color = 0xFFFF3B30.toInt()
            c.drawCircle(bx, by, r, fill)
            if (mode == 0 && badge > 0) {
                badgeText.textSize = 11f * d
                c.drawText(if (badge > 99) "99+" else "$badge", bx, by + 4f * d, badgeText)
            }
        }
        // edit mode: "−" to remove
        if (editing) {
            val r = 10f * d
            val bx = x + r * 0.4f
            val by = y + r * 0.4f
            fill.color = 0xE6505058.toInt()
            c.drawCircle(bx, by, r, fill)
            fill.color = Color.WHITE
            c.drawRect(bx - r * 0.45f, by - 1.2f * d, bx + r * 0.45f, by + 1.2f * d, fill)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longFired = false
                dragStarted = false
                pressedDown = true
                val r = 14f * d
                minusDown = editing && e.x < iconLeft() + r * 1.6f && e.y < iconTop() + r * 1.6f
                postDelayed(longRun, if (editing) 160L else 380L)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dist = hypot(e.x - downX, e.y - downY)
                if (!longFired) {
                    if (dist > slop) {
                        removeCallbacks(longRun)
                        if (pressedDown) {
                            pressedDown = false
                            invalidate()
                        }
                    }
                } else if (!dragStarted && dist > slop) {
                    dragStarted = true
                    onDrag?.invoke(this)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                if (!longFired && pressedDown) {
                    if (minusDown && editing) onRemove?.invoke(this) else onTap?.invoke(this)
                }
                pressedDown = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                pressedDown = false
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        jiggle?.cancel()
        removeCallbacks(longRun)
        super.onDetachedFromWindow()
    }
}

/** A page of the home screen: a grid of cells, plus an optional header (the clock) over the top rows. */
class GridPage(ctx: Context, val cols: Int, val rows: Int) : ViewGroup(ctx) {
    var header: View? = null
        private set
    private var headerRows = 0

    fun setHeader(v: View, rowsUsed: Int) {
        header = v
        headerRows = rowsUsed
        addView(v)
    }

    fun addCell(v: View, index: Int) {
        v.setTag(R.id.cell_index, index)
        addView(v)
    }

    fun cellW(): Int = width / cols
    fun cellH(): Int = height / rows

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = w / cols
        val ch = h / rows
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v === header) {
                v.measure(MeasureSpec.makeMeasureSpec(cw * cols, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch * headerRows, MeasureSpec.EXACTLY))
            } else {
                v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            }
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v === header) {
                v.layout(0, 0, cw * cols, ch * headerRows)
                continue
            }
            val idx = v.getTag(R.id.cell_index) as? Int ?: continue
            val x = (idx % cols) * cw
            val y = (idx / cols) * ch
            v.layout(x, y, x + cw, y + ch)
        }
    }
}

/**
 * Swipes between pages like a phone home screen, with springy edges.
 * Also reports swipe up / swipe down / double tap / long press on empty space.
 */
class Pager(ctx: Context) : FrameLayout(ctx) {
    var current = 0
        private set
    private var offset = 0f
    var firstHome = 1
    var onPage: ((Int) -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = 600f * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var vertical = false
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!tracking && !vertical) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke()
            }
        }
    })

    fun isHome(i: Int = current): Boolean = i >= firstHome

    fun setPage(i: Int, animate: Boolean) {
        val t = i.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        anim?.cancel()
        if (!animate || width == 0) {
            current = t
            offset = 0f
            apply()
            onPage?.invoke(t)
            return
        }
        snapTo(t)
    }

    private fun apply() {
        val w = width.toFloat()
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            v.translationX = (i - current) * w + offset
            v.visibility = if (abs(i - current) <= 1) View.VISIBLE else View.INVISIBLE
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        apply()
    }

    private fun snapTo(t: Int) {
        val w = width.toFloat()
        offset += (t - current) * w
        current = t
        onPage?.invoke(t)
        val from = offset
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                offset = it.animatedValue as Float
                apply()
            }
            start()
        }
    }

    /** Decides if a finger movement is a page swipe or a vertical gesture. */
    private fun decide(e: MotionEvent): Boolean {
        val dx = e.x - startX
        val dy = e.y - startY
        if (!tracking && !vertical && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
            tracking = true
            startX = e.x - offset
            anim?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (!tracking && !vertical && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.3f && isHome()) {
            vertical = true
            if (dy < 0) onSwipeUp?.invoke() else onSwipeDown?.invoke()
            return true
        }
        return tracking || vertical
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x
                startY = e.y
                tracking = false
                vertical = false
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                if (anim?.isRunning == true) {
                    anim?.cancel()
                    tracking = true
                    startX = e.x - offset
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                return decide(e)
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!tracking && !vertical) gestures.onTouchEvent(e)
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x
                startY = e.y
                tracking = false
                vertical = false
            }
            MotionEvent.ACTION_MOVE -> {
                decide(e)
                if (tracking) {
                    var o = e.x - startX
                    if ((current == 0 && o > 0) || (current == childCount - 1 && o < 0)) o *= 0.35f
                    offset = o
                    apply()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    var t = current
                    if (offset < -width * 0.3f || v < -minFling) t = current + 1
                    if (offset > width * 0.3f || v > minFling) t = current - 1
                    snapTo(t.coerceIn(0, childCount - 1))
                }
                tracking = false
                vertical = false
                vt?.recycle()
                vt = null
            }
        }
        return true
    }
}

/** Small dots that show which page is open. The first (widgets) page has a hollow dot. */
class PageDots(ctx: Context) : View(ctx) {
    var count = 0
    var current = 0
    var onTap: (() -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    init {
        setOnClickListener { onTap?.invoke() }
    }

    override fun onDraw(c: Canvas) {
        if (count <= 1) return
        val gap = 14f * d
        val start = width / 2f - (count - 1) * gap / 2f
        for (i in 0 until count) {
            val x = start + i * gap
            p.color = if (i == current) Color.WHITE else 0x77FFFFFF
            if (i == 0) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                c.drawCircle(x, height / 2f, 3f * d, p)
                p.style = Paint.Style.FILL
            } else {
                c.drawCircle(x, height / 2f, 3.5f * d, p)
            }
        }
    }
}

/** Big clock on page 1: time, date, battery and the next alarm. */
class ClockWidget(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f * d, 0f, 1f * d, 0x66000000)
    }
    private val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * d, 0f, 1f * d, 0x66000000)
    }
    private val info = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * d, 0f, 1f * d, 0x66000000)
    }
    private var line3 = ""

    fun refresh() {
        val parts = ArrayList<String>()
        try {
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                if (lvl >= 0 && sc > 0) parts.add((if (plugged) "⚡" else "🔋") + " ${lvl * 100 / sc}%")
            }
        } catch (_: Exception) {
        }
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.nextAlarmClock?.let {
                parts.add("⏰ " + SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it.triggerTime)))
            }
        } catch (_: Exception) {
        }
        line3 = parts.joinToString("   ")
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val now = Date()
        val h = height.toFloat()
        time.textSize = min(h * 0.42f, width * 0.28f)
        date.textSize = 15f * d
        info.textSize = 13f * d
        val tf = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        val cy = h * 0.52f
        c.drawText(SimpleDateFormat(tf, Locale.getDefault()).format(now), width / 2f, cy, time)
        c.drawText(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now), width / 2f, cy + 24f * d, date)
        if (line3.isNotEmpty()) c.drawText(line3, width / 2f, cy + 44f * d, info)
    }
}

/** Frame that notices a long press without stealing the touch from the widget inside. */
class LongPressFrame(ctx: Context) : FrameLayout(ctx) {
    var onLong: (() -> Unit)? = null
    private var x0 = 0f
    private var y0 = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val run = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLong?.invoke()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x
                y0 = e.y
                postDelayed(run, 600)
            }
            MotionEvent.ACTION_MOVE -> if (hypot(e.x - x0, e.y - y0) > slop) removeCallbacks(run)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(run)
        }
        return false
    }
}
