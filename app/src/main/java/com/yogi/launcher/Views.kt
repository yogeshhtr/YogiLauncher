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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One app or folder icon, drawn by hand (fast: a single view, no layouts inside). */
class IconView(ctx: Context) : View(ctx) {
    var item: Item? = null
    var slot: Slot? = null
    var icon: Bitmap? = null
    var minis: List<Bitmap> = emptyList()
    var label = ""
    var showLabel = true
    var iconPx = 0
    /** 1 = live clock icon · 2 = live calendar icon */
    var live = 0
    /** > 0 count · -1 dot · 0 none */
    var badge = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var hiddenForDrag = false
        set(v) { field = v; invalidate() }
    /** where the finger was lifted (for large folders: which app was tapped) */
    var upX = 0f
        private set
    var upY = 0f
        private set

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
                jiggle = ObjectAnimator.ofFloat(this, "rotation", if (isBig) -0.8f else -1.6f, if (isBig) 0.8f else 1.6f).apply {
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
        textAlign = Paint.Align.CENTER
        typeface = Prefs.typeface()
        textSize = Prefs.labelSize * d
        if (Prefs.labelDark) {
            color = 0xFF111111.toInt()
        } else {
            color = Color.WHITE
            setShadowLayer(3f * d, 0f, 1f * d, 0x99000000.toInt())
        }
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

    /** A large (2 × 2) folder. */
    val isBig: Boolean get() = (item as? FolderItem)?.big == true && slot?.area == Slot.PAGE

    private fun labelSpace(): Float = if (showLabel) Prefs.labelSize * d + 10f * d else 0f

    /** Size of the drawn tile (icon, or the whole large folder). */
    fun boxSize(): Int = if (isBig) {
        (min(width - 14f * d, height - labelSpace() - 10f * d)).toInt().coerceAtLeast(iconPx)
    } else iconPx

    fun iconLeft(): Float = (width - boxSize()) / 2f
    fun iconTop(): Float = if (showLabel) (if (isBig) 6f * d else 4f * d) else (height - boxSize()) / 2f

    /** For large folders: which of the 3 × 3 places is at (x, y); 8 = "more". -1 = none. */
    fun bigCellAt(x: Float, y: Float): Int {
        val s = boxSize()
        val lx = x - iconLeft()
        val ly = y - iconTop()
        if (lx < 0 || ly < 0 || lx > s || ly > s) return -1
        val c = (lx / (s / 3f)).toInt().coerceIn(0, 2)
        val r = (ly / (s / 3f)).toInt().coerceIn(0, 2)
        return r * 3 + c
    }

    /** Draws just the icon (or the folder tile) at 0,0. */
    fun drawIcon(c: Canvas) {
        val s = boxSize()
        if (shapeFor != s * 10 + Prefs.shape) {
            shape = if (isBig) Path().apply { addRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), s * 0.16f, s * 0.16f, Path.Direction.CW) }
            else Shapes.path(Prefs.shape, s.toFloat())
            shapeFor = s * 10 + Prefs.shape
        }
        if (item is FolderItem) {
            fill.color = 0x55FFFFFF
            c.drawPath(shape, fill)
            if (isBig) {
                // HyperOS style: 3 × 3 real-size icons you can tap
                val cell = s / 3f
                val ic = cell * 0.72f
                val all = minis
                val showMore = all.size > 9
                val n = if (showMore) 8 else min(9, all.size)
                for (i in 0 until n) {
                    val cx = (i % 3) * cell + (cell - ic) / 2
                    val cy = (i / 3) * cell + (cell - ic) / 2
                    rect.set(cx, cy, cx + ic, cy + ic)
                    c.drawBitmap(all[i], null, rect, bmp)
                }
                if (showMore) {
                    // last place: a mini 2 × 2 of the rest
                    val ox = 2 * cell + (cell - ic) / 2
                    val oy = 2 * cell + (cell - ic) / 2
                    fill.color = 0x33FFFFFF
                    c.drawRoundRect(RectF(ox, oy, ox + ic, oy + ic), ic * 0.25f, ic * 0.25f, fill)
                    val m = ic * 0.42f
                    for (k in 0 until min(4, all.size - 8)) {
                        val mx = ox + ic * 0.06f + (k % 2) * (m + ic * 0.04f)
                        val my = oy + ic * 0.06f + (k / 2) * (m + ic * 0.04f)
                        rect.set(mx, my, mx + m, my + m)
                        c.drawBitmap(all[8 + k], null, rect, bmp)
                    }
                }
            } else {
                val n = if (Prefs.folderPreview == 1) 2 else 3
                val pad = s * (if (n == 2) 0.16f else 0.14f)
                val gap = s * (if (n == 2) 0.07f else 0.05f)
                val cell = (s - 2 * pad - (n - 1) * gap) / n
                for ((i, m) in minis.take(n * n).withIndex()) {
                    val cx = pad + (i % n) * (cell + gap)
                    val cy = pad + (i / n) * (cell + gap)
                    rect.set(cx, cy, cx + cell, cy + cell)
                    c.drawBitmap(m, null, rect, bmp)
                }
            }
            return
        }
        when (live) {
            1 -> LiveIcons.clock(c, s.toFloat(), shape, fill)
            2 -> LiveIcons.calendar(c, s.toFloat(), shape, fill)
            else -> icon?.let {
                rect.set(0f, 0f, s.toFloat(), s.toFloat())
                c.drawBitmap(it, null, rect, bmp)
            }
        }
    }

    /** Picture of the icon, for the drag shadow. */
    fun snapshot(): Bitmap {
        val s = boxSize().coerceAtLeast(1)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        drawIcon(Canvas(b))
        return b
    }

    override fun onDraw(c: Canvas) {
        if (hiddenForDrag || iconPx <= 0) return
        val x = iconLeft()
        val y = iconTop()
        val s = boxSize()
        c.save()
        c.translate(x, y)
        drawIcon(c)
        if (pressedDown && !isBig) {
            fill.color = 0x55000000
            c.drawPath(shape, fill)
        }
        c.restore()

        if (showLabel && label.isNotEmpty()) {
            val t = TextUtils.ellipsize(label, text, width - 4f * d, TextUtils.TruncateAt.END).toString()
            c.drawText(t, width / 2f, y + s + text.textSize + 4f * d, text)
        }
        val mode = Prefs.badges
        if (badge != 0 && mode != 2 && !editing) {
            val r = if (mode == 1 || badge < 0) 6f * d else 10f * d
            val bx = x + s - r * 0.55f
            val by = y + r * 0.55f
            fill.color = 0xFFFF3B30.toInt()
            c.drawCircle(bx, by, r, fill)
            if (mode == 0 && badge > 0) {
                badgeText.textSize = 11f * d
                c.drawText(if (badge > 99) "99+" else "$badge", bx, by + 4f * d, badgeText)
            }
        }
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
                upX = e.x
                upY = e.y
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

/** Live app icons: a clock that shows the real time, a calendar that shows today. */
object LiveIcons {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun clock(c: Canvas, s: Float, shape: Path, fill: Paint) {
        fill.color = Color.WHITE
        c.drawPath(shape, fill)
        val cx = s / 2
        val cy = s / 2
        val r = s * 0.42f
        p.style = Paint.Style.FILL
        p.color = 0xFF1C1C1E.toInt()
        c.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val len = if (i % 3 == 0) r * 0.16f else r * 0.08f
            p.strokeWidth = if (i % 3 == 0) s * 0.025f else s * 0.014f
            c.drawLine(cx + (sin(a) * (r - len)).toFloat(), cy - (cos(a) * (r - len)).toFloat(),
                cx + (sin(a) * r * 0.94).toFloat(), cy - (cos(a) * r * 0.94).toFloat(), p)
        }
        val cal = java.util.Calendar.getInstance()
        val min = cal.get(java.util.Calendar.MINUTE)
        val hr = cal.get(java.util.Calendar.HOUR) + min / 60.0
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.045f
        val ha = Math.toRadians(hr * 30.0)
        c.drawLine(cx, cy, cx + (sin(ha) * r * 0.5).toFloat(), cy - (cos(ha) * r * 0.5).toFloat(), p)
        p.strokeWidth = s * 0.032f
        val ma = Math.toRadians(min * 6.0)
        c.drawLine(cx, cy, cx + (sin(ma) * r * 0.78).toFloat(), cy - (cos(ma) * r * 0.78).toFloat(), p)
        p.color = 0xFFFF9F0A.toInt()
        c.drawCircle(cx, cy, s * 0.035f, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    fun calendar(c: Canvas, s: Float, shape: Path, fill: Paint) {
        fill.color = Color.WHITE
        c.drawPath(shape, fill)
        val cal = java.util.Calendar.getInstance()
        val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.time).uppercase()
        t.color = 0xFFFF3B30.toInt()
        t.typeface = Typeface.DEFAULT_BOLD
        t.textSize = s * 0.17f
        c.drawText(day, s / 2, s * 0.3f, t)
        t.color = 0xFF1C1C1E.toInt()
        t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        t.textSize = s * 0.5f
        c.drawText(cal.get(java.util.Calendar.DAY_OF_MONTH).toString(), s / 2, s * 0.8f, t)
    }
}

/** A page of the home screen: a grid of cells. Children can cover several cells. */
class GridPage(ctx: Context, val cols: Int, val rows: Int) : ViewGroup(ctx) {

    fun addCell(v: View, index: Int, w: Int = 1, h: Int = 1) {
        v.setTag(R.id.cell_index, index)
        v.setTag(R.id.cell_span, intArrayOf(w, h))
        addView(v)
    }

    private fun cw(): Int = (width - paddingLeft - paddingRight) / cols
    private fun ch(): Int = (height - paddingTop - paddingBottom) / rows

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = (w - paddingLeft - paddingRight) / cols
        val ch = (h - paddingTop - paddingBottom) / rows
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val span = v.getTag(R.id.cell_span) as? IntArray ?: intArrayOf(1, 1)
            v.measure(MeasureSpec.makeMeasureSpec(cw * span[0], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch * span[1], MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cw()
        val ch = ch()
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val idx = v.getTag(R.id.cell_index) as? Int ?: continue
            val span = v.getTag(R.id.cell_span) as? IntArray ?: intArrayOf(1, 1)
            val x = paddingLeft + (idx % cols) * cw
            val y = paddingTop + (idx / cols) * ch
            v.layout(x, y, x + cw * span[0], y + ch * span[1])
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
    /** two fingers swiped up (OxygenOS hidden space) */
    var onTwoFingerUp: (() -> Unit)? = null
    private var twoY = -1f
    private var twoFired = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoY = (e.getY(0) + e.getY(1)) / 2
                twoFired = false
            }
            MotionEvent.ACTION_MOVE -> if (twoY >= 0 && e.pointerCount >= 2 && !twoFired) {
                val y = (e.getY(0) + e.getY(1)) / 2
                if (twoY - y > slop * 5) {
                    twoFired = true
                    onTwoFingerUp?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> twoY = -1f
        }
        return super.dispatchTouchEvent(e)
    }

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
        if (!tracking && !vertical && e.pointerCount == 1 && twoY < 0 && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.3f && isHome()) {
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
    /** the first page is the widgets page: hollow dot */
    var hollowFirst = true
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
            if (i == 0 && hollowFirst) {
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

/**
 * Built-in widgets: clock (big · dot-matrix · analog · stacked), date and battery.
 * Drawn by hand, updated once a minute.
 */
class BuiltinWidget(ctx: Context, val kind: String, var style: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f * d, 0f, 1f * d, 0x66000000)
    }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * d, 0f, 1f * d, 0x66000000)
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var battery = -1
    private var charging = false
    private var alarm = ""
    /** World clock: time zone ids (the phone's own zone is always first). */
    var zones: List<String> = emptyList()

    fun refresh() {
        try {
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                battery = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
            }
        } catch (_: Exception) {
        }
        alarm = try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.nextAlarmClock?.let { SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it.triggerTime)) } ?: ""
        } catch (_: Exception) {
            ""
        }
        invalidate()
    }

    private fun timeText(): String {
        val f = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return SimpleDateFormat(f, Locale.getDefault()).format(Date())
    }

    private fun info(): String {
        val parts = ArrayList<String>()
        if (battery >= 0) parts.add((if (charging) "⚡" else "🔋") + " $battery%")
        if (alarm.isNotEmpty()) parts.add("⏰ $alarm")
        return parts.joinToString("   ")
    }

    override fun onDraw(c: Canvas) {
        when (kind) {
            "date" -> drawDate(c)
            "battery" -> drawBattery(c)
            "month" -> drawMonth(c)
            else -> when (style) {
                1 -> drawDots(c)
                2 -> drawAnalog(c)
                3 -> drawStacked(c)
                4 -> drawNothing(c)
                5 -> drawOxygen(c)
                6 -> drawWorld(c)
                7 -> drawFlip(c)
                8 -> drawRing(c)
                else -> drawBig(c)
            }
        }
    }

    private fun drawBig(c: Canvas) {
        val h = height.toFloat()
        white.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        white.textSize = min(h * 0.46f, width * 0.27f)
        val cy = h * 0.52f
        c.drawText(timeText(), width / 2f, cy, white)
        soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        soft.textSize = 15f * d
        c.drawText(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), width / 2f, cy + 24f * d, soft)
        val i = info()
        if (i.isNotEmpty()) {
            soft.textSize = 13f * d
            c.drawText(i, width / 2f, cy + 44f * d, soft)
        }
    }

    private fun drawStacked(c: Canvas) {
        val t = timeText().split(':')
        val h = height.toFloat()
        white.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        white.textSize = h * 0.36f
        white.textAlign = Paint.Align.LEFT
        val x = 22f * d
        c.drawText(t.getOrElse(0) { "" }, x, h * 0.42f, white)
        c.drawText(t.getOrElse(1) { "" }, x, h * 0.80f, white)
        white.textAlign = Paint.Align.CENTER
        soft.textAlign = Paint.Align.LEFT
        soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        soft.textSize = 16f * d
        val rx = width * 0.5f
        c.drawText(SimpleDateFormat("EEEE", Locale.getDefault()).format(Date()), rx, h * 0.38f, soft)
        soft.textSize = 14f * d
        c.drawText(SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date()), rx, h * 0.38f + 22f * d, soft)
        val i = info()
        if (i.isNotEmpty()) c.drawText(i, rx, h * 0.38f + 44f * d, soft)
        soft.textAlign = Paint.Align.CENTER
    }

    /** 5 × 7 dot-matrix digits (Nothing-style), our own pattern. */
    private val glyphs = mapOf(
        '0' to "01110100011001110101110011000101110", '1' to "00100011000010000100001000010001110",
        '2' to "01110100010000100010001000100011111", '3' to "11111000100010000010000011000101110",
        '4' to "00010001100101010010111110001000010", '5' to "11111100001111000001000011000101110",
        '6' to "00110010001000011110100011000101110", '7' to "11111000010001000100010000100001000",
        '8' to "01110100011000101110100011000101110", '9' to "01110100011000101111000010001001100",
        ':' to "00000001000010000000001000010000000"
    )

    private fun drawDots(c: Canvas) {
        val t = timeText()
        val cols = t.length * 6 - 1
        val dot = min(width * 0.86f / cols, height * 0.52f / 7f)
        val totalW = cols * dot
        var x0 = (width - totalW) / 2f
        val y0 = height * 0.12f
        p.color = Color.WHITE
        p.setShadowLayer(4f * d, 0f, 0f, 0x55000000)
        for (ch in t) {
            val g = glyphs[ch] ?: continue
            for (i in 0 until 35) {
                if (g[i] == '1') {
                    val cx = x0 + (i % 5) * dot + dot / 2
                    val cy = y0 + (i / 5) * dot + dot / 2
                    c.drawCircle(cx, cy, dot * 0.38f, p)
                }
            }
            x0 += 6 * dot
        }
        p.clearShadowLayer()
        soft.typeface = Typeface.MONOSPACE
        soft.textSize = 13f * d
        soft.letterSpacing = 0.15f
        val line = SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(Date()).uppercase() +
            (if (battery >= 0) "  ·  $battery%" else "")
        c.drawText(line, width / 2f, y0 + 7 * dot + 26f * d, soft)
        soft.letterSpacing = 0f
    }

    private fun drawAnalog(c: Canvas) {
        val r = min(width, height) * 0.44f
        val cx = if (width > height * 1.5f) width * 0.3f else width / 2f
        val cy = height / 2f
        p.style = Paint.Style.FILL
        p.color = 0x33FFFFFF
        c.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            p.strokeWidth = if (i % 3 == 0) 3f * d else 1.5f * d
            val len = if (i % 3 == 0) r * 0.14f else r * 0.07f
            c.drawLine(cx + (sin(a) * (r - len)).toFloat(), cy - (cos(a) * (r - len)).toFloat(),
                cx + (sin(a) * r * 0.93).toFloat(), cy - (cos(a) * r * 0.93).toFloat(), p)
        }
        val cal = java.util.Calendar.getInstance()
        val min = cal.get(java.util.Calendar.MINUTE)
        val hr = cal.get(java.util.Calendar.HOUR) + min / 60.0
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 5f * d
        val ha = Math.toRadians(hr * 30.0)
        c.drawLine(cx, cy, cx + (sin(ha) * r * 0.5).toFloat(), cy - (cos(ha) * r * 0.5).toFloat(), p)
        p.strokeWidth = 3.5f * d
        val ma = Math.toRadians(min * 6.0)
        c.drawLine(cx, cy, cx + (sin(ma) * r * 0.78).toFloat(), cy - (cos(ma) * r * 0.78).toFloat(), p)
        p.color = 0xFFFF9F0A.toInt()
        c.drawCircle(cx, cy, 4f * d, p)
        p.strokeCap = Paint.Cap.BUTT
        if (width > height * 1.5f) {
            soft.textAlign = Paint.Align.LEFT
            soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            soft.textSize = 18f * d
            val x = width * 0.58f
            c.drawText(SimpleDateFormat("EEEE", Locale.getDefault()).format(Date()), x, cy - 6f * d, soft)
            soft.textSize = 14f * d
            c.drawText(SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date()), x, cy + 16f * d, soft)
            val i = info()
            if (i.isNotEmpty()) c.drawText(i, x, cy + 38f * d, soft)
            soft.textAlign = Paint.Align.CENTER
        }
    }

    /** Dot-matrix digits of [t] at (x0, y0) with dot size [dot]. */
    private fun dotText(c: Canvas, t: String, x0: Float, y0: Float, dot: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        var x = x0
        for (ch in t) {
            val g = glyphs[ch] ?: continue
            for (i in 0 until 35) if (g[i] == '1') c.drawCircle(x + (i % 5) * dot + dot / 2, y0 + (i / 5) * dot + dot / 2, dot * 0.4f, p)
            x += 6 * dot
        }
    }

    /** Nothing OS: hours over minutes in big dots, minutes in Nothing red, date on the right. */
    private fun drawNothing(c: Canvas) {
        val t = timeText().padStart(5, '0').split(':')
        val dot = min(width * 0.5f / 11f, height * 0.84f / 15f)
        val x0 = 14f * d
        val y0 = (height - 15 * dot) / 2f
        dotText(c, t.getOrElse(0) { "" }, x0, y0, dot, Color.WHITE)
        dotText(c, t.getOrElse(1) { "" }, x0, y0 + 8 * dot, dot, 0xFFD71921.toInt())
        val rx = x0 + 11 * dot + 18f * d
        soft.textAlign = Paint.Align.LEFT
        soft.typeface = Typeface.MONOSPACE
        soft.letterSpacing = 0.12f
        soft.textSize = 15f * d
        val now = Date()
        c.drawText(SimpleDateFormat("EEEE", Locale.getDefault()).format(now).uppercase(), rx, height * 0.40f, soft)
        soft.textSize = 13f * d
        c.drawText(SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(now), rx, height * 0.40f + 22f * d, soft)
        if (battery >= 0) c.drawText((if (charging) "⚡ " else "") + "$battery%", rx, height * 0.40f + 42f * d, soft)
        p.color = 0xFFD71921.toInt()
        c.drawCircle(rx + 3f * d, height * 0.40f - 26f * d, 3.5f * d, p)
        soft.letterSpacing = 0f
        soft.textAlign = Paint.Align.CENTER
    }

    /** OxygenOS / OnePlus: heavy two-tone digits, hours white and minutes red, date underneath. */
    private fun drawOxygen(c: Canvas) {
        val t = timeText().split(':')
        val hh = t.getOrElse(0) { "" }
        val mm = t.getOrElse(1) { "" }
        white.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        white.textSize = min(height * 0.5f, width * 0.24f)
        white.textAlign = Paint.Align.LEFT
        val wh = white.measureText(hh)
        val gap = white.textSize * 0.08f
        val wm = white.measureText(mm)
        var x = (width - wh - gap - wm) / 2f
        val y = height * 0.56f
        c.drawText(hh, x, y, white)
        x += wh + gap
        val red = white.color
        white.color = 0xFFEB0028.toInt()
        c.drawText(mm, x, y, white)
        white.color = red
        white.textAlign = Paint.Align.CENTER
        soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        soft.textSize = 15f * d
        val line = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date()) + (if (battery >= 0) "   ·   $battery%" else "")
        c.drawText(line, width / 2f, y + 26f * d, soft)
    }

    /** World clock: small day / night dials for the phone's zone and up to three cities. */
    private fun drawWorld(c: Canvas) {
        val list = (listOf(java.util.TimeZone.getDefault().id) + zones).distinct().take(4)
        val n = list.size
        val cw = width / n.toFloat()
        val r = min(cw * 0.32f, height * 0.26f)
        val cy = height * 0.40f
        val fmt = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val here = java.util.TimeZone.getDefault()
        val now = System.currentTimeMillis()
        for ((i, z) in list.withIndex()) {
            val tz = java.util.TimeZone.getTimeZone(z)
            val cal = java.util.Calendar.getInstance(tz)
            val hr = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val mn = cal.get(java.util.Calendar.MINUTE)
            val day = hr in 6..17
            val cx = cw * i + cw / 2
            p.style = Paint.Style.FILL
            p.color = if (day) 0xF2FFFFFF.toInt() else 0xFF1C1C1E.toInt()
            c.drawCircle(cx, cy, r, p)
            p.color = if (day) 0xFF1C1C1E.toInt() else Color.WHITE
            for (k in 0 until 12) {
                val a = Math.toRadians(k * 30.0)
                c.drawCircle(cx + (sin(a) * r * 0.82).toFloat(), cy - (cos(a) * r * 0.82).toFloat(), if (k % 3 == 0) 1.6f * d else 0.9f * d, p)
            }
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 3f * d
            val ha = Math.toRadians(((hr % 12) + mn / 60.0) * 30.0)
            c.drawLine(cx, cy, cx + (sin(ha) * r * 0.48).toFloat(), cy - (cos(ha) * r * 0.48).toFloat(), p)
            p.strokeWidth = 2f * d
            val ma = Math.toRadians(mn * 6.0)
            c.drawLine(cx, cy, cx + (sin(ma) * r * 0.72).toFloat(), cy - (cos(ma) * r * 0.72).toFloat(), p)
            p.color = 0xFFFF9F0A.toInt()
            c.drawCircle(cx, cy, 2.2f * d, p)
            p.strokeCap = Paint.Cap.BUTT
            val city = if (i == 0) "Here" else z.substringAfterLast('/').replace('_', ' ')
            soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            soft.textSize = 13f * d
            c.drawText(TextUtils.ellipsize(city, TextPaint(soft), cw - 6f * d, TextUtils.TruncateAt.END).toString(), cx, cy + r + 18f * d, soft)
            white.typeface = Typeface.DEFAULT_BOLD
            white.textSize = 14f * d
            val sdf = SimpleDateFormat(fmt, Locale.getDefault())
            sdf.timeZone = tz
            c.drawText(sdf.format(Date(now)), cx, cy + r + 36f * d, white)
            if (i > 0) {
                val diff = (tz.getOffset(now) - here.getOffset(now)) / 60000
                val sign = if (diff >= 0) "+" else "−"
                val ad = abs(diff)
                val txt = sign + (ad / 60) + "h" + (if (ad % 60 != 0) " " + (ad % 60) + "m" else "")
                soft.textSize = 11f * d
                c.drawText(txt, cx, cy + r + 51f * d, soft)
            }
        }
    }

    /** Flip clock: two dark cards with a split line. */
    private fun drawFlip(c: Canvas) {
        val t = timeText().padStart(5, '0').split(':')
        val cardH = min(height * 0.66f, width * 0.36f)
        val cardW = cardH * 1.05f
        val gap = 10f * d
        val left = (width - 2 * cardW - gap) / 2f
        val top = height * 0.08f
        white.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        white.textSize = cardH * 0.7f
        for (k in 0..1) {
            val l = left + k * (cardW + gap)
            p.style = Paint.Style.FILL
            p.color = 0xF01C1C1E.toInt()
            c.drawRoundRect(RectF(l, top, l + cardW, top + cardH), 14f * d, 14f * d, p)
            c.drawText(t.getOrElse(k) { "" }, l + cardW / 2, top + cardH / 2 - (white.descent() + white.ascent()) / 2, white)
            p.color = 0xFF000000.toInt()
            p.strokeWidth = 2f * d
            c.drawLine(l, top + cardH / 2, l + cardW, top + cardH / 2, p)
        }
        soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        soft.textSize = 14f * d
        c.drawText(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), width / 2f, top + cardH + 22f * d, soft)
    }

    /** Pixel-style rings: outer ring = hours, inner ring = minutes, time in the middle. */
    private fun drawRing(c: Canvas) {
        val cal = java.util.Calendar.getInstance()
        val wide = width > height * 1.5f
        val s = min(height * 0.9f, width * 0.9f)
        val cx = if (wide) height * 0.55f + 8f * d else width / 2f
        val cy = height / 2f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        val outer = s / 2 - 8f * d
        val inner = outer - 16f * d
        p.strokeWidth = 10f * d
        p.color = 0x33FFFFFF
        c.drawCircle(cx, cy, outer, p)
        c.drawCircle(cx, cy, inner, p)
        p.color = 0xFF8AB4F8.toInt()
        val hr = cal.get(java.util.Calendar.HOUR) + cal.get(java.util.Calendar.MINUTE) / 60f
        c.drawArc(RectF(cx - outer, cy - outer, cx + outer, cy + outer), -90f, 360f * hr / 12f, false, p)
        p.color = 0xFFF28B82.toInt()
        c.drawArc(RectF(cx - inner, cy - inner, cx + inner, cy + inner), -90f, 360f * cal.get(java.util.Calendar.MINUTE) / 60f, false, p)
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        white.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        white.textSize = inner * 0.5f
        c.drawText(timeText(), cx, cy - (white.descent() + white.ascent()) / 2, white)
        if (wide) {
            soft.textAlign = Paint.Align.LEFT
            soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val x = cx + outer + 22f * d
            soft.textSize = 20f * d
            c.drawText(SimpleDateFormat("EEEE", Locale.getDefault()).format(Date()), x, cy - 4f * d, soft)
            soft.textSize = 15f * d
            c.drawText(SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date()), x, cy + 20f * d, soft)
            val i = info()
            if (i.isNotEmpty()) c.drawText(i, x, cy + 42f * d, soft)
            soft.textAlign = Paint.Align.CENTER
        }
    }

    /** iOS-style month calendar, today in a red circle. */
    private fun drawMonth(c: Canvas) {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val first = cal.clone() as java.util.Calendar
        first.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val startDow = (first.get(java.util.Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
        val days = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val rows = (startDow + days + 6) / 7
        val pad = 12f * d
        val head = 22f * d
        val cellW = (width - 2 * pad) / 7f
        val cellH = (height - pad * 2 - head * 2) / rows
        soft.textAlign = Paint.Align.LEFT
        soft.typeface = Typeface.DEFAULT_BOLD
        soft.textSize = 13f * d
        soft.color = 0xFFFF453A.toInt()
        c.drawText(SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time).uppercase(), pad + 4f * d, pad + 13f * d, soft)
        soft.color = 0xDDFFFFFF.toInt()
        soft.textAlign = Paint.Align.CENTER
        soft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        soft.textSize = 10f * d
        val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
        for (k in 0 until 7) {
            val dow = (cal.firstDayOfWeek - 1 + k) % 7 + 1
            c.drawText(names[dow].take(1).uppercase(), pad + cellW * k + cellW / 2, pad + head + 10f * d, soft)
        }
        white.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        white.textSize = min(cellH * 0.55f, 13f * d)
        for (day in 1..days) {
            val idx = startDow + day - 1
            val x = pad + cellW * (idx % 7) + cellW / 2
            val y = pad + head * 2 + cellH * (idx / 7) + cellH / 2
            if (day == today) {
                p.style = Paint.Style.FILL
                p.color = 0xFFFF453A.toInt()
                c.drawCircle(x, y, min(cellW, cellH) * 0.42f, p)
            }
            c.drawText(day.toString(), x, y - (white.descent() + white.ascent()) / 2, white)
        }
    }

    private fun drawDate(c: Canvas) {
        val s = min(width, height) * 0.86f
        val l = (width - s) / 2f
        val t = (height - s) / 2f
        p.style = Paint.Style.FILL
        p.color = 0xEEFFFFFF.toInt()
        c.drawRoundRect(RectF(l, t, l + s, t + s), s * 0.2f, s * 0.2f, p)
        val cal = java.util.Calendar.getInstance()
        white.clearShadowLayer()
        white.color = 0xFFFF3B30.toInt()
        white.typeface = Typeface.DEFAULT_BOLD
        white.textSize = s * 0.15f
        c.drawText(SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.time).uppercase(), width / 2f, t + s * 0.25f, white)
        white.color = 0xFF1C1C1E.toInt()
        white.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        white.textSize = s * 0.5f
        c.drawText(cal.get(java.util.Calendar.DAY_OF_MONTH).toString(), width / 2f, t + s * 0.72f, white)
        white.textSize = s * 0.11f
        white.color = 0xFF6E6E73.toInt()
        c.drawText(SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time), width / 2f, t + s * 0.9f, white)
        white.color = Color.WHITE
        white.setShadowLayer(6f * d, 0f, 1f * d, 0x66000000)
    }

    private fun drawBattery(c: Canvas) {
        val s = min(width, height) * 0.86f
        val cx = width / 2f
        val cy = height / 2f
        p.style = Paint.Style.FILL
        p.color = 0x44000000
        c.drawCircle(cx, cy, s / 2, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.09f
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x33FFFFFF
        val r = s / 2 - p.strokeWidth
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 0f, 360f, false, p)
        val lvl = battery.coerceAtLeast(0)
        p.color = when {
            charging -> 0xFF30D158.toInt()
            lvl <= 20 -> 0xFFFF453A.toInt()
            else -> Color.WHITE
        }
        c.drawArc(box, -90f, 360f * lvl / 100f, false, p)
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        white.typeface = Typeface.DEFAULT_BOLD
        white.textSize = s * 0.22f
        c.drawText(if (battery >= 0) "$battery%" else "—", cx, cy + s * 0.08f, white)
        soft.textSize = s * 0.1f
        c.drawText(if (charging) "charging" else "battery", cx, cy + s * 0.24f, soft)
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
