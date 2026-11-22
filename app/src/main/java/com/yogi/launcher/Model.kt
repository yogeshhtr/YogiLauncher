package com.yogi.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

/** Something on the home screen. Items can cover several cells ([w] × [h]). */
sealed class Item {
    abstract fun json(): JSONObject
    open val w: Int get() = 1
    open val h: Int get() = 1

    companion object {
        fun from(o: JSONObject?): Item? {
            if (o == null) return null
            return when (o.optString("t")) {
                "a" -> AppItem(o.optString("k"))
                "f" -> FolderItem(o.optString("n", "Folder"), o.optJSONArray("a")?.strings()?.toMutableList() ?: ArrayList(),
                    o.optLong("id", System.nanoTime()), o.optBoolean("big", false))
                "w" -> WidgetItem(o.optString("k", "clock"), o.optInt("wid", -1), o.optInt("w", 4), o.optInt("h", 2),
                    o.optInt("s", 0), o.optLong("id", System.nanoTime()))
                else -> null
            }
        }
    }
}

class AppItem(val key: String) : Item() {
    override fun json(): JSONObject = JSONObject().put("t", "a").put("k", key)
}

/** A folder. [big] = 2 × 2 on the home screen (HyperOS style: its apps can be tapped directly). */
class FolderItem(var name: String, val apps: MutableList<String>, val id: Long = System.nanoTime(), var big: Boolean = false) : Item() {
    override val w: Int get() = if (big) 2 else 1
    override val h: Int get() = if (big) 2 else 1
    override fun json(): JSONObject = JSONObject().put("t", "f").put("n", name).put("a", JSONArray(apps)).put("id", id).put("big", big)
}

/**
 * A widget on a home page. [kind] = "app" (a real Android widget, [wid] = its id) or a built-in:
 * "clock" (style 0 big · 1 dot-matrix · 2 analog · 3 stacked), "date", "battery".
 */
class WidgetItem(val kind: String, val wid: Int, var sw: Int, var sh: Int, var style: Int = 0, val id: Long = System.nanoTime()) : Item() {
    override val w: Int get() = sw
    override val h: Int get() = sh
    override fun json(): JSONObject = JSONObject().put("t", "w").put("k", kind).put("wid", wid).put("w", sw).put("h", sh)
        .put("s", style).put("id", id)
}

/** Where an item is: on a page cell (its top-left cell), in the dock, or inside a folder. */
data class Slot(val area: Int, val page: Int, val index: Int, val folderId: Long = 0L) {
    companion object {
        const val PAGE = 0
        const val DOCK = 1
        const val FOLDER = 2
    }
}

/** The whole home screen: pages of cells + the dock. Saved as JSON. */
object Home {
    const val DOCK_SIZE = 4
    var cols = 4
        private set
    var rows = 6
        private set
    /** Each page stores items at their top-left cell. */
    val pages = ArrayList<Array<Item?>>()
    val dock = arrayOfNulls<Item>(DOCK_SIZE)

    val capacity: Int get() = cols * rows

    fun isEmpty(): Boolean = pages.all { p -> p.all { it == null } } && dock.all { it == null }

    fun load() {
        pages.clear()
        for (i in dock.indices) dock[i] = null
        val o = Store.obj("layout")
        cols = o.optInt("cols", Prefs.cols)
        rows = o.optInt("rows", 6)
        val ps = o.optJSONArray("pages") ?: JSONArray()
        for (p in 0 until ps.length()) {
            val arr = ps.optJSONArray(p) ?: continue
            val page = arrayOfNulls<Item>(capacity)
            for (i in 0 until minOf(arr.length(), capacity)) page[i] = Item.from(arr.optJSONObject(i))
            pages.add(page)
        }
        val d = o.optJSONArray("dock") ?: JSONArray()
        for (i in 0 until minOf(d.length(), DOCK_SIZE)) {
            val it = Item.from(d.optJSONObject(i))
            dock[i] = if (it is WidgetItem) null else it
        }
        // v4: the old fixed clock area becomes a real (movable) clock widget
        if (!Store.bool("v4_layout")) {
            Store.setBool("v4_layout", true)
            if (pages.isNotEmpty() && Prefs.clock && pages.none { p -> p.any { it is WidgetItem && it.kind == "clock" } }) {
                val clock = WidgetItem("clock", -1, cols, 2, Prefs.clockStyle)
                if (free(0, 0, clock.w, clock.h, null)) pages[0][0] = clock else place(clock)
            }
            save()
        }
    }

