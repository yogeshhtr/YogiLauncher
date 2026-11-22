package com.yogi.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Yogi icon styles. They restyle each app's OWN icon, so WhatsApp, Viber and Telegram stay recognisable,
 * but every icon gets the same finish:
 *  - Glass (iOS 26 "liquid glass" feel): the app's colour as a soft gradient, a glossy top light and a bright rim
 *  - Dark (Nothing OS): white glyphs on graphite
 *  - Light (iOS tinted light): the app's glyph in its own colour on frosted white
 */
object YogiIcons {
    const val PACK = "yogi"          // glass
    const val DARK = "yogi_dark"
    const val LIGHT = "yogi_light"

    fun isYogi(p: String): Boolean = p == PACK || p == DARK || p == LIGHT

    fun name(p: String): String = when (p) {
        DARK -> "Yogi Dark (Nothing)"
        LIGHT -> "Yogi Light (iOS tinted)"
        else -> "Yogi Glass"
    }

    fun render(app: AppInfo, s: Int, pack: String): Bitmap? {
        val d = try { app.info.getIcon(0) } catch (_: Exception) { null } ?: return null
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shape = Shapes.path(Prefs.shape, s.toFloat())
        val dom = dominant(d)
        val adaptive = Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable
        val e = (s * 0.25f).toInt()
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG)

        c.save()
        c.clipPath(shape)
        when (pack) {
            DARK -> {
                bgP.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), 0xFF2E2E33.toInt(), 0xFF0F0F11.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), bgP)
                glyph(c, d, adaptive, s, e, 0xFFF2F2F2.toInt(), grey = true)
            }
            LIGHT -> {
                bgP.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), 0xFFFFFFFF.toInt(), 0xFFE6E9F0.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), bgP)
                glyph(c, d, adaptive, s, e, shade(dom, if (lum(dom) > 0.7f) 0.45f else 0.9f), grey = false)
            }
            else -> {
                // glass: keep light backgrounds light (Google-style icons), otherwise the app's own colour
                val lightBg = adaptive && Build.VERSION.SDK_INT >= 26 && (d as AdaptiveIconDrawable).background?.let { lum(avg(it)) > 0.82f } == true
                val top: Int
                val bottom: Int
                if (lightBg || sat(dom) < 0.12f && lum(dom) > 0.7f) {
                    top = 0xFFFFFFFF.toInt(); bottom = 0xFFE4E8EF.toInt()
                } else {
                    top = shade(dom, 1.28f); bottom = shade(dom, 0.72f)
                }
                bgP.shader = LinearGradient(0f, 0f, s * 0.35f, s.toFloat(), top, bottom, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), bgP)
                if (adaptive && Build.VERSION.SDK_INT >= 26) {
                    val fg = (d as AdaptiveIconDrawable).foreground
                    if (fg != null) {
                        fg.setBounds(-e, -e, s + e, s + e)
                        fg.draw(c)
                    }
                } else {
                    val raw = toBitmap(d, s)
                    val inset = (s * 0.16f).toInt()
                    c.drawBitmap(raw, null, Rect(inset, inset, s - inset, s - inset), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
            }
        }
        // gloss: light from the top, a little depth at the bottom
        val gl = Paint(Paint.ANTI_ALIAS_FLAG)
        gl.shader = LinearGradient(0f, 0f, 0f, s * 0.55f,
            if (pack == DARK) 0x33FFFFFF else 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s * 0.55f, gl)
        gl.shader = LinearGradient(0f, s * 0.6f, 0f, s.toFloat(), 0x00000000, if (pack == LIGHT) 0x14000000 else 0x26000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, s * 0.6f, s.toFloat(), s.toFloat(), gl)
        c.restore()
        // bright rim, strongest at the top (liquid glass edge)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG)
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = s * 0.022f
        rim.shader = LinearGradient(0f, 0f, 0f, s.toFloat(),
            if (pack == LIGHT) 0xCCFFFFFF.toInt() else 0x99FFFFFF.toInt(), 0x14FFFFFF, Shader.TileMode.CLAMP)
        c.save()
        c.clipPath(shape)
        c.drawPath(shape, rim)
        c.restore()
        return out
    }

    /** The app's glyph: Android 13 monochrome layer when the app has one, else its foreground. */
    private fun glyph(c: Canvas, d: Drawable, adaptive: Boolean, s: Int, e: Int, color: Int, grey: Boolean) {
        if (adaptive && Build.VERSION.SDK_INT >= 26) {
            val ad = d as AdaptiveIconDrawable
            val mono = if (Build.VERSION.SDK_INT >= 33) ad.monochrome else null
            val layer = (mono ?: ad.foreground)?.constantState?.newDrawable()?.mutate() ?: return
            layer.setBounds(-e, -e, s + e, s + e)
            layer.colorFilter = if (mono != null) PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) else if (grey) greyFilter() else null
            layer.draw(c)
        } else {
            val raw = toBitmap(d, s)
            val inset = (s * 0.16f).toInt()
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (grey) p.colorFilter = greyFilter()
            c.drawBitmap(raw, null, Rect(inset, inset, s - inset, s - inset), p)
        }
    }

    private fun greyFilter(): ColorMatrixColorFilter {
        val m = ColorMatrix()
        m.setSaturation(0f)
        m.postConcat(ColorMatrix(floatArrayOf(1.25f, 0f, 0f, 0f, 30f, 0f, 1.25f, 0f, 0f, 30f, 0f, 0f, 1.25f, 0f, 30f, 0f, 0f, 0f, 1f, 0f)))
        return ColorMatrixColorFilter(m)
    }

    private fun toBitmap(d: Drawable, s: Int): Bitmap {
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val dd = d.constantState?.newDrawable()?.mutate() ?: d
        dd.setBounds(0, 0, s, s)
        dd.draw(Canvas(b))
        return b
    }

    /** Average colour of a drawable (small render). */
    private fun avg(d: Drawable): Int {
        val b = toBitmap(d, 16)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        if (n == 0L) return Color.WHITE
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    /** The icon's main colour: the strongest hue, weighted by how vivid each pixel is. */
    private fun dominant(d: Drawable): Int {
        val b = toBitmap(d, 28)
        val bins = FloatArray(12)
        val sums = Array(12) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until 28) for (x in 0 until 28) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
            val w = hsv[1] * hsv[2]
            val i = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            bins[i] += w
            sums[i][0] += Color.red(p) * w; sums[i][1] += Color.green(p) * w; sums[i][2] += Color.blue(p) * w
        }
        val best = bins.indices.maxByOrNull { bins[it] } ?: return 0xFF8E8E93.toInt()
        val w = bins[best]
        if (w < 8f) return avg(d)
        return Color.rgb((sums[best][0] / w).toInt().coerceIn(0, 255), (sums[best][1] / w).toInt().coerceIn(0, 255), (sums[best][2] / w).toInt().coerceIn(0, 255))
    }

    private fun lum(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    private fun sat(c: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        return hsv[1]
    }

    /** Lighter (> 1) or darker (< 1) version of a colour. */
    private fun shade(c: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (f > 1f) {
            hsv[2] = (hsv[2] * f).coerceAtMost(1f)
            hsv[1] = (hsv[1] / f).coerceIn(0f, 1f)
        } else {
            hsv[2] = hsv[2] * f
        }
        return Color.HSVToColor(hsv)
    }
}
