package com.yogi.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

/** Something on the home screen: one app, or a folder of apps. */
sealed class Item {
    abstract fun json(): JSONObject

    companion object {
        fun from(o: JSONObject?): Item? {
            if (o == null) return null
            return when (o.optString("t")) {
                "a" -> AppItem(o.optString("k"))
                "f" -> FolderItem(o.optString("n", "Folder"), o.optJSONArray("a")?.strings()?.toMutableList() ?: ArrayList(),
                    o.optLong("id", System.nanoTime()))
                else -> null
            }
        }
    }
}

class AppItem(val key: String) : Item() {
    override fun json(): JSONObject = JSONObject().put("t", "a").put("k", key)
}

class FolderItem(var name: String, val apps: MutableList<String>, val id: Long = System.nanoTime()) : Item() {
    override fun json(): JSONObject = JSONObject().put("t", "f").put("n", name).put("a", JSONArray(apps)).put("id", id)
}

/** Where an item is: on a page cell, in the dock, or inside a folder. */
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
    val pages = ArrayList<Array<Item?>>()
    val dock = arrayOfNulls<Item>(DOCK_SIZE)

    val capacity: Int get() = cols * rows

    /** Cells kept free for the clock widget on page 1. */
    fun reserved(page: Int): Int = if (page == 0 && Prefs.clock) cols * 2 else 0

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
        for (i in 0 until minOf(d.length(), DOCK_SIZE)) dock[i] = Item.from(d.optJSONObject(i))
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

    fun slotOf(item: Item): Slot? {
        for ((p, page) in pages.withIndex()) for ((i, it) in page.withIndex()) if (it === item) return Slot(Slot.PAGE, p, i)
        for ((i, it) in dock.withIndex()) if (it === item) return Slot(Slot.DOCK, 0, i)
        return null
    }

    fun usable(s: Slot): Boolean = when (s.area) {
        Slot.PAGE -> s.index >= reserved(s.page) && s.index < capacity
        Slot.DOCK -> s.index in 0 until DOCK_SIZE
        else -> false
    }

    /**
     * Drag & drop result.
     * [src] = where it came from (null = from the App Library), [merge] = dropped on the middle of an icon.
     */
    fun drop(src: Slot?, item: Item, dst: Slot, merge: Boolean): Boolean {
        if (!usable(dst)) return false
        if (src == dst) return false
        val target = get(dst)
        when {
            target == null -> {
                if (src != null) clear(src)
                put(dst, item)
            }
            merge && item is AppItem && target is AppItem -> {
                if (target.key == item.key) return false
                if (src != null) clear(src)
                val name = folderName(listOf(target.key, item.key))
                // the target may have moved if src was a folder that collapsed; look it up again
                val at = slotOf(target) ?: dst
                put(at, FolderItem(name, mutableListOf(target.key, item.key)))
            }
            merge && item is AppItem && target is FolderItem -> {
                if (item.key in target.apps) return false
                if (src != null) clear(src)
                target.apps.add(item.key)
            }
            src != null && src.area != Slot.FOLDER -> {
                // swap the two places
                put(src, target)
                put(dst, item)
            }
            else -> {
                // from a folder / the library onto an occupied cell: push the other icon to a free place
                if (src != null) clear(src)
                val at = slotOf(target) ?: dst
                put(at, item)
                place(target)
            }
        }
        compact()
        return true
    }

    /** Puts an item in the first free cell (new page if all are full). */
    fun place(item: Item) {
        for ((p, page) in pages.withIndex()) {
            for (i in reserved(p) until capacity) if (page[i] == null) {
                page[i] = item
                return
            }
        }
        val page = arrayOfNulls<Item>(capacity)
        page[reserved(pages.size)] = item
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
                    1 -> AppItem(it.apps[0])
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
        for (p in pages) for (it in p) {
            if (it is AppItem && it.key !in installed) gone.add(it.key)
            if (it is FolderItem) it.apps.filterTo(gone) { k -> k !in installed }
        }
        for (it in dock) {
            if (it is AppItem && it.key !in installed) gone.add(it.key)
            if (it is FolderItem) it.apps.filterTo(gone) { k -> k !in installed }
        }
        for (k in gone) removeKey(k)
    }

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

    /** New grid size: icons keep their order and flow into the new pages. */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows && pages.all { it.size == capacity }) return
        val items = ArrayList<Item>()
        for (p in pages) for (it in p) if (it != null) items.add(it)
        cols = newCols
        rows = newRows
        pages.clear()
        pages.add(arrayOfNulls(capacity))
        for (it in items) place(it)
        save()
    }

    /** The clock was switched on: move icons out of the top two rows of page 1. */
    fun freeReserved() {
        if (pages.isEmpty()) return
        val p = pages[0]
        val moved = ArrayList<Item>()
        for (i in 0 until reserved(0)) {
            p[i]?.let { moved.add(it) }
            p[i] = null
        }
        for (it in moved) place(it)
    }

    fun folderName(keys: List<String>): String {
        val cats = keys.mapNotNull { Apps.get(it)?.category?.let { c -> Apps.categoryName(c) } }
        if (cats.isNotEmpty()) return cats.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Folder"
        val pk = keys.mapNotNull { Apps.get(it)?.pkg }
        return if (pk.isNotEmpty() && pk.all { it.startsWith("com.google") }) "Google" else "Folder"
    }

    // ---------------- first start: a tidy, useful home screen

    private val favourites = listOf(
        "com.whatsapp", "com.facebook.orca", "com.viber.voip", "com.instagram.android", "com.facebook.katana",
        "com.google.android.youtube", "com.zhiliaoapp.musically", "com.google.android.gm", "com.google.android.apps.maps",
        "com.google.android.apps.photos", "com.android.vending", "com.google.android.calendar", "com.f1soft.esewa",
        "com.khalti", "com.yogi.tv", "com.yogi.one", "com.yogi.zen", "com.yogi.lab", "com.android.settings",
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

        // dock: phone · messages · browser · camera
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
        // page 1: favourites that are installed
        for (p in favourites) {
            val k = keyOf(p) ?: continue
            if (k in used) continue
            used.add(k)
            place(AppItem(k))
        }
        // smart folders: Google apps, and categories with 3+ apps
        val rest = apps.filter { it.key !in used && it.key !in Prefs.hidden }
        val google = rest.filter { it.pkg.startsWith("com.google.android") }
        if (google.size >= 3) {
            place(FolderItem("Google", google.map { it.key }.toMutableList()))
            used.addAll(google.map { it.key })
        }
        val byCat = rest.filter { it.key !in used }.groupBy { Apps.categoryName(it.category) }
        for ((name, list) in byCat) {
            if (name != null && list.size >= 3) {
                place(FolderItem(name, list.map { it.key }.toMutableList()))
                used.addAll(list.map { it.key })
            }
        }
        // system tools together
        val tools = rest.filter { it.key !in used && Apps.isSystem(it) }
        if (tools.size >= 3) {
            place(FolderItem("Utilities", tools.map { it.key }.toMutableList()))
            used.addAll(tools.map { it.key })
        }
        for (a in rest) if (a.key !in used) place(AppItem(a.key))
        save()
    }
}