    fun save() {
        val ps = JSONArray()
        for (p in pages) {
            val arr = JSONArray()
            for (it in p) arr.put(it?.json() ?: JSONObject.NULL)
            ps.put(arr)
        }
        val d = JSONArray()
        for (it in dock) d.put(it?.json() ?: JSONObject.NULL)
        Store.setObj("layout", JSONObject().put("cols", cols).put("rows", rows).put("pages", ps).put("dock", d))
    }

    // ---------------- cells covered by items of several cells

    /** Index of the item that covers [idx] (its top-left cell), or -1. */
    fun anchorAt(page: Int, idx: Int): Int {
        val p = pages.getOrNull(page) ?: return -1
        if (idx !in 0 until capacity) return -1
        val r = idx / cols
        val c = idx % cols
        for (i in p.indices) {
            val it = p[i] ?: continue
            val ar = i / cols
            val ac = i % cols
            if (r >= ar && r < ar + it.h && c >= ac && c < ac + it.w) return i
        }
        return -1
    }

    fun itemAt(page: Int, idx: Int): Item? = anchorAt(page, idx).let { if (it >= 0) pages[page][it] else null }

    /** True if a [w] × [h] item fits with its top-left at [idx] ([ignore] = the item being moved). */
    fun free(page: Int, idx: Int, w: Int, h: Int, ignore: Item?): Boolean {
        if (idx < 0) return false
        val r = idx / cols
        val c = idx % cols
        if (c + w > cols || r + h > rows) return false
        if (page >= pages.size) return true
        for (dr in 0 until h) for (dc in 0 until w) {
            val it = itemAt(page, (r + dr) * cols + c + dc)
            if (it != null && it !== ignore) return false
        }
        return true
    }

    // ---------------- reading / writing slots

    fun folder(id: Long): FolderItem? {
        for (p in pages) for (it in p) if (it is FolderItem && it.id == id) return it
        for (it in dock) if (it is FolderItem && it.id == id) return it
        return null
    }

    fun get(s: Slot): Item? = when (s.area) {
        Slot.PAGE -> pages.getOrNull(s.page)?.getOrNull(s.index)
        Slot.DOCK -> dock.getOrNull(s.index)
        else -> folder(s.folderId)?.apps?.getOrNull(s.index)?.let { AppItem(it) }
    }

    private fun put(s: Slot, item: Item?) {
        when (s.area) {
            Slot.PAGE -> {
                while (pages.size <= s.page) pages.add(arrayOfNulls(capacity))
                if (s.index in 0 until capacity) pages[s.page][s.index] = item
            }
            Slot.DOCK -> if (s.index in 0 until DOCK_SIZE) dock[s.index] = item
            else -> Unit
        }
    }

    /** Takes an item out of its slot. A folder left with one app turns back into that app. */
    private fun clear(s: Slot) {
        if (s.area != Slot.FOLDER) {
            put(s, null)
            return
        }
        val f = folder(s.folderId) ?: return
        if (s.index in f.apps.indices) f.apps.removeAt(s.index)
        if (f.apps.size <= 1) {
            val where = slotOf(f) ?: return
            put(where, if (f.apps.size == 1) AppItem(f.apps[0]) else null)
        }
    }

    /** "Remove from Home": the app stays installed and in the App Library. */
    fun removeAt(s: Slot) {
        clear(s)
        compact(keepTrailing = true)
    }

    /** Breaks a folder up: its apps go to free cells. */
    fun ungroup(f: FolderItem) {
        val at = slotOf(f) ?: return
        put(at, null)
        val keys = f.apps.toList()
        if (keys.isNotEmpty()) put(at, AppItem(keys[0]))
        for (k in keys.drop(1)) place(AppItem(k))
    }

    /** Small ⇄ large folder (or a new widget size). False when it doesn't fit there. */
    fun resize(item: Item, newW: Int, newH: Int): Boolean {
        val s = slotOf(item) ?: return false
        if (s.area != Slot.PAGE) return false
        if (!free(s.page, s.index, newW, newH, item)) {
            // try another place on the same page, then anywhere
            put(s, null)
            val old = Pair(item.w, item.h)
            applySize(item, newW, newH)
            val page = pages[s.page]
            for (i in 0 until capacity) if (free(s.page, i, newW, newH, null)) {
                page[i] = item
                return true
            }
            applySize(item, old.first, old.second)
            put(s, item)
            return false
        }
        applySize(item, newW, newH)
        return true
    }

    private fun applySize(item: Item, w: Int, h: Int) {
        when (item) {
            is FolderItem -> item.big = w > 1
            is WidgetItem -> {
                item.sw = w
                item.sh = h
            }
            else -> Unit
        }
    }

