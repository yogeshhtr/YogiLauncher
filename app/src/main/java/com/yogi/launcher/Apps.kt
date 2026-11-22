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

    /** Everything, for a backup file. */
    fun export(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    /** Puts a backup back (keeps keys that are not in the backup). */
    fun restore(o: JSONObject) {
        val ed = sp.edit()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "s" -> ed.putString(k, e.optString("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "set" -> {
                    val a = e.optJSONArray("v") ?: continue
                    ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet())
                }
            }
        }
        ed.commit()
    }
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
    /** double-tap on empty space opens search */
    val doubleTapLock: Boolean get() = Store.bool("dt_lock", true)
    val clock: Boolean get() = Store.bool("clock", true)
    val autoAdd: Boolean get() = Store.bool("auto_add", true)
    val dockAlpha: Int get() = Store.int("dock_alpha", 30)
    val hidden: Set<String> get() = Store.set("hidden")

    // v4: layout, text, widgets, drawer, icon pack
    /** 0 compact · 1 normal · 2 roomy (space between rows) */
    val spacing: Int get() = Store.int("spacing", 1)
    /** 0 none · 1 normal · 2 wide (space at the screen sides) */
    val margins: Int get() = Store.int("margins", 1)
    /** label size in sp */
    val labelSize: Int get() = Store.int("label_size", 12)
    /** 0 default · 1 light · 2 medium · 3 condensed · 4 serif · 5 mono */
    val font: Int get() = Store.int("font", 0)
    /** 0 white with shadow · 1 black (for light wallpapers) */
    val labelDark: Boolean get() = Store.bool("label_dark", false)
    /** 0 big · 1 dot-matrix · 2 analog · 3 stacked (style for new clock widgets) */
    val clockStyle: Int get() = Store.int("clock_style", 0)
    /** clock & calendar app icons show the real time and date */
    val liveIcons: Boolean get() = Store.bool("live_icons", true)
    /** new folders are 2 × 2 (HyperOS style) */
    val bigFolders: Boolean get() = Store.bool("big_folders", false)
    /** App Library: 0 A–Z without headers · 1 by letter · 2 by colour */
    /** App Library: 0 none · 1 letters · 2 colours · 3 iOS-style category boxes */
    val drawerGroup: Int get() = Store.int("drawer_group", 3)
    val drawerCols: Int get() = Store.int("drawer_cols", 4)
    /** Nothing-style category chips on top of the App Library */
    val drawerChips: Boolean get() = Store.bool("drawer_chips", true)
    /** package of the chosen icon pack ("" = none) */
    val iconPack: String get() = Store.str("icon_pack", "")

    // v6: App Library, private space, folders, widgets
    /** swipe up opens: 0 App Library (category boxes) · 1 All apps list */
    val drawerOpen: Int get() = Store.int("drawer_open", 0)
    /** letter headers + A–Z bar in the All apps list */
    val drawerLetters: Boolean get() = Store.bool("drawer_letters", Store.int("drawer_group", 3) != 0)
    /** tapping a category box: 0 vertical grid · 1 horizontal pages · 2 inside the list */
    val libFolder: Int get() = Store.int("lib_folder", 0)
    /** Private tab in the App Library. Off: hidden until you search "private" (like Pixel). */
    val showPrivate: Boolean get() = Store.bool("show_private", true)
    /** unlocked private apps also appear in search results */
    val privateInSearch: Boolean get() = Store.bool("private_search", true)
    /** home folders open as: 0 iOS pages (3 × 3, swipe) · 1 one scrolling grid */
    val folderStyle: Int get() = Store.int("folder_style", 0)
    /** folder icon on the home screen: 0 3 × 3 minis (iOS) · 1 2 × 2 bigger minis */
    val folderPreview: Int get() = Store.int("folder_preview", 0)
    /** Android widgets get a glass background */
    val widgetGlass: Boolean get() = Store.bool("widget_glass", true)
    /** One UI: lock the home screen layout (no moving, removing or edit mode) */
    val lockLayout: Boolean get() = Store.bool("lock_layout", false)
    /** OxygenOS: hidden apps live in a Hidden space (two-finger swipe up / search "hidden") */
    val hiddenLock: Boolean get() = Store.bool("hidden_lock", true)
    /** blur behind folders (home + App Library categories): 0 off · 1 light · 2 medium · 3 strong · 4 max */
    val blurFolder: Int get() = Store.int("blur_folder", 2)
    /** blur behind the App Library */
    val blurLibrary: Int get() = Store.int("blur_library", 2)
    /** how see-through the App Library is: 0 clear · 1 glass · 2 dark */
    val libraryTint: Int get() = Store.int("library_tint", 1)
    /** long-press empty space: 0 menu (One UI) · 1 edit mode directly */
    val longPress: Int get() = Store.int("long_press", 0)
    /** wallpaper dimming over the home screen, 0–60 % */
    val wallDim: Int get() = Store.int("wall_dim", 0)

    fun typeface(): android.graphics.Typeface = when (font) {
        1 -> android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        2 -> android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        3 -> android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
        4 -> android.graphics.Typeface.SERIF
        5 -> android.graphics.Typeface.MONOSPACE
        else -> android.graphics.Typeface.DEFAULT
    }
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
    val info: LauncherActivityInfo,
    /** Profiles.PERSONAL · WORK · PRIVATE */
    val profile: Int = 0
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
        Profiles.refresh(ctx)
        for (u in Profiles.users(ctx)) {
            val type = Profiles.type(ctx, u)
            // a locked private space shows nothing (like the Pixel launcher)
            if (type == Profiles.PRIVATE && Profiles.isLocked(ctx, u)) continue
            val acts = try { la.getActivityList(null, u) } catch (_: Exception) { emptyList<LauncherActivityInfo>() }
            for (a in acts) {
                val cn = a.componentName
                if (cn.packageName == me) continue
                val other = type != Profiles.PERSONAL
                val key = cn.packageName + "/" + cn.className + if (other) "#" + u.hashCode() else ""
                val ai = a.applicationInfo
                val cat = if (Build.VERSION.SDK_INT >= 26) ai.category else -1
                val label = (a.label?.toString() ?: cn.packageName).trim() + if (type == Profiles.WORK) " 💼" else ""
                out.add(AppInfo(key, cn.packageName, cn.className, label,
                    try { ctx.packageManager.getPackageInfo(cn.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L },
                    cat, u, a, type))
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
            if (Build.VERSION.SDK_INT >= 23) {
                android.app.ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle()
            } else {
                android.app.ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
            }
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
        // smart suggestions: what you open at this time of day, and lately
        val hb = Store.obj("launch_h")
        val hk = app.key + "#" + bucket()
        hb.put(hk, hb.optInt(hk) + 1)
        Store.setObj("launch_h", hb)
        val t = Store.obj("launch_t")
        t.put(app.key, System.currentTimeMillis())
        Store.setObj("launch_t", t)
    }

    /** 3-hour part of the day (0..7) */
    private fun bucket(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) / 3

    /** Apps you are likely to open now: same time of day counts most, then recent, then overall. */
    fun smart(n: Int): List<AppInfo> {
        val o = Store.obj("launches")
        val hb = Store.obj("launch_h")
        val t = Store.obj("launch_t")
        val b = bucket()
        val near = listOf((b + 7) % 8, (b + 1) % 8)
        val now = System.currentTimeMillis()
        val hidden = Prefs.hidden
        fun score(a: AppInfo): Double {
            val total = o.optInt(a.key)
            if (total == 0) return 0.0
            val here = hb.optInt(a.key + "#" + b)
            val around = near.sumOf { hb.optInt(a.key + "#" + it) }
            val ago = (now - t.optLong(a.key)) / 3600000.0
            val recent = if (ago < 1) 6.0 else if (ago < 6) 3.0 else if (ago < 24) 1.0 else 0.0
            return here * 4.0 + around * 1.5 + recent + kotlin.math.ln(1.0 + total) * 2.0
        }
        return list.filter { o.optInt(it.key) > 0 && it.key !in hidden && it.profile != Profiles.PRIVATE }
            .sortedByDescending { score(it) }.take(n)
    }

    fun launches(key: String): Int = Store.obj("launches").optInt(key)

    /** Most opened apps (for suggestions). */
    fun top(n: Int): List<AppInfo> {
        val o = Store.obj("launches")
        val hidden = Prefs.hidden
        return list.filter { o.optInt(it.key) > 0 && it.key !in hidden && it.profile != Profiles.PRIVATE }
            .sortedByDescending { o.optInt(it.key) }.take(n)
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
            appInfo(ctx, app)
        }
    }

    fun isSystem(app: AppInfo): Boolean = (app.info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private val catCache = HashMap<String, String>()

    /** Nothing-OS-style drawer category for an app. */
    fun drawerCategory(app: AppInfo): String = synchronized(catCache) {
        catCache.getOrPut(app.key) {
            val p = app.pkg.lowercase()
            val l = app.label.lowercase()
            fun has(vararg w: String) = w.any { p.contains(it) || l.contains(it) }
            when {
                has("whatsapp", "messenger", "facebook", "instagram", "telegram", "viber", "signal", "snapchat", "twitter",
                    "tiktok", "musically", "discord", "imo", "linkedin", "reddit", "threads", "mms", "messaging", "sms") -> "Social"
                has("youtube", "spotify", "netflix", "music", "video", "player", "podcast", "tv", "prime", "hotstar", "radio") -> "Media"
                has("camera", "gallery", "photo", "snapseed", "lightroom", "picsart") -> "Photos"
                has("esewa", "khalti", "bank", "wallet", "pay", "finance", "money", "fonepay", "imepay", "binance", "trading") -> "Finance"
                has("daraz", "amazon", "shop", "store", "mart", "food", "foodmandu", "pathao", "indrive", "uber") -> "Shopping"
                has("maps", "travel", "booking", "airbnb", "weather", "flight") -> "Travel"
                has("gmail", "mail", "docs", "sheets", "slides", "drive", "office", "word", "excel", "notion", "calendar",
                    "keep", "zoom", "teams", "meet", "slack", "classroom", "pdf") -> "Productivity"
                has("settings", "clock", "calculator", "files", "contacts", "dialer", "phone", "recorder", "compass",
                    "flashlight", "launcher", "vending", "chrome", "browser", "security", "cleaner", "backup", "yogi") -> "Tools"
                else -> when (app.category) {
                    ApplicationInfo.CATEGORY_GAME -> "Games"
                    ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                    ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Media"
                    ApplicationInfo.CATEGORY_IMAGE -> "Photos"
                    ApplicationInfo.CATEGORY_NEWS -> "Reading"
                    ApplicationInfo.CATEGORY_MAPS -> "Travel"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                    else -> if (isSystem(app)) "Tools" else "Other"
                }
            }
        }
    }

    val CATEGORY_ORDER = listOf("Social", "Media", "Photos", "Games", "Productivity", "Finance", "Shopping", "Travel", "Reading", "Tools", "Other")

    private val colourCache = HashMap<String, Int>()

    /** Colour group of an icon: 0 red · 1 orange · 2 yellow · 3 green · 4 teal · 5 blue · 6 purple · 7 pink · 8 black & white. */
    fun colourGroup(b: Bitmap, key: String): Int = synchronized(colourCache) {
        colourCache.getOrPut(key) {
            val hist = FloatArray(9)
            val hsv = FloatArray(3)
            val step = (b.width / 12).coerceAtLeast(1)
            var y = step / 2
            while (y < b.height) {
                var x = step / 2
                while (x < b.width) {
                    val c = b.getPixel(x, y)
                    if (Color.alpha(c) > 200) {
                        Color.colorToHSV(c, hsv)
                        if (hsv[1] < 0.22f || hsv[2] < 0.18f) hist[8] += 0.35f else {
                            val hue = hsv[0]
                            val g = when {
                                hue < 15 || hue >= 345 -> 0
                                hue < 40 -> 1
                                hue < 70 -> 2
                                hue < 160 -> 3
                                hue < 195 -> 4
                                hue < 255 -> 5
                                hue < 290 -> 6
                                else -> 7
                            }
                            hist[g] += hsv[1] * hsv[2]
                        }
                    }
                    x += step
                }
                y += step
            }
            hist.indices.maxByOrNull { hist[it] } ?: 8
        }
    }

    val COLOUR_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Teal", "Blue", "Purple", "Pink", "Black & white")
    val COLOUR_DOTS = listOf(0xFFFF453A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF40C8E0.toInt(),
        0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFF8E8E93.toInt())

    private val clockPkgs = setOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
        "com.oneplus.deskclock", "com.coloros.alarmclock", "com.oplus.alarmclock", "com.nothing.clock", "com.motorola.timeweatherwidget",
        "com.miui.clock", "com.huawei.deskclock", "com.vivo.alarmclock", "com.transsion.deskclock")
    private val calendarPkgs = setOf("com.google.android.calendar", "com.android.calendar", "com.samsung.android.calendar",
        "com.miui.calendar", "com.coloros.calendar", "com.oneplus.calendar", "com.nothing.calendar", "com.xiaomi.calendar")

    /** 1 = live clock icon · 2 = live calendar icon · 0 = normal. */
    fun liveKind(app: AppInfo): Int = when {
        !Prefs.liveIcons -> 0
        app.pkg in clockPkgs -> 1
        app.pkg in calendarPkgs -> 2
        else -> 0
    }

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
        "${app.key}|${app.updated}|${Prefs.shape}|${Prefs.themed}|${Prefs.iconPack}|$size"

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
        // 0) built-in Yogi icons for common apps
        val yogi = if (YogiIcons.isYogi(Prefs.iconPack)) YogiIcons.render(app, size, Prefs.iconPack) else null
        // 1) the chosen icon pack (its icons keep their own design)
        val packIcon = if (yogi == null) IconPacks.drawable(ctx, app) else null
        val b = if (yogi != null) yogi else if (packIcon != null) {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            packIcon.setBounds(0, 0, size, size)
            packIcon.draw(Canvas(out))
            out
        } else {
            // 2) the app's own icon, cut to our shape
            val d = try { app.info.getIcon(0) } catch (_: Exception) { null }
            if (d != null) render(d, size) else placeholder(app.label, size)
        }
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


/**
 * Icon packs in the common format used by thousands of free packs (Nova / ADW "appfilter.xml").
 * Install any icon pack from the Play Store or GitHub, then pick it in Settings.
 */
object IconPacks {
    private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme", "com.dlto.atom.launcher.THEME")

    @Volatile private var loadedPkg = ""
    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile private var res: android.content.res.Resources? = null

    /** Installed icon packs: package → name. */
    fun installed(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, String>()
        for (a in ACTIONS) {
            val list = try { pm.queryIntentActivities(Intent(a), 0) } catch (_: Exception) { emptyList() }
            for (r in list) {
                val p = r.activityInfo?.packageName ?: continue
                out[p] = try { r.loadLabel(pm).toString() } catch (_: Exception) { p }
            }
        }
        return out.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    @Synchronized
    private fun ensure(ctx: Context) {
        val want = Prefs.iconPack
        if (want == loadedPkg) return
        loadedPkg = want
        map = emptyMap()
        res = null
        if (want.isEmpty()) return
        try {
            val r = ctx.packageManager.getResourcesForApplication(want)
            val m = HashMap<String, String>()
            val id = r.getIdentifier("appfilter", "xml", want)
            val parser: org.xmlpull.v1.XmlPullParser = if (id != 0) r.getXml(id) else {
                val p = android.util.Xml.newPullParser()
                p.setInput(r.assets.open("appfilter.xml"), "UTF-8")
                p
            }
            var ev = parser.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component") ?: ""
                    val draw = parser.getAttributeValue(null, "drawable") ?: ""
                    // ComponentInfo{com.whatsapp/com.whatsapp.Main}
                    val inner = comp.substringAfter("{", "").substringBefore("}", "")
                    if (inner.isNotEmpty() && draw.isNotEmpty()) {
                        val pkg = inner.substringBefore("/")
                        var cls = inner.substringAfter("/", "")
                        if (cls.startsWith(".")) cls = pkg + cls
                        m["$pkg/$cls"] = draw
                        if (!m.containsKey(pkg)) m[pkg] = draw
                    }
                }
                ev = parser.next()
            }
            map = m
            res = r
        } catch (_: Exception) {
            map = emptyMap()
            res = null
        }
    }

    fun drawable(ctx: Context, app: AppInfo): android.graphics.drawable.Drawable? {
        if (Prefs.iconPack.isEmpty() || YogiIcons.isYogi(Prefs.iconPack)) return null
        ensure(ctx)
        val r = res ?: return null
        val name = map["${app.pkg}/${app.cls}"] ?: map[app.pkg] ?: return null
        return try {
            val id = r.getIdentifier(name, "drawable", Prefs.iconPack)
            if (id == 0) null else r.getDrawable(id, null)
        } catch (_: Exception) {
            null
        }
    }
}


