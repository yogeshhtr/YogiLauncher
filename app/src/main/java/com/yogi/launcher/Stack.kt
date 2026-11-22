package com.yogi.launcher

import android.animation.ValueAnimator
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * iOS-style smart stack: several widgets in one place. Swipe up / down to flip through them.
 * Little dots on the right show which card is shown.
 */
class WidgetStack(ctx: Context) : FrameLayout(ctx) {
    var index = 0
        private set
    var onChange: ((Int) -> Unit)? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = 500f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var offset = 0f
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private val cards = ArrayList<View>()

    private val dots = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val n = cards.size
            if (n <= 1) return
            val d = resources.displayMetrics.density
            val gap = 9f * d
            val start = height / 2f - (n - 1) * gap / 2f
            for (i in 0 until n) {
                p.color = if (i == index) Color.WHITE else 0x66FFFFFF
                c.drawCircle(width / 2f, start + i * gap, (if (i == index) 2.6f else 2f) * d, p)
            }
        }
    }

    fun setCards(views: List<View>, start: Int) {
        removeAllViews()
        cards.clear()
        for (v in views) {
            cards.add(v)
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(dots, LayoutParams(context.dp(10), LayoutParams.MATCH_PARENT, Gravity.END))
        index = start.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        place()
    }

    private fun place() {
        val h = height.toFloat()
        for ((i, v) in cards.withIndex()) {
            v.translationY = (i - index) * h + offset
            v.visibility = if (abs(i - index) <= 1) View.VISIBLE else View.INVISIBLE
        }
        dots.invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (v in cards) if (v is AppWidgetHostView) WidgetHost.tellSize(context, v, right - left, bottom - top)
        place()
    }

    private fun pager(): Pager? {
        var p = parent
        while (p != null) {
            if (p is Pager) return p
            p = p.parent
        }
        return null
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // vertical swipes here flip the stack instead of opening the App Library
        if (e.actionMasked == MotionEvent.ACTION_DOWN && cards.size > 1) pager()?.blockVertical = true
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                if (anim?.isRunning == true) {
                    anim?.cancel()
                    dragging = true
                    downY = e.y - offset
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                val dx = e.x - downX
                if (cards.size > 1 && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
                    dragging = true
                    downY = e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && cards.size > 1 && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX) * 1.2f) {
                    dragging = true
                    downY = e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    var o = e.y - downY
                    if ((index == 0 && o > 0) || (index == cards.size - 1 && o < 0)) o *= 0.3f
                    offset = o
                    place()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    var t = index
                    if (offset < -height * 0.22f || v < -minFling) t = index + 1
                    if (offset > height * 0.22f || v > minFling) t = index - 1
                    snapTo(t.coerceIn(0, cards.size - 1))
                }
                dragging = false
                vt?.recycle()
                vt = null
            }
        }
        return true
    }

    fun show(t: Int) = snapTo(t.coerceIn(0, (cards.size - 1).coerceAtLeast(0)))

    private fun snapTo(t: Int) {
        val h = height.toFloat()
        offset += (t - index) * h
        val changed = t != index
        index = t
        if (changed) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(t)
        }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                offset = it.animatedValue as Float
                place()
            }
            start()
        }
    }
}

/**
 * Smart suggestions (iOS Siri suggestions / One UI): the apps you are most likely to open now,
 * learned from what you open at this time of day.
 */
class SuggestRow(ctx: Context, private val cols: Int, private val rows: Int, private val iconPx: Int) : LinearLayout(ctx) {
    private var shown = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
    }

    fun refresh() {
        val apps = Apps.smart(cols * rows)
        val sig = apps.joinToString { it.key }
        if (sig == shown && childCount > 0) return
        shown = sig
        removeAllViews()
        if (apps.isEmpty()) {
            addView(context.label("✨ Suggestions appear as you use your apps", 13f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 1f, 0x66000000)
            })
            return
        }
        val size = (iconPx * 0.92f).toInt()
        for (r in 0 until rows) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER
            for (c in 0 until cols) {
                val app = apps.getOrNull(r * cols + c)
                val cell = LinearLayout(context)
                cell.orientation = VERTICAL
                cell.gravity = Gravity.CENTER
                if (app != null) {
                    val iv = ImageView(context)
                    val cached = Icons.cached(app)
                    if (cached != null) iv.setImageBitmap(cached) else Thread {
                        val b = try { Icons.get(context, app) } catch (_: Exception) { null }
                        iv.post { iv.setImageBitmap(b) }
                    }.start()
                    cell.addView(iv, LayoutParams(size, size))
                    cell.addView(context.label(app.label, 11f, Color.WHITE).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setShadowLayer(5f, 0f, 1f, 0x88000000.toInt())
                    }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    cell.setOnClickListener { Apps.launch(context, app, iv) }
                }
                row.addView(cell, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
}