    fun slotOf(item: Item): Slot? {
        for ((p, page) in pages.withIndex()) for ((i, it) in page.withIndex()) if (it === item) return Slot(Slot.PAGE, p, i)
        for ((i, it) in dock.withIndex()) if (it === item) return Slot(Slot.DOCK, 0, i)
        return null
    }

    fun usable(s: Slot): Boolean = when (s.area) {
        Slot.PAGE -> s.index in 0 until capacity
        Slot.DOCK -> s.index in 0 until DOCK_SIZE
        else -> false
    }

    /**
     * Drag & drop. [src] = where it came from (null = App Library / new), [dst] = the cell under the finger
     * (for big items: their new top-left cell), [merge] = dropped on the middle of an icon.
     */
    fun drop(src: Slot?, item: Item, dst: Slot, merge: Boolean): Boolean {
        if (!usable(dst)) return false
        if (src == dst) return false
        val big = item.w > 1 || item.h > 1
        if (dst.area == Slot.DOCK) {
            if (big) return false
            val target = dock[dst.index]
            return dropSmall(src, item, dst, target, merge)
        }
        val anchor = anchorAt(dst.page, dst.index)
        val target = if (anchor >= 0) pages[dst.page][anchor] else null
        // an app dropped onto any part of a folder (small or large) joins it
        if (item is AppItem && target is FolderItem && (merge || target.big)) {
            if (item.key in target.apps) return false
            if (src != null) clear(src)
            target.apps.add(item.key)
            compact()
            return true
        }
        if (big) {
            if (!free(dst.page, dst.index, item.w, item.h, item)) return false
            if (src != null) clear(src)
            put(dst, item)
            compact()
            return true
        }
        if (target != null && (target.w > 1 || target.h > 1)) return false
        return dropSmall(src, item, Slot(Slot.PAGE, dst.page, if (anchor >= 0) anchor else dst.index), target, merge)
    }

    private fun dropSmall(src: Slot?, item: Item, dst: Slot, target: Item?, merge: Boolean): Boolean {
        when {
            target == null -> {
                if (src != null) clear(src)
                put(dst, item)
            }
            target === item -> return false
            merge && item is AppItem && target is AppItem -> {
                if (target.key == item.key) return false
                if (src != null) clear(src)
                val at = slotOf(target) ?: dst
                put(at, FolderItem(folderName(listOf(target.key, item.key)), mutableListOf(target.key, item.key),
                    big = Prefs.bigFolders))
                if (Prefs.bigFolders && at.area == Slot.PAGE && !free(at.page, at.index, 2, 2, get(at))) {
                    (get(at) as? FolderItem)?.big = false
                }
            }
            merge && item is AppItem && target is FolderItem -> {
                if (item.key in target.apps) return false
                if (src != null) clear(src)
                target.apps.add(item.key)
            }
            src != null && src.area != Slot.FOLDER -> {
                put(src, target)
                put(dst, item)
            }
            else -> {
                if (src != null) clear(src)
                val at = slotOf(target) ?: dst
                put(at, item)
                place(target)
            }
        }
        compact()
        return true
    }

    /** Puts an item in the first place where it fits (new page if needed). */
    fun place(item: Item, preferPage: Int = -1) {
        val order = if (preferPage in pages.indices) listOf(preferPage) + pages.indices.filter { it != preferPage } else pages.indices.toList()
        for (p in order) {
            for (i in 0 until capacity) if (free(p, i, item.w, item.h, null)) {
                pages[p][i] = item
                return
            }
        }
        val page = arrayOfNulls<Item>(capacity)
        page[0] = item
        pages.add(page)
    }

    fun contains(key: String): Boolean {
        fun has(it: Item?): Boolean = (it is AppItem && it.key == key) || (it is FolderItem && key in it.apps)
        return pages.any { p -> p.any { has(it) } } || dock.any { has(it) }
    }

    /** Removes an app everywhere (uninstalled, hidden or "remove from home"). */
    fun removeKey(key: String) {
        fun fix(it: Item?): Item? = when (it) {
            is AppItem -> if (it.key == key) null else it
            is FolderItem -> {
                it.apps.remove(key)
                when (it.apps.size) {
                    0 -> null
                    1 -> if (it.big) it else AppItem(it.apps[0])
                    else -> it
                }
            }
            else -> it
        }
        for (p in pages) for (i in p.indices) p[i] = fix(p[i])
        for (i in dock.indices) dock[i] = fix(dock[i])
    }

    /** Drops apps that are no longer installed. */
    fun sync(installed: Set<String>) {
        val gone = HashSet<String>()
        fun check(it: Item?) {
            if (it is AppItem && it.key !in installed) gone.add(it.key)
            if (it is FolderItem) it.apps.filterTo(gone) { k -> k !in installed }
        }
        for (p in pages) for (it in p) check(it)
        for (it in dock) check(it)
        for (k in gone) removeKey(k)
    }

