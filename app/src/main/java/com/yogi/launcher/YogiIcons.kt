package com.yogi.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Yogi icon styles. They finish each app's OWN icon (its real colours and logo), never swap it for a drawing:
 *  - Glass (liquid glass): the app's own background and logo, the logo lifted with a soft shadow,
 *    a curved glass highlight on top, a light edge at the bottom and a bright rim
 *  - Dark (Nothing OS): the app's monochrome logo in white on graphite
 *  - Light (iOS tinted light): the app's monochrome logo in its own colour on frosted white
 * Apps whose icon can't be styled well (old square icons, no monochrome logo) keep their normal icon.
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

    /** File manager apps get Yogi's own folder icon in every Yogi style. */
    private val FILES = setOf("com.google.android.apps.nbu.files", "com.sec.android.app.myfiles", "com.mi.android.globalFileexplorer",
        "com.android.fileexplorer", "com.coloros.filemanager", "com.oneplus.filemanager", "com.nothing.filemanager",
        "com.huawei.hidisk", "com.android.documentsui", "com.google.android.documentsui", "com.oplus.filemanager",
        "com.vivo.filemanager", "com.motorola.filemanager", "com.asus.filemanager", "com.lenovo.FileBrowser2",
        "com.transsion.filemanagerx", "com.realme.filemanager")

    /** null = keep the app's normal icon. */
    fun render(app: AppInfo, s: Int, pack: String): Bitmap? {
        if (app.pkg in FILES) return try { files(s, pack) } catch (_: Throwable) { null }
        if (Build.VERSION.SDK_INT < 26) return null
        val d = try { app.info.getIcon(0) } catch (_: Exception) { null } ?: return null
        if (d !is AdaptiveIconDrawable) return null
        return try {
            when (pack) {
                DARK, LIGHT -> tinted(d, s, pack == DARK)
                else -> glass(d, s)
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ glass

    private fun glass(d: AdaptiveIconDrawable, s: Int): Bitmap {
        val sf = s.toFloat()
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shape = Shapes.path(Prefs.shape, sf)
        val e = (s * 0.25f).toInt()
        c.save()
        c.clipPath(shape)

        // 1. the app's own background
        val bg = d.background
        if (bg != null) {
            bg.setBounds(-e, -e, s + e, s + e)
            bg.draw(c)
        } else c.drawColor(Color.WHITE)

        // 2. glass depth: a soft light wash from the top, a little shade at the bottom
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, sf, intArrayOf(0x2EFFFFFF, 0x00FFFFFF, 0x00000000, 0x24000000),
            floatArrayOf(0f, 0.45f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, sf, sf, p)

        // 3. the logo, lifted off the glass with a soft shadow
        val fg = d.foreground
        if (fg != null) {
            val fb = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            fg.setBounds(-e, -e, s + e, s + e)
            fg.draw(Canvas(fb))
            val alpha = fb.extractAlpha()
            val sh = Paint(Paint.ANTI_ALIAS_FLAG)
            sh.color = 0x47000000
            sh.maskFilter = BlurMaskFilter(sf * 0.035f, BlurMaskFilter.Blur.NORMAL)
            c.drawBitmap(alpha, 0f, sf * 0.028f, sh)
            c.drawBitmap(fb, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            alpha.recycle()
            fb.recycle()
        }

        // 4. curved highlight across the top, like light on a glass dome
        val dome = Path()
        dome.addOval(RectF(-sf * 0.25f, -sf * 0.68f, sf * 1.25f, sf * 0.44f), Path.Direction.CW)
        p.shader = LinearGradient(0f, 0f, 0f, sf * 0.44f, 0x4DFFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(dome, p)

        // 5. light caught in the bottom edge (refraction)
        p.shader = RadialGradient(sf * 0.5f, sf * 1.08f, sf * 0.5f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, sf * 0.6f, sf, sf, p)

        // 6. a small sparkle at the top left
        p.shader = RadialGradient(sf * 0.24f, sf * 0.16f, sf * 0.16f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(sf * 0.24f, sf * 0.16f, sf * 0.16f, p)
        c.restore()

        rim(c, shape, sf, 0xCCFFFFFF.toInt(), 0x14FFFFFF, 0x59FFFFFF)
        return out
    }

    /** Bright glass edge: strong at the top left, faint in the middle, soft again at the bottom right. */
    private fun rim(c: Canvas, shape: Path, sf: Float, a: Int, b: Int, z: Int) {
        val r = Paint(Paint.ANTI_ALIAS_FLAG)
        r.style = Paint.Style.STROKE
        r.strokeWidth = sf * 0.032f
        r.shader = LinearGradient(0f, 0f, sf, sf, intArrayOf(a, b, z), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.clipPath(shape)
        c.drawPath(shape, r)
        // hair-thin dark line so light icons still have an edge
        r.shader = null
        r.color = 0x1F000000
        r.strokeWidth = sf * 0.008f
        c.drawPath(shape, r)
        c.restore()
    }

    // ------------------------------------------------------------------ file manager

    /** A folder with a document peeking out, drawn in the chosen Yogi style. */
    private fun files(s: Int, pack: String): Bitmap {
        val sf = s.toFloat()
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shape = Shapes.path(Prefs.shape, sf)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val dark = pack == DARK
        val light = pack == LIGHT
        c.save()
        c.clipPath(shape)
        p.shader = when {
            dark -> LinearGradient(0f, 0f, 0f, sf, 0xFF34343A.toInt(), 0xFF111113.toInt(), Shader.TileMode.CLAMP)
            light -> LinearGradient(0f, 0f, 0f, sf, 0xFFFFFFFF.toInt(), 0xFFE4E7EE.toInt(), Shader.TileMode.CLAMP)
            else -> LinearGradient(0f, 0f, sf * 0.4f, sf, 0xFF4FA3FF.toInt(), 0xFF1D5FE0.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, sf, sf, p)
        p.shader = null
        val l = sf * 0.2f
        val r = sf * 0.8f
        val t = sf * 0.3f
        val b = sf * 0.74f
        val rad = sf * 0.06f
        // back of the folder with its tab
        p.color = when { dark -> 0xFF8E8E93.toInt(); light -> 0xFF7FB2FF.toInt(); else -> 0xFFFFC94D.toInt() }
        val back = Path()
        back.addRoundRect(RectF(l, t, l + sf * 0.24f, t + sf * 0.12f), rad, rad, Path.Direction.CW)
        back.addRoundRect(RectF(l, t + sf * 0.06f, r, b), rad, rad, Path.Direction.CW)
        c.drawPath(back, p)
        // a sheet of paper
        p.color = if (dark) 0xFFDADADF.toInt() else Color.WHITE
        c.drawRoundRect(RectF(l + sf * 0.08f, t + sf * 0.1f, r - sf * 0.08f, b - sf * 0.1f), rad * 0.6f, rad * 0.6f, p)
        // front of the folder, a little lighter, with a soft shadow above it
        val front = RectF(l, t + sf * 0.2f, r, b)
        val sh = Paint(Paint.ANTI_ALIAS_FLAG)
        sh.color = 0x33000000
        sh.maskFilter = BlurMaskFilter(sf * 0.025f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(front.left, front.top - sf * 0.01f, front.right, front.bottom), rad, rad, sh)
        p.shader = when {
            dark -> LinearGradient(0f, front.top, 0f, front.bottom, 0xFFF4F4F4.toInt(), 0xFFBDBDC2.toInt(), Shader.TileMode.CLAMP)
            light -> LinearGradient(0f, front.top, 0f, front.bottom, 0xFF5A9BFF.toInt(), 0xFF1D64E8.toInt(), Shader.TileMode.CLAMP)
            else -> LinearGradient(0f, front.top, 0f, front.bottom, 0xFFFFE08A.toInt(), 0xFFFFB627.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(front, rad, rad, p)
        p.shader = null
        // glass finish like the other Yogi icons
        val dome = Path()
        dome.addOval(RectF(-sf * 0.25f, -sf * 0.68f, sf * 1.25f, sf * 0.44f), Path.Direction.CW)
        p.shader = LinearGradient(0f, 0f, 0f, sf * 0.44f, if (dark) 0x22FFFFFF else 0x44FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(dome, p)
        c.restore()
        rim(c, shape, sf, if (dark) 0x66FFFFFF else 0xCCFFFFFF.toInt(), 0x14FFFFFF, 0x59FFFFFF)
        return out
    }

    // ------------------------------------------------------------------ dark / light

    private fun tinted(d: AdaptiveIconDrawable, s: Int, dark: Boolean): Bitmap? {
        if (Build.VERSION.SDK_INT < 33) return null
        val mono = d.monochrome ?: return null
        val sf = s.toFloat()
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shape = Shapes.path(Prefs.shape, sf)
        val e = (s * 0.25f).toInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.clipPath(shape)
        p.shader = if (dark) LinearGradient(0f, 0f, 0f, sf, 0xFF34343A.toInt(), 0xFF111113.toInt(), Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, sf, 0xFFFFFFFF.toInt(), 0xFFE4E7EE.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, sf, sf, p)
        val col = if (dark) 0xFFF4F4F4.toInt() else deep(dominant(d))
        val layer = mono.constantState?.newDrawable()?.mutate() ?: mono
        layer.setBounds(-e, -e, s + e, s + e)
        layer.colorFilter = PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN)
        layer.draw(c)
        p.shader = LinearGradient(0f, 0f, 0f, sf * 0.5f, if (dark) 0x22FFFFFF else 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, sf, sf * 0.5f, p)
        c.restore()
        rim(c, shape, sf, if (dark) 0x66FFFFFF else 0xE6FFFFFF.toInt(), 0x0FFFFFFF, if (dark) 0x26FFFFFF else 0x66FFFFFF)
        return out
    }

    /** Makes a colour strong enough to read on white. */
    private fun deep(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.15f) return 0xFF3A3A3C.toInt()
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.82f)
        return Color.HSVToColor(hsv)
    }

    private fun toBitmap(d: Drawable, s: Int): Bitmap {
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val dd = d.constantState?.newDrawable()?.mutate() ?: d
        dd.setBounds(0, 0, s, s)
        dd.draw(Canvas(b))
        return b
    }

    /** The icon's main colour: the strongest hue, weighted by how vivid each pixel is. */
    private fun dominant(d: Drawable): Int {
        val b = toBitmap(d, 28)
        val bins = FloatArray(12)
        val sums = Array(12) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until 28) for (x in 0 until 28) {
            val px = b.getPixel(x, y)
            if (Color.alpha(px) < 200) continue
            Color.colorToHSV(px, hsv)
            if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
            val w = hsv[1] * hsv[2]
            val i = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            bins[i] += w
            sums[i][0] += Color.red(px) * w; sums[i][1] += Color.green(px) * w; sums[i][2] += Color.blue(px) * w
        }
        val best = bins.indices.maxByOrNull { bins[it] } ?: return 0xFF3A3A3C.toInt()
        val w = bins[best]
        if (w < 4f) return 0xFF3A3A3C.toInt()
        return Color.rgb((sums[best][0] / w).toInt().coerceIn(0, 255), (sums[best][1] / w).toInt().coerceIn(0, 255), (sums[best][2] / w).toInt().coerceIn(0, 255))
    }
}
