package com.yogi.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Small key/value storage. */
object Store {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("yogilauncher", Context.MODE_PRIVATE)
    }

    fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun setStr(k: String, v: String) { sp.edit().putString(k, v).apply() }
    fun int(k: String, d: Int = 0): Int = sp.getInt(k, d)
    fun setInt(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    fun bool(k: String, d: Boolean = false): Boolean = sp.getBoolean(k, d)
    fun setBool(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    fun obj(k: String): JSONObject = try { JSONObject(str(k, "{}")) } catch (_: Exception) { JSONObject() }
    fun setObj(k: String, o: JSONObject) { setStr(k, o.toString()) }
    fun set(k: String): Set<String> = sp.getStringSet(k, emptySet()) ?: emptySet()
    fun setSet(k: String, v: Set<String>) { sp.edit().putStringSet(k, HashSet(v)).apply() }
}

/** User settings. Changing any of these bumps [version] so the home screen rebuilds. */
object Prefs {
    val cols: Int get() = Store.int("cols", 4)
    val iconScale: Float get() = Store.int("icon_scale", 100) / 100f
    val labels: Boolean get() = Store.bool("labels", true)
    /** 0 squircle · 1 circle · 2 rounded square · 3 pebble */
    val shape: Int get() = Store.int("shape", 0)
    val themed: Boolean get() = Store.bool("themed", false)
    /** 0 number · 1 dot · 2 off */
    val badges: Int get() = Store.int("badges", 0)
    /** 0 notifications · 1 search */
    val swipeDown: Int get() = Store.int("swipe_down", 0)
    val doubleTapLock: Boolean get() = Store.bool("dt_lock", true)
    val clock: Boolean get() = Store.bool("clock", true)
    val autoAdd: Boolean get() = Store.bool("auto_add", true)
    val dockAlpha: Int get() = Store.int("dock_alpha", 30)
    val hidden: Set<String> get() = Store.set("hidden")
    val version: Int get() = Store.int("version", 0)

    fun bump() { Store.setInt("version", version + 1) }
}

/** One app icon on the phone. key = "package/activity". */
class AppInfo(
    val key: String,
    val pkg: String,
    val cls: String,
    val label: String,
    val updated: Long,
    val category: Int,
    val user: UserHandle,
    val info: LauncherActivityInfo
) {
    val component: ComponentName get() = ComponentName(pkg, cls)
}

object Apps {
    @Volatile var list: List<AppInfo> = emptyList()
        private set
    @Volatile private var byKey: Map<String, AppInfo> = emptyMap()

    fun get(key: String): AppInfo? = byKey[key]
    fun forPackage(pkg: String): AppInfo? = list.firstOrNull { it.pkg == pkg }

    /** Reads all launchable apps (runs on a background thread). */
    fun load(ctx: Context): List<AppInfo> {
        val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val me = ctx.packageName
        val out = ArrayList<AppInfo>()
        val users = try {
            val um = ctx.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            um.userProfiles
        } catch (_: Exception) {
            listOf(Process.myUserHandle())
        }
        for (u in users) {
            val acts = try { la.getActivityList(null, u) } catch (_: Exception) { emptyList<LauncherActivityInfo>() }
            for (a in acts) {
                val cn = a.componentName
                if (cn.packageName == me) continue
                val work = u != Process.myUserHandle()
                val key = cn.packageName + "/" + cn.className + if (work) "#" + u.hashCode() else ""
                val ai = a.applicationInfo
                val cat = if (Build.VERSION.SDK_INT >= 26) ai.category else -1
                val label = (a.label?.toString() ?: cn.packageName).trim() + if (work) " 💼" else ""
                out.add(AppInfo(key, cn.packageName, cn.className, label,
                    try { ctx.packageManager.getPackageInfo(cn.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L },
                    cat, u, a))
            }
        }
        out.sortBy { it.label.lowercase() }
        list = out
        byKey = out.associateBy { it.key }
        return out
    }

    fun launch(ctx: Context, app: AppInfo, from: android.view.View?) {
        val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val bounds = from?.let { v ->
            val p = IntArray(2)
            v.getLocationOnScreen(p)
            Rect(p[0], p[1], p[0] + v.width, p[1] + v.height)
        }
        val opts = from?.let {
            android.app.ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        try {
            la.startMainActivity(app.component, app.user, bounds, opts)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            } catch (_: Exception) {
                ctx.toast("Can't open ${app.label}")
                return
            }
        }
        val o = Store.obj("launches")
        o.put(app.key, o.optInt(app.key) + 1)
        Store.setObj("launches", o)
    }

    fun launches(key: String): Int = Store.obj("launches").optInt(key)

    /** Most opened apps (for suggestions). */
    fun top(n: Int): List<AppInfo> {
        val o = Store.obj("launches")
        val hidden = Prefs.hidden
        return list.filter { o.optInt(it.key) > 0 && it.key !in hidden }.sortedByDescending { o.optInt(it.key) }.take(n)
    }

    fun appInfo(ctx: Context, app: AppInfo) {
        try {
            val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            la.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (_: Exception) {
            ctx.toast("Can't open app info")
        }
    }

    fun uninstall(ctx: Context, app: AppInfo) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ctx.toast("Can't uninstall this app")
        }
    }

    fun isSystem(app: AppInfo): Boolean = (app.info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    fun categoryName(c: Int): String? = when (c) {
        ApplicationInfo.CATEGORY_GAME -> "Games"
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos"
        ApplicationInfo.CATEGORY_NEWS -> "News"
        ApplicationInfo.CATEGORY_MAPS -> "Travel"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
        else -> null
    }
}

/** Icon outlines. All drawn as smooth paths, so every icon has the same shape. */
object Shapes {
    fun path(shape: Int, s: Float, out: Path = Path()): Path {
        out.reset()
        when (shape) {
            1 -> out.addCircle(s / 2, s / 2, s / 2, Path.Direction.CW)
            2 -> out.addRoundRect(RectF(0f, 0f, s, s), s * 0.18f, s * 0.18f, Path.Direction.CW)
            3 -> superellipse(out, 3.2, s)
            else -> superellipse(out, 5.0, s)   // continuous-curve "squircle"
        }
        return out
    }

    private fun superellipse(p: Path, n: Double, s: Float) {
        val r = s / 2.0
        val steps = 120
        for (i in 0 until steps) {
            val t = 2 * PI * i / steps
            val c = cos(t)
            val sn = sin(t)
            val x = r + r * Math.signum(c) * abs(c).pow(2.0 / n)
            val y = r + r * Math.signum(sn) * abs(sn).pow(2.0 / n)
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close()
    }
}

/**
 * Turns every app's icon into the same clean shape.
 * Adaptive icons are cut to the shape; old square/odd icons sit on a white tile.
 * Results are cached in memory and on disk, so the home screen opens instantly.
 */
object Icons {
    private val mem: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 10).toInt().coerceAtMost(48 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Volatile var size = 144

    private fun cacheKey(app: AppInfo): String =
        "${app.key}|${app.updated}|${Prefs.shape}|${Prefs.themed}|$size"

    fun cached(app: AppInfo): Bitmap? = mem.get(cacheKey(app))

    fun clearMemory() { mem.evictAll() }

    /** Gets the finished icon (memory → disk → render). Safe on any thread. */
    fun get(ctx: Context, app: AppInfo): Bitmap {
        val k = cacheKey(app)
        mem.get(k)?.let { return it }
        val file = File(File(ctx.cacheDir, "icons").apply { mkdirs() }, Integer.toHexString(k.hashCode()) + ".png")
        if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.let { b ->
                    if (b.width == size) {
                        mem.put(k, b)
                        return b
                    }
                }
            } catch (_: Exception) {
            }
        }
        val d = try { app.info.getIcon(0) } catch (_: Exception) { null }
        val b = if (d != null) render(d, size) else placeholder(app.label, size)
        mem.put(k, b)
        try {
            FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
        }
        return b
    }

    fun render(d: Drawable, s: Int): Bitmap {
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = Shapes.path(Prefs.shape, s.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val themed = Prefs.themed
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            val e = (s * 0.25f).toInt()
            val mono = if (themed && Build.VERSION.SDK_INT >= 33) d.monochrome else null
            c.save()
            c.clipPath(path)
            if (themed) {
                c.drawColor(0xFF1F2A36.toInt())
                val layer = (mono ?: d.foreground)?.constantState?.newDrawable()?.mutate()
                if (layer != null) {
                    layer.setBounds(-e, -e, s + e, s + e)
                    if (mono != null) layer.colorFilter = PorterDuffColorFilter(0xFFBFE3FF.toInt(), PorterDuff.Mode.SRC_IN)
                    else layer.colorFilter = tintMatrix()
                    layer.draw(c)
                }
            } else {
                d.background?.let { it.setBounds(-e, -e, s + e, s + e); it.draw(c) }
                d.foreground?.let { it.setBounds(-e, -e, s + e, s + e); it.draw(c) }
            }
            c.restore()
        } else {
            val raw = toBitmap(d, s)
            if (fullBleed(raw)) {
                c.save()
                c.clipPath(path)
                if (themed) paint.colorFilter = tintMatrix()
                c.drawBitmap(raw, null, Rect(0, 0, s, s), paint)
                c.restore()
            } else {
                paint.color = if (themed) 0xFF1F2A36.toInt() else Color.WHITE
                c.drawPath(path, paint)
                val inset = (s * 0.17f).toInt()
                val p2 = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                if (themed) p2.colorFilter = tintMatrix()
                c.drawBitmap(raw, null, Rect(inset, inset, s - inset, s - inset), p2)
            }
        }
        return out
    }

    /** Soft blue-grey tint for "themed" icons. */
    private fun tintMatrix(): ColorMatrixColorFilter {
        val m = ColorMatrix()
        m.setSaturation(0f)
        val tint = ColorMatrix(floatArrayOf(
            0.75f, 0f, 0f, 0f, 40f,
            0f, 0.85f, 0f, 0f, 60f,
            0f, 0f, 1f, 0f, 80f,
            0f, 0f, 0f, 1f, 0f
        ))
        m.postConcat(tint)
        return ColorMatrixColorFilter(m)
    }

    private fun toBitmap(d: Drawable, s: Int): Bitmap {
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, s, s)
        d.draw(c)
        return b
    }

    /** True when the icon already fills its square (no transparent corners). */
    private fun fullBleed(b: Bitmap): Boolean {
        val m = (b.width * 0.06f).toInt()
        val w = b.width - 1 - m
        val pts = listOf(m to m, w to m, m to w, w to w)
        return pts.all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 220 }
    }

    fun placeholder(label: String, s: Int): Bitmap {
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF3A3A3C.toInt()
        c.drawPath(Shapes.path(Prefs.shape, s.toFloat()), p)
        p.color = Color.WHITE
        p.textSize = s * 0.45f
        p.textAlign = Paint.Align.CENTER
        c.drawText(label.take(1).uppercase(), s / 2f, s * 0.66f, p)
        return out
    }

    /** Icon of an app shortcut (long-press menu). */
    fun small(d: Drawable?, s: Int): Bitmap? = d?.let { render(it, s) }
}

/** JSON helper. */
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
