package com.yogi.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import kotlin.math.PI
import kotlin.math.sin

/** Wallpapers drawn on the phone (no downloads): soft iOS blooms, HyperOS waves, Nothing dots. */
object Wallpapers {
    class Wall(val name: String, val base: IntArray, val blobs: IntArray, val kind: Int)

    /** kind 0 = blooms · 1 = waves · 2 = dots · 3 = rings */
    val ALL = listOf(
        Wall("Bloom", intArrayOf(0xFF0B1026.toInt(), 0xFF1A1440.toInt()), intArrayOf(0xFFFF5E8A.toInt(), 0xFF7B5CFF.toInt(), 0xFF2EC5FF.toInt()), 0),
        Wall("Sunrise", intArrayOf(0xFF2B0F3A.toInt(), 0xFFFF7A59.toInt()), intArrayOf(0xFFFFC371.toInt(), 0xFFFF5F6D.toInt(), 0xFF8E44AD.toInt()), 0),
        Wall("Ocean", intArrayOf(0xFF001B33.toInt(), 0xFF003F66.toInt()), intArrayOf(0xFF00C6FF.toInt(), 0xFF0072FF.toInt(), 0xFF00E5A8.toInt()), 0),
        Wall("Himalaya", intArrayOf(0xFF0E1A2B.toInt(), 0xFF5C7EA8.toInt()), intArrayOf(0xFFE8F1FF.toInt(), 0xFF9BB7D4.toInt(), 0xFF35517A.toInt()), 1),
        Wall("HyperOS waves", intArrayOf(0xFF14102B.toInt(), 0xFF2A1B5C.toInt()), intArrayOf(0xFF6A5CFF.toInt(), 0xFFB65CFF.toInt(), 0xFF5CE1FF.toInt()), 1),
        Wall("Forest", intArrayOf(0xFF07140D.toInt(), 0xFF123524.toInt()), intArrayOf(0xFF3DDC84.toInt(), 0xFF0F9D58.toInt(), 0xFFB8E986.toInt()), 1),
        Wall("Nothing", intArrayOf(0xFF000000.toInt(), 0xFF0A0A0A.toInt()), intArrayOf(0xFFFFFFFF.toInt(), 0xFFD71921.toInt(), 0xFF555555.toInt()), 2),
        Wall("Nothing light", intArrayOf(0xFFF2F2F2.toInt(), 0xFFE6E6E6.toInt()), intArrayOf(0xFF111111.toInt(), 0xFFD71921.toInt(), 0xFFAAAAAA.toInt()), 2),
        Wall("Rings", intArrayOf(0xFF101010.toInt(), 0xFF1E1E24.toInt()), intArrayOf(0xFFFF9F0A.toInt(), 0xFFFF375F.toInt(), 0xFF0A84FF.toInt()), 3),
        Wall("Rose", intArrayOf(0xFF2A0E1A.toInt(), 0xFF5C1A33.toInt()), intArrayOf(0xFFFFB3C7.toInt(), 0xFFFF4F81.toInt(), 0xFFFFE0A3.toInt()), 0),
        Wall("Midnight", intArrayOf(0xFF000000.toInt(), 0xFF0B0B1A.toInt()), intArrayOf(0xFF3A3AFF.toInt(), 0xFF00157A.toInt(), 0xFF6C2BD9.toInt()), 0),
        Wall("Mint", intArrayOf(0xFFE9FFF6.toInt(), 0xFFBFF5E0.toInt()), intArrayOf(0xFF4FD1A5.toInt(), 0xFF9BE7FF.toInt(), 0xFFFFF3B0.toInt()), 0)
    )

    fun render(w: Wall, width: Int, height: Int): Bitmap {
        val b = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val W = width.toFloat()
        val H = height.toFloat()
        p.shader = LinearGradient(0f, 0f, W * 0.3f, H, w.base[0], w.base[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
        when (w.kind) {
            0 -> {
                val spots = listOf(floatArrayOf(0.2f, 0.25f, 0.75f), floatArrayOf(0.85f, 0.45f, 0.7f), floatArrayOf(0.35f, 0.85f, 0.8f))
                for ((i, s) in spots.withIndex()) {
                    val col = w.blobs[i]
                    p.shader = RadialGradient(W * s[0], H * s[1], W * s[2], intArrayOf(col and 0xD0FFFFFF.toInt(), col and 0x00FFFFFF),
                        null, Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, W, H, p)
                }
            }
            1 -> {
                for (k in 0 until 3) {
                    val path = Path()
                    val y0 = H * (0.45f + k * 0.16f)
                    path.moveTo(0f, H)
                    path.lineTo(0f, y0)
                    var x = 0f
                    while (x <= W + 8) {
                        path.lineTo(x, y0 + (sin(x / W * 2 * PI * (1.0 + k * 0.4) + k) * H * 0.05).toFloat())
                        x += 8f
                    }
                    path.lineTo(W, H)
                    path.close()
                    val col = w.blobs[k]
                    p.shader = LinearGradient(0f, y0 - H * 0.05f, 0f, H, col and 0xE6FFFFFF.toInt(), w.base[0], Shader.TileMode.CLAMP)
                    c.drawPath(path, p)
                }
            }
            2 -> {
                p.shader = null
                val step = W / 22f
                val r = step * 0.16f
                var y = step / 2
                var row = 0
                while (y < H) {
                    var x = step / 2
                    var col = 0
                    while (x < W) {
                        val wave = sin(col * 0.35 + row * 0.22)
                        p.color = w.blobs[2]
                        p.alpha = (60 + 80 * (wave + 1) / 2).toInt()
                        c.drawCircle(x, y, r, p)
                        x += step
                        col++
                    }
                    y += step
                    row++
                }
                p.alpha = 255
                p.color = w.blobs[1]
                c.drawCircle(W * 0.78f, H * 0.2f, step * 0.55f, p)
            }
            else -> {
                p.style = Paint.Style.STROKE
                for (k in 0 until 9) {
                    p.color = w.blobs[k % 3]
                    p.alpha = 150 - k * 12
                    p.strokeWidth = W * 0.035f
                    c.drawCircle(W * 0.5f, H * 0.62f, W * (0.12f + k * 0.11f), p)
                }
                p.style = Paint.Style.FILL
                p.alpha = 255
            }
        }
        // a light grain so gradients don't band
        p.shader = null
        val rnd = java.util.Random(7)
        p.color = Color.WHITE
        for (i in 0 until (width * height / 900)) {
            p.alpha = rnd.nextInt(10)
            c.drawPoint(rnd.nextFloat() * W, rnd.nextFloat() * H, p)
        }
        return b
    }

    /** where: 0 home & lock · 1 home · 2 lock */
    fun apply(ctx: Context, w: Wall, where: Int): Boolean {
        val dm = ctx.resources.displayMetrics
        val wm = WallpaperManager.getInstance(ctx)
        val bmp = render(w, dm.widthPixels, dm.heightPixels)
        return try {
            if (Build.VERSION.SDK_INT >= 24) {
                val flags = when (where) {
                    1 -> WallpaperManager.FLAG_SYSTEM
                    2 -> WallpaperManager.FLAG_LOCK
                    else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                wm.setBitmap(bmp, null, true, flags)
            } else {
                wm.setBitmap(bmp)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