    /** All home-screen widgets (to clean up their ids). */
    fun widgets(): List<WidgetItem> = pages.flatMap { p -> p.filterIsInstance<WidgetItem>() }

    /** Removes empty pages (keeps at least one). */
    fun compact(keepTrailing: Boolean = false) {
        var i = pages.size - 1
        while (i >= 1) {
            val empty = pages[i].all { it == null }
            if (empty && !(keepTrailing && i == pages.size - 1)) pages.removeAt(i)
            i--
        }
        if (pages.isEmpty()) pages.add(arrayOfNulls(capacity))
    }

    fun ensureTrailingPage() {
        if (pages.isEmpty() || pages.last().any { it != null }) pages.add(arrayOfNulls(capacity))
    }

    /** New grid size: items keep their order and flow into the new pages. */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows && pages.all { it.size == capacity }) return
        val items = ArrayList<Item>()
        for (p in pages) for (it in p) if (it != null) items.add(it)
        cols = newCols
        rows = newRows
        pages.clear()
        pages.add(arrayOfNulls(capacity))
        for (it in items) {
            if (it is WidgetItem) {
                it.sw = it.sw.coerceIn(1, cols)
                it.sh = it.sh.coerceIn(1, rows)
            }
            place(it)
        }
        save()
    }

    fun folderName(keys: List<String>): String {
        val cats = keys.mapNotNull { Apps.get(it)?.let { a -> Apps.drawerCategory(a) } }.filter { it != "Other" }
        if (cats.isNotEmpty()) return cats.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Folder"
        val pk = keys.mapNotNull { Apps.get(it)?.pkg }
        return if (pk.isNotEmpty() && pk.all { it.startsWith("com.google") }) "Google" else "Folder"
    }

    // ---------------- first start: a tidy, useful home screen

    private val favourites = listOf(
        "com.whatsapp", "com.facebook.orca", "com.viber.voip", "com.instagram.android", "com.facebook.katana",
        "com.google.android.youtube", "com.zhiliaoapp.musically", "com.google.android.gm", "com.google.android.apps.maps",
        "com.google.android.apps.photos", "com.android.vending", "com.google.android.calendar", "com.f1soft.esewa",
        "com.khalti", "com.yogi.tv", "com.yogi.one", "com.yogi.zen", "com.yogi.lab", "com.yogi.sky", "com.android.settings",
        "com.google.android.deskclock", "com.google.android.calculator", "com.spotify.music", "org.telegram.messenger"
    )

    private fun pkgFor(ctx: Context, intent: Intent): String? = try {
        val r = ctx.packageManager.resolveActivity(intent, 0)
        r?.activityInfo?.packageName?.takeIf { it != "android" }
    } catch (_: Exception) {
        null
    }

    fun firstRun(ctx: Context, apps: List<AppInfo>) {
        pages.clear()
        for (i in dock.indices) dock[i] = null
        cols = Prefs.cols
        val used = HashSet<String>()
        fun keyOf(pkg: String?): String? = pkg?.let { p -> apps.firstOrNull { it.pkg == p }?.key }

        val dockPkgs = listOf(
            pkgFor(ctx, Intent(Intent.ACTION_DIAL)),
            try { Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Exception) { null },
            pkgFor(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))),
            pkgFor(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        )
        for ((i, p) in dockPkgs.withIndex()) {
            val k = keyOf(p) ?: continue
            if (k in used) continue
            dock[i] = AppItem(k)
            used.add(k)
        }
        pages.add(arrayOfNulls(capacity))
        if (Prefs.clock) pages[0][0] = WidgetItem("clock", -1, cols, 2, Prefs.clockStyle)
        for (p in favourites) {
            val k = keyOf(p) ?: continue
            if (k in used) continue
            used.add(k)
            place(AppItem(k))
        }
        val rest = apps.filter { it.key !in used && it.key !in Prefs.hidden }
        val google = rest.filter { it.pkg.startsWith("com.google.android") }
        if (google.size >= 3) {
            place(FolderItem("Google", google.map { it.key }.toMutableList()))
            used.addAll(google.map { it.key })
        }
        val byCat = rest.filter { it.key !in used }.groupBy { Apps.drawerCategory(it) }
        for ((name, list) in byCat) {
            if (name != "Other" && list.size >= 3) {
                place(FolderItem(name, list.map { it.key }.toMutableList()))
                used.addAll(list.map { it.key })
            }
        }
        for (a in rest) if (a.key !in used) place(AppItem(a.key))
        Store.setBool("v4_layout", true)
        save()
    }
}
