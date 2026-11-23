package com.yogi.launcher

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import java.util.Locale

/** Colours taken from the wallpaper (Material You on Android 12+, wallpaper colours on 8.1+). */
object WallColors {
    @Volatile var accent: Int = 0xFF0A84FF.toInt()
        private set

    val on: Boolean get() = Store.bool("wall_colors", true)

    /** Reads the wallpaper colour. Returns true when it changed. */
    fun load(ctx: Context): Boolean {
        val old = accent
        accent = if (!on) 0xFF0A84FF.toInt() else pick(ctx) ?: 0xFF0A84FF.toInt()
        return old != accent
    }

    private fun pick(ctx: Context): Int? {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // system palette made from the wallpaper
                return ctx.getColor(android.R.color.system_accent1_400)
            }
            if (Build.VERSION.SDK_INT >= 27) {
                val c = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
                return vivid(c.primaryColor.toArgb())
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun vivid(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
        hsv[2] = hsv[2].coerceIn(0.7f, 0.95f)
        return Color.HSVToColor(hsv)
    }

    /** Frosted glass tinted with the wallpaper colour; [alpha] 0–255. */
    fun glass(alpha: Int, mix: Float = 0.28f): Int {
        if (!on) return Color.argb(alpha, 255, 255, 255)
        val r = (255 + (Color.red(accent) - 255) * mix).toInt()
        val g = (255 + (Color.green(accent) - 255) * mix).toInt()
        val b = (255 + (Color.blue(accent) - 255) * mix).toInt()
        return Color.argb(alpha, r, g, b)
    }
}

/** Unit conversion for search: "5 km to mi", "100 f", "3.5 kg in lb", "1 gb mb". */
object Units {
    private class U(val dim: String, val factor: Double, val names: List<String>)

    private val ALL = listOf(
        U("len", 1.0, listOf("m", "meter", "meters", "metre", "metres")),
        U("len", 1000.0, listOf("km", "kilometer", "kilometers", "kilometre", "kilometres")),
        U("len", 0.01, listOf("cm", "centimeter", "centimeters")),
        U("len", 0.001, listOf("mm", "millimeter", "millimeters")),
        U("len", 1609.344, listOf("mi", "mile", "miles")),
        U("len", 0.9144, listOf("yd", "yard", "yards")),
        U("len", 0.3048, listOf("ft", "foot", "feet")),
        U("len", 0.0254, listOf("in", "inch", "inches")),
        U("mass", 1.0, listOf("kg", "kilo", "kilos", "kilogram", "kilograms")),
        U("mass", 0.001, listOf("g", "gram", "grams")),
        U("mass", 0.45359237, listOf("lb", "lbs", "pound", "pounds")),
        U("mass", 0.028349523125, listOf("oz", "ounce", "ounces")),
        U("mass", 1000.0, listOf("t", "ton", "tons", "tonne", "tonnes")),
        U("vol", 1.0, listOf("l", "liter", "liters", "litre", "litres")),
        U("vol", 0.001, listOf("ml", "milliliter", "milliliters")),
        U("vol", 3.785411784, listOf("gal", "gallon", "gallons")),
        U("vol", 0.2365882365, listOf("cup", "cups")),
        U("speed", 1.0, listOf("kmh", "km/h", "kph")),
        U("speed", 1.609344, listOf("mph")),
        U("speed", 3.6, listOf("m/s", "mps")),
        U("data", 1.0, listOf("b", "byte", "bytes")),
        U("data", 1024.0, listOf("kb")),
        U("data", 1048576.0, listOf("mb")),
        U("data", 1073741824.0, listOf("gb")),
        U("data", 1099511627776.0, listOf("tb")),
        U("time", 1.0, listOf("s", "sec", "second", "seconds")),
        U("time", 60.0, listOf("min", "minute", "minutes")),
        U("time", 3600.0, listOf("h", "hr", "hour", "hours")),
        U("time", 86400.0, listOf("day", "days")),
        U("time", 604800.0, listOf("week", "weeks"))
    )
    private val TEMPS = mapOf("c" to "c", "°c" to "c", "celsius" to "c", "f" to "f", "°f" to "f", "fahrenheit" to "f",
        "k" to "k", "kelvin" to "k")
    /** what each unit converts to when no target is typed */
    private val DEFAULT_TO = mapOf("km" to "mi", "mi" to "km", "m" to "ft", "ft" to "m", "cm" to "in", "in" to "cm",
        "kg" to "lb", "lb" to "kg", "g" to "oz", "oz" to "g", "l" to "gal", "gal" to "l", "ml" to "cup", "kmh" to "mph",
        "mph" to "kmh", "gb" to "mb", "mb" to "gb", "tb" to "gb", "kb" to "mb", "h" to "min", "min" to "s", "day" to "h",
        "week" to "day", "yd" to "m", "mm" to "in", "t" to "kg", "cup" to "ml")