/**
 * Personal, work and private profiles. Private space (Android 15+) is shown only while it is unlocked,
 * and only to the default home app (Android grants it ACCESS_HIDDEN_PROFILES).
 */
object Profiles {
    const val PERSONAL = 0
    const val WORK = 1
    const val PRIVATE = 2

    @Volatile var workUser: UserHandle? = null
        private set
    @Volatile var privateUser: UserHandle? = null
        private set

    private fun la(ctx: Context) = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private fun um(ctx: Context) = ctx.getSystemService(Context.USER_SERVICE) as android.os.UserManager

    fun users(ctx: Context): List<UserHandle> = try {
        la(ctx).profiles
    } catch (_: Exception) {
        try { um(ctx).userProfiles } catch (_: Exception) { listOf(Process.myUserHandle()) }
    }

    /** Uses the public Android 15 API LauncherApps.getLauncherUserInfo (looked up at run time). */
    fun type(ctx: Context, u: UserHandle): Int {
        if (u == Process.myUserHandle()) return PERSONAL
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val l = la(ctx)
                val info = l.javaClass.getMethod("getLauncherUserInfo", UserHandle::class.java).invoke(l, u)
                val t = info?.javaClass?.getMethod("getUserType")?.invoke(info) as? String
                if (t == "android.os.usertype.profile.PRIVATE") return PRIVATE
            } catch (_: Throwable) {
            }
        }
        return WORK
    }

    fun refresh(ctx: Context) {
        var w: UserHandle? = null
        var p: UserHandle? = null
        for (u in users(ctx)) {
            when (type(ctx, u)) {
                WORK -> if (w == null) w = u
                PRIVATE -> if (p == null) p = u
                else -> Unit
            }
        }
        workUser = w
        privateUser = p
    }

    fun isLocked(ctx: Context, u: UserHandle): Boolean = try {
        um(ctx).isQuietModeEnabled(u)
    } catch (_: Exception) {
        false
    }

    /** Lock / unlock private space, or pause / resume work apps. Android asks for the PIN when unlocking. */
    fun setLocked(ctx: Context, u: UserHandle, lock: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return try {
            um(ctx).requestQuietModeEnabled(lock, u)
            true
        } catch (_: Exception) {
            ctx.toast("Make Yogi Launcher your default home app first")
            false
        }
    }

    /**
     * Android's private space settings. The system gives us an IntentSender; like the Pixel launcher we must allow
     * it to start an activity "from the background", otherwise Android 14+ silently blocks it.
     */
    fun openPrivateSettings(a: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val l = la(a)
                val sender = l.javaClass.getMethod("getPrivateSpaceSettingsIntent").invoke(l) as? android.content.IntentSender
                if (sender != null) {
                    val opts = android.app.ActivityOptions.makeBasic()
                    try {
                        opts.javaClass.getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType)
                            .invoke(opts, 1) // MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    } catch (_: Throwable) {
                    }
                    a.startIntentSender(sender, null, 0, 0, 0, opts.toBundle())
                    return
                }
            } catch (_: Throwable) {
            }
        }
        // fallbacks: Pixel's own page, then Security & privacy
        for (act in listOf("android.settings.PRIVATE_SPACE_SETTINGS", "com.android.settings.action.OPEN_PRIVATE_SPACE_SETTINGS",
            "android.settings.SECURITY_ADVANCED_SETTINGS", android.provider.Settings.ACTION_SECURITY_SETTINGS)) {
            try {
                a.startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    /** Opens the app store inside the private space / work profile, to install apps there. */
    fun installApps(a: android.app.Activity, u: UserHandle) {
        try {
            val store = Apps.list.firstOrNull { it.user == u && (it.pkg == "com.android.vending" || it.pkg.contains("store")) }
            if (store != null) {
                la(a).startMainActivity(store.component, u, null, null)
                return
            }
        } catch (_: Exception) {
        }
        a.toast("Open Play Store from inside this space to install apps")
    }
}

/** System settings you can jump to from search ("wifi", "battery", …). */
object SettingsSearch {
    val ITEMS: List<Triple<String, String, String>> = listOf(
        Triple("📶", "Wi-Fi", android.provider.Settings.ACTION_WIFI_SETTINGS),
        Triple("🔵", "Bluetooth", android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
        Triple("📱", "Mobile data · SIM", android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS),
        Triple("✈️", "Airplane mode", android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Triple("🔋", "Battery", Intent.ACTION_POWER_USAGE_SUMMARY),
        Triple("🖥", "Display · brightness", android.provider.Settings.ACTION_DISPLAY_SETTINGS),
        Triple("🔊", "Sound · volume", android.provider.Settings.ACTION_SOUND_SETTINGS),
        Triple("🔔", "Notifications", "android.settings.NOTIFICATION_SETTINGS"),
        Triple("📍", "Location", android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Triple("🔒", "Security · privacy", android.provider.Settings.ACTION_SECURITY_SETTINGS),
        Triple("🧩", "Apps", android.provider.Settings.ACTION_APPLICATION_SETTINGS),
        Triple("💾", "Storage", android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Triple("🌐", "Language · keyboard", android.provider.Settings.ACTION_LOCALE_SETTINGS),
        Triple("🕐", "Date & time", android.provider.Settings.ACTION_DATE_SETTINGS),
        Triple("♿", "Accessibility", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Triple("🔗", "Hotspot · tethering", "android.settings.TETHER_SETTINGS"),
        Triple("📡", "NFC", android.provider.Settings.ACTION_NFC_SETTINGS),
        Triple("📊", "Data usage", android.provider.Settings.ACTION_DATA_USAGE_SETTINGS),
        Triple("🖼", "Wallpaper", Intent.ACTION_SET_WALLPAPER),
        Triple("🛠", "Developer options", android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Triple("ℹ️", "About phone", android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS),
        Triple("🔒", "Private space", "yogi:private"),
        Triple("🙈", "Hidden space · hidden apps", "yogi:hidden"),
        Triple("💼", "Work apps · work profile", "yogi:work"),
        Triple("🏠", "Yogi Launcher settings", "yogi:settings"),
        Triple("⚙️", "All settings", android.provider.Settings.ACTION_SETTINGS)
    )

    fun find(q: String): List<Triple<String, String, String>> {
        val l = q.lowercase().replace("-", "").replace(" ", "")
        if (l.length < 2) return emptyList()
        return ITEMS.filter { it.second.lowercase().replace("-", "").replace(" ", "").contains(l) }.take(4)
    }
}
