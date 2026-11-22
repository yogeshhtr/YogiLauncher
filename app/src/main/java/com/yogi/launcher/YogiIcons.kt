package com.yogi.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Yogi icons": our own built-in icon set for the apps found on almost every phone
 * (phone, messages, camera, settings, clock, maps, browser…). Each is a clean white line glyph on a
 * bright gradient, cut to your icon shape. Apps without a Yogi icon keep their own icon.
 */
object YogiIcons {
    const val PACK = "yogi"

    private class Style(val top: Int, val bottom: Int)

    private val styles = mapOf(
        "phone" to Style(0xFF5CF07A.toInt(), 0xFF1DB954.toInt()),
        "messages" to Style(0xFF6FE38B.toInt(), 0xFF17B26A.toInt()),
        "chat" to Style(0xFF4BE07F.toInt(), 0xFF0E9F6E.toInt()),
        "camera" to Style(0xFF8E8E93.toInt(), 0xFF3A3A3C.toInt()),
        "settings" to Style(0xFFAEB4BE.toInt(), 0xFF5B616B.toInt()),
        "clock" to Style(0xFF2C2C2E.toInt(), 0xFF000000.toInt()),
        "calculator" to Style(0xFFFFB340.toInt(), 0xFFFF7A00.toInt()),
        "calendar" to Style(0xFFFFFFFF.toInt(), 0xFFECECEC.toInt()),
        "contacts" to Style(0xFFB0B7C3.toInt(), 0xFF6B7280.toInt()),
        "files" to Style(0xFF5AC8FA.toInt(), 0xFF007AFF.toInt()),
        "gallery" to Style(0xFFFF9A8B.toInt(), 0xFFFF5E62.toInt()),
        "maps" to Style(0xFF7CE38B.toInt(), 0xFF34A853.toInt()),
        "browser" to Style(0xFF64B5FF.toInt(), 0xFF1A73E8.toInt()),
        "mail" to Style(0xFF5EB0FF.toInt(), 0xFF0A63D6.toInt()),
        "music" to Style(0xFFFF6B8B.toInt(), 0xFFFA2D48.toInt()),
        "notes" to Style(0xFFFFE066.toInt(), 0xFFFFC300.toInt()),
        "weather" to Style(0xFF6EC6FF.toInt(), 0xFF2F80ED.toInt()),
        "store" to Style(0xFF7B8CFF.toInt(), 0xFF4A5BFF.toInt()),
        "video" to Style(0xFFFF5A5A.toInt(), 0xFFD91E1E.toInt()),
        "recorder" to Style(0xFFFF6B6B.toInt(), 0xFFB3261E.toInt()),
        "wallet" to Style(0xFF3A3A3C.toInt(), 0xFF111111.toInt()),
        "compass" to Style(0xFF3A3A3C.toInt(), 0xFF0C0C0C.toInt()),
        "health" to Style(0xFFFFFFFF.toInt(), 0xFFF2F2F7.toInt()),
        "downloads" to Style(0xFF6DD5FA.toInt(), 0xFF2980B9.toInt()),
        "translate" to Style(0xFF7EA6FF.toInt(), 0xFF3C6CF0.toInt()),
        "games" to Style(0xFFB06AFF.toInt(), 0xFF6A1BDB.toInt())
    )

    /** Which Yogi icon fits this app, or null. */
    fun kind(app: AppInfo): String? {
        val p = app.pkg.lowercase()
        val l = app.label.lowercase().replace(" 💼", "")
        fun pk(vararg w: String) = w.any { p.contains(it) }
        fun lb(vararg w: String) = w.any { l == it || l.startsWith("$it ") }
        return when {
            pk("dialer", ".phone", "incallui") || lb("phone") -> "phone"
            pk("whatsapp", "telegram", "messenger", "signal", "viber", ".imo", "discord") -> "chat"
            pk("messaging", ".mms", ".sms") || lb("messages", "messaging") -> "messages"
            pk("camera") || lb("camera") -> "camera"
            p == "com.android.settings" || pk("settings") && lb("settings") -> "settings"
            pk("deskclock", ".clock") || lb("clock") -> "clock"
            pk("calculator") || lb("calculator") -> "calculator"
            pk("calendar") || lb("calendar") -> "calendar"
            pk("contacts") || lb("contacts") -> "contacts"
            pk("documentsui", "filemanager", "fileexplorer", ".files", "myfiles") || lb("files", "file manager") -> "files"
            pk("gallery", ".photos", "photos") || lb("gallery", "photos") -> "gallery"
            pk(".maps", "maps") || lb("maps") -> "maps"
            pk("chrome", "browser", "firefox", "opera", "brave", "edge", "duckduckgo", "sbrowser") -> "browser"
            pk("gmail", ".email", "mail", "outlook") || lb("mail", "email", "gmail") -> "mail"
            pk("music", "spotify", "soundcloud") || lb("music") -> "music"
            pk(".keep", "notes", "memo", "notion") || lb("notes", "keep notes") -> "notes"
            pk("weather") || lb("weather") -> "weather"
            pk("vending", "appstore", "market", "galaxyapps", "appmarket") || lb("play store", "app store", "galaxy store") -> "store"
            pk("youtube", "netflix", "videoplayer", ".video", "mxtech", "vlc", "primevideo") -> "video"
            pk("recorder", "soundrecorder") || lb("recorder") -> "recorder"
            pk("wallet", "esewa", "khalti", "paytm", "phonepe", ".pay") || lb("wallet") -> "wallet"
            pk("compass") || lb("compass") -> "compass"
            pk("health", "fitness", ".fit") || lb("health", "fit") -> "health"
            pk("downloads", "download") || lb("downloads") -> "downloads"
            pk("translate") || lb("translate") -> "translate"
            pk("games", "playgames") || lb("games") -> "games"
            else -> null
        }
    }