    private val RX = Regex("""^\s*(-?[\d.,]+)\s*([a-z°/]+)\s*(?:to|in|=|->)?\s*([a-z°/]*)\s*$""")

    private fun find(n: String): U? = ALL.firstOrNull { n in it.names }

    /** "5 km = 3.107 mi", or null when the text isn't a conversion. */
    fun convert(q: String): String? {
        val m = RX.find(q.lowercase(Locale.US)) ?: return null
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val from = m.groupValues[2]
        val to = m.groupValues[3]
        TEMPS[from]?.let { f ->
            val t = TEMPS[to] ?: if (to.isEmpty()) (if (f == "c") "f" else "c") else return null
            val c = when (f) { "f" -> (v - 32) * 5 / 9; "k" -> v - 273.15; else -> v }
            val r = when (t) { "f" -> c * 9 / 5 + 32; "k" -> c + 273.15; else -> c }
            return "${fmt(v)} °${f.uppercase()} = ${fmt(r)} °${t.uppercase()}"
        }
        val fu = find(from) ?: return null
        val tn = to.ifEmpty { DEFAULT_TO[fu.names[0]] ?: return null }
        val tu = find(tn) ?: return null
        if (tu.dim != fu.dim) return null
        return "${fmt(v)} ${fu.names[0]} = ${fmt(v * fu.factor / tu.factor)} ${tu.names[0]}"
    }

    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString()
        else String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')
}

/** Contacts in search (asks for permission the first time). */
object Contacts {
    class Hit(val name: String, val uri: Uri, val number: String)

    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 ||
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(ctx: Context, q: String, max: Int = 4): List<Hit> {
        if (!allowed(ctx) || q.length < 2) return emptyList()
        val out = ArrayList<Hit>()
        try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { c ->
                val seen = HashSet<Long>()
                while (c.moveToNext() && out.size < max) {
                    val id = c.getLong(0)
                    if (!seen.add(id)) continue
                    out.add(Hit(c.getString(1) ?: "", Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString()),
                        c.getString(2) ?: ""))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** Every app shortcut on the phone ("New chat", "Navigate home"…), for search. */
object ShortcutIndex {
    @Volatile var all: List<ShortcutInfo> = emptyList()
        private set
    @Volatile private var at = 0L

    /** Refreshes in the background at most once a minute. */
    fun refresh(ctx: Context) {
        if (Build.VERSION.SDK_INT < 25 || System.currentTimeMillis() - at < 60_000) return
        at = System.currentTimeMillis()
        Thread {
            try {
                val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                if (!la.hasShortcutHostPermission()) return@Thread
                val q = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                all = (la.getShortcuts(q, android.os.Process.myUserHandle()) ?: emptyList())
                    .filter { it.isEnabled }
                    .distinctBy { it.`package` + "/" + it.id }
            } catch (_: Throwable) {
            }
        }.start()
    }

    fun find(q: String, max: Int = 5): List<ShortcutInfo> {
        if (Build.VERSION.SDK_INT < 25 || q.length < 2) return emptyList()
        val ql = q.lowercase()
        return all.filter { s ->
            val l = (s.shortLabel?.toString() ?: "").lowercase()
            val ll = (s.longLabel?.toString() ?: "").lowercase()
            l.startsWith(ql) || l.contains(" $ql") || ll.contains(ql)
        }.take(max)
    }
}