    /** The finished icon in the user's shape, or null when there is no Yogi icon for this app. */
    fun render(app: AppInfo, s: Int): Bitmap? {
        val k = kind(app) ?: return null
        val st = styles[k] ?: return null
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val shape = Shapes.path(Prefs.shape, s.toFloat())
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), st.top, st.bottom, Shader.TileMode.CLAMP)
        c.drawPath(shape, bg)
        c.save()
        c.clipPath(shape)
        c.scale(s.toFloat(), s.toFloat())
        glyph(c, k)
        c.restore()
        return b
    }

    private fun stroke(w: Float = 0.065f, color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private fun fill(color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    /** Glyphs are drawn in a 0…1 box. */
    private fun glyph(c: Canvas, k: String) {
        val w = stroke()
        val f = fill()
        when (k) {
            "phone" -> {
                val p = Path()
                p.moveTo(0.34f, 0.26f)
                p.cubicTo(0.24f, 0.36f, 0.30f, 0.56f, 0.44f, 0.68f)
                p.cubicTo(0.55f, 0.77f, 0.68f, 0.80f, 0.75f, 0.70f)
                c.drawPath(p, stroke(0.1f))
                c.drawLine(0.31f, 0.24f, 0.40f, 0.33f, stroke(0.13f))
                c.drawLine(0.68f, 0.66f, 0.77f, 0.73f, stroke(0.13f))
            }
            "messages", "chat" -> {
                val r = RectF(0.22f, 0.27f, 0.78f, 0.67f)
                c.drawRoundRect(r, 0.2f, 0.2f, f)
                val t = Path()
                t.moveTo(0.32f, 0.6f)
                t.lineTo(0.26f, 0.78f)
                t.lineTo(0.47f, 0.64f)
                t.close()
                c.drawPath(t, f)
                if (k == "chat") {
                    val d = fill(styles["chat"]!!.bottom)
                    for (i in 0..2) c.drawCircle(0.37f + i * 0.13f, 0.47f, 0.035f, d)
                }
            }
            "camera" -> {
                c.drawRoundRect(RectF(0.2f, 0.33f, 0.8f, 0.72f), 0.09f, 0.09f, w)
                c.drawRoundRect(RectF(0.38f, 0.26f, 0.56f, 0.34f), 0.03f, 0.03f, f)
                c.drawCircle(0.5f, 0.525f, 0.12f, w)
                c.drawCircle(0.71f, 0.41f, 0.025f, f)
            }
            "settings" -> {
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    c.drawLine(0.5f + (cos(a) * 0.2).toFloat(), 0.5f + (sin(a) * 0.2).toFloat(),
                        0.5f + (cos(a) * 0.29).toFloat(), 0.5f + (sin(a) * 0.29).toFloat(), stroke(0.1f))
                }
                c.drawCircle(0.5f, 0.5f, 0.2f, f)
                c.drawCircle(0.5f, 0.5f, 0.08f, fill(styles["settings"]!!.bottom))
            }
            "clock" -> {
                c.drawCircle(0.5f, 0.5f, 0.3f, fill(Color.WHITE))
                val h = stroke(0.05f, Color.BLACK)
                val cal = java.util.Calendar.getInstance()
                val mn = cal.get(java.util.Calendar.MINUTE)
                val ha = Math.toRadians((cal.get(java.util.Calendar.HOUR) + mn / 60.0) * 30)
                val ma = Math.toRadians(mn * 6.0)
                c.drawLine(0.5f, 0.5f, 0.5f + (sin(ha) * 0.14).toFloat(), 0.5f - (cos(ha) * 0.14).toFloat(), h)
                c.drawLine(0.5f, 0.5f, 0.5f + (sin(ma) * 0.22).toFloat(), 0.5f - (cos(ma) * 0.22).toFloat(), stroke(0.035f, Color.BLACK))
                c.drawCircle(0.5f, 0.5f, 0.03f, fill(0xFFFF9F0A.toInt()))
            }
            "calculator" -> {
                val p = stroke(0.07f)
                c.drawLine(0.27f, 0.33f, 0.43f, 0.33f, p); c.drawLine(0.35f, 0.25f, 0.35f, 0.41f, p)
                c.drawLine(0.57f, 0.33f, 0.73f, 0.33f, p)
                c.drawLine(0.29f, 0.6f, 0.41f, 0.72f, p); c.drawLine(0.41f, 0.6f, 0.29f, 0.72f, p)
                c.drawLine(0.57f, 0.62f, 0.73f, 0.62f, p); c.drawLine(0.57f, 0.71f, 0.73f, 0.71f, p)
            }
            "calendar" -> {
                val cal = java.util.Calendar.getInstance()
                val t = Paint(Paint.ANTI_ALIAS_FLAG)
                t.textAlign = Paint.Align.CENTER
                t.color = 0xFFFF3B30.toInt()
                t.typeface = Typeface.DEFAULT_BOLD
                t.textSize = 0.13f
                c.drawText(java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.time).uppercase(), 0.5f, 0.3f, t)
                t.color = 0xFF1C1C1E.toInt()
                t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                t.textSize = 0.42f
                c.drawText(cal.get(java.util.Calendar.DAY_OF_MONTH).toString(), 0.5f, 0.72f, t)
            }
            "contacts" -> {
                c.drawCircle(0.5f, 0.4f, 0.12f, f)
                c.drawArc(RectF(0.27f, 0.56f, 0.73f, 0.96f), 180f, 180f, true, f)
            }
            "files" -> {
                val p = Path()
                p.moveTo(0.2f, 0.32f); p.lineTo(0.42f, 0.32f); p.lineTo(0.48f, 0.38f); p.lineTo(0.8f, 0.38f)
                p.lineTo(0.8f, 0.72f); p.lineTo(0.2f, 0.72f); p.close()
                c.drawPath(p, f)
                c.drawLine(0.2f, 0.45f, 0.8f, 0.45f, stroke(0.03f, styles["files"]!!.bottom))
            }
            "gallery" -> {
                val m = Path()
                m.moveTo(0.18f, 0.74f); m.lineTo(0.42f, 0.44f); m.lineTo(0.56f, 0.6f); m.lineTo(0.64f, 0.52f); m.lineTo(0.82f, 0.74f); m.close()
                c.drawPath(m, f)
                c.drawCircle(0.66f, 0.33f, 0.08f, f)
            }
            "maps" -> {
                val p = Path()
                p.addCircle(0.5f, 0.42f, 0.19f, Path.Direction.CW)
                c.drawPath(p, f)
                val t = Path()
                t.moveTo(0.33f, 0.5f); t.lineTo(0.5f, 0.8f); t.lineTo(0.67f, 0.5f); t.close()
                c.drawPath(t, f)
                c.drawCircle(0.5f, 0.42f, 0.075f, fill(styles["maps"]!!.bottom))
            }
            "browser" -> {
                c.drawCircle(0.5f, 0.5f, 0.28f, w)
                c.drawOval(RectF(0.38f, 0.22f, 0.62f, 0.78f), stroke(0.045f))
                c.drawLine(0.22f, 0.5f, 0.78f, 0.5f, stroke(0.045f))
                c.drawLine(0.27f, 0.37f, 0.73f, 0.37f, stroke(0.035f))
                c.drawLine(0.27f, 0.63f, 0.73f, 0.63f, stroke(0.035f))
            }
            "mail" -> {
                c.drawRoundRect(RectF(0.2f, 0.3f, 0.8f, 0.7f), 0.07f, 0.07f, f)
                val v = Path()
                v.moveTo(0.24f, 0.35f); v.lineTo(0.5f, 0.54f); v.lineTo(0.76f, 0.35f)
                c.drawPath(v, stroke(0.05f, styles["mail"]!!.bottom))
            }
            "music" -> {
                c.drawCircle(0.37f, 0.68f, 0.09f, f)
                c.drawCircle(0.66f, 0.62f, 0.09f, f)
                c.drawLine(0.45f, 0.68f, 0.45f, 0.3f, stroke(0.06f))
                c.drawLine(0.74f, 0.62f, 0.74f, 0.24f, stroke(0.06f))
                c.drawLine(0.45f, 0.3f, 0.74f, 0.24f, stroke(0.09f))
            }
            "notes" -> {
                c.drawRoundRect(RectF(0.25f, 0.2f, 0.75f, 0.8f), 0.06f, 0.06f, f)
                val l = stroke(0.035f, 0xFFC9A400.toInt())
                for (i in 0..3) c.drawLine(0.33f, 0.36f + i * 0.11f, 0.67f, 0.36f + i * 0.11f, l)
            }
            "weather" -> {
                c.drawCircle(0.42f, 0.4f, 0.14f, fill(0xFFFFD60A.toInt()))
                c.drawCircle(0.5f, 0.6f, 0.13f, f)
                c.drawCircle(0.65f, 0.58f, 0.11f, f)
                c.drawCircle(0.36f, 0.64f, 0.09f, f)
                c.drawRect(0.36f, 0.6f, 0.68f, 0.73f, f)
            }
            "store" -> {
                c.drawRoundRect(RectF(0.24f, 0.36f, 0.76f, 0.78f), 0.07f, 0.07f, f)
                c.drawArc(RectF(0.37f, 0.22f, 0.63f, 0.5f), 180f, 180f, false, stroke(0.05f))
                val t = Path()
                t.moveTo(0.45f, 0.48f); t.lineTo(0.6f, 0.57f); t.lineTo(0.45f, 0.66f); t.close()
                c.drawPath(t, fill(styles["store"]!!.bottom))
            }
            "video" -> {
                val t = Path()
                t.moveTo(0.4f, 0.32f); t.lineTo(0.7f, 0.5f); t.lineTo(0.4f, 0.68f); t.close()
                val p = stroke(0.08f)
                p.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(t, p)
            }
            "recorder" -> {
                c.drawRoundRect(RectF(0.41f, 0.2f, 0.59f, 0.56f), 0.09f, 0.09f, f)
                c.drawArc(RectF(0.31f, 0.32f, 0.69f, 0.66f), 0f, 180f, false, w)
                c.drawLine(0.5f, 0.66f, 0.5f, 0.78f, w)
            }
            "wallet" -> {
                val cols = intArrayOf(0xFF34C759.toInt(), 0xFFFFCC00.toInt(), 0xFFFF9500.toInt(), 0xFF0A84FF.toInt())
                for (i in 0..3) c.drawRoundRect(RectF(0.22f, 0.25f + i * 0.07f, 0.78f, 0.55f + i * 0.07f), 0.05f, 0.05f, fill(cols[i]))
                c.drawRoundRect(RectF(0.2f, 0.5f, 0.8f, 0.78f), 0.06f, 0.06f, fill(0xFF2C2C2E.toInt()))
            }
            "compass" -> {
                c.drawCircle(0.5f, 0.5f, 0.29f, stroke(0.03f))
                val n = Path()
                n.moveTo(0.5f, 0.26f); n.lineTo(0.57f, 0.5f); n.lineTo(0.43f, 0.5f); n.close()
                c.drawPath(n, fill(0xFFFF3B30.toInt()))
                val s2 = Path()
                s2.moveTo(0.5f, 0.74f); s2.lineTo(0.57f, 0.5f); s2.lineTo(0.43f, 0.5f); s2.close()
                c.drawPath(s2, f)
            }
            "health" -> {
                val h = Path()
                h.moveTo(0.5f, 0.74f)
                h.cubicTo(0.12f, 0.5f, 0.24f, 0.2f, 0.5f, 0.36f)
                h.cubicTo(0.76f, 0.2f, 0.88f, 0.5f, 0.5f, 0.74f)
                c.drawPath(h, fill(0xFFFF2D55.toInt()))
            }
            "downloads" -> {
                c.drawLine(0.5f, 0.24f, 0.5f, 0.62f, w)
                val a = Path()
                a.moveTo(0.35f, 0.48f); a.lineTo(0.5f, 0.63f); a.lineTo(0.65f, 0.48f)
                c.drawPath(a, w)
                c.drawLine(0.28f, 0.75f, 0.72f, 0.75f, w)
            }
            "translate" -> {
                val t = Paint(Paint.ANTI_ALIAS_FLAG)
                t.color = Color.WHITE
                t.typeface = Typeface.DEFAULT_BOLD
                t.textSize = 0.32f
                c.drawText("A", 0.22f, 0.52f, t)
                t.textSize = 0.28f
                c.drawText("文", 0.48f, 0.78f, t)
            }
            "games" -> {
                c.drawRoundRect(RectF(0.18f, 0.36f, 0.82f, 0.68f), 0.16f, 0.16f, f)
                val g = stroke(0.05f, styles["games"]!!.bottom)
                c.drawLine(0.3f, 0.52f, 0.42f, 0.52f, g); c.drawLine(0.36f, 0.46f, 0.36f, 0.58f, g)
                val d = fill(styles["games"]!!.bottom)
                c.drawCircle(0.62f, 0.49f, 0.03f, d); c.drawCircle(0.7f, 0.56f, 0.03f, d)
            }
        }
    }
}
