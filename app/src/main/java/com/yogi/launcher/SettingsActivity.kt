package com.yogi.launcher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

/**
 * Launcher settings, organised like Lawnchair: a main list of sections, each opening its own page,
 * plus a search box that finds any setting. Every change rebuilds the home screen when you go back.
 */
class SettingsActivity : AppCompatActivity() {
    private var scrollY = 0
    private var scroller: android.widget.ScrollView? = null
    private var pageId = ""

    /** (id, icon, title, subtitle, search words) */
    private val pages = listOf(
        listOf("icons", "🎨", "Icons", "Shape, size, icon pack, badges", "shape squircle circle size icon pack yogi icons pixel themed badge live clock calendar"),
        listOf("text", "🔤", "Text & labels", "Font, label size and colour", "font label text size colour color black white"),
        listOf("home", "🏠", "Home screen & dock", "Grid, spacing, margins, dock glass", "grid columns rows spacing margin dock glass new apps lock layout one ui"),
        listOf("folders", "📁", "Folders", "iOS pages, HyperOS 2 × 2, previews", "folder ios hyperos big large pages preview"),
        listOf("widgets", "🕐", "Widgets & clocks", "Nothing, OxygenOS, world and Pixel clocks", "widget clock world nothing oxygen flip ring glass background calendar"),
        listOf("library", "📚", "App Library", "Style, letters, categories, opening view", "drawer library app list letters categories chips columns ios all apps folder horizontal vertical"),
        listOf("private", "🔒", "Private space & work apps", "Show, lock, settings, work profile", "private space secret hidden work profile pause lock unlock"),
        listOf("wallpaper", "🖼", "Wallpaper", "Yogi wallpapers, your photos, dim", "wallpaper background photo dim dark"),
        listOf("gestures", "👆", "Gestures & notifications", "Swipes, double-tap lock, badges", "gesture swipe double tap lock badge notification dots accessibility"),
        listOf("hidden", "🙈", "Hidden space", "Hidden apps, screen lock (OxygenOS)", "hidden hide apps space secret oxygen"),
        listOf("about", "ℹ️", "About & reset", "Version, reset layout, icon cache", "about version reset layout cache credits")
    )

    private val BLURS = listOf("Off", "Light", "Medium", "Strong", "Max")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        pageId = intent.getStringExtra("page") ?: ""
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun changed() {
        Prefs.bump()
        scrollY = scroller?.scrollY ?: 0
        build()
    }

    private fun openPage(id: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", id))
    }

    private fun build() {
        val (sv, col) = page()
        scroller = sv
        when (pageId) {
            "icons" -> icons(col)
            "text" -> text(col)
            "home" -> home(col)
            "folders" -> folders(col)
            "widgets" -> widgets(col)
            "library" -> library(col)
            "private" -> privateSpace(col)
            "wallpaper" -> wallpaper(col)
            "gestures" -> gestures(col)
            "hidden" -> hidden(col)
            "about" -> about(col)
            else -> main(col)
        }
        setContentView(sv)
        val y = scrollY
        sv.post { sv.scrollTo(0, y) }
    }

    private fun pageTitle(col: LinearLayout, id: String) {
        val p = pages.first { it[0] == id }
        val back = label("‹  Settings", 16f, C.ACCENT, true)
        back.setPadding(dp(18), dp(14), dp(18), 0)
        back.setOnClickListener { finish() }
        col.addView(back)
        col.addView(header(p[2], p[3]))
    }

    // ===================================================================== main list

    private fun main(col: LinearLayout) {
        col.addView(header("Yogi Launcher", "Make it yours"))
        val search = input("🔍  Search settings")
        search.background = rounded(C.CARD, dpf(14f))
        col.addView(search.lin(l = dp(14), r = dp(14), t = dp(4), b = dp(6)))

        val st = card()
        val isDefault = System2.isDefaultLauncher(this)
        st.addView(statusRow("🏠", "Default home screen", if (isDefault) "Yes" else "Tap to set", isDefault) { System2.askDefault(this) })
        val badges = Badges.allowed(this)
        st.addView(statusRow("🔴", "Notification badges", if (badges) "On" else "Tap to turn on", badges) { Badges.openSettings(this) })
        val lock = Badges.lockAllowed(this)
        st.addView(statusRow("🔒", "Double-tap to lock", if (lock) "On" else "Tap to turn on", lock) { Access.ask(this, true) })
        col.addView(st)

        val listCard = card()
        fun fill(q: String) {
            listCard.removeAllViews()
            val ql = q.lowercase().trim()
            val shown = pages.filter { ql.isEmpty() || (it[2] + " " + it[3] + " " + it[4]).lowercase().contains(ql) }
            for (p in shown) listCard.addView(navRow(p[1], p[2], p[3]) { openPage(p[0]) })
            if (shown.isEmpty()) listCard.addView(label("Nothing found for “$q”", 14f, C.SUB))
        }
        search.doAfterTextChanged { fill(it?.toString() ?: "") }
        fill("")
        col.addView(listCard)
        col.addView(footer())
    }

    private fun footer(): View {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        return label("Yogi Launcher $ver · made by Yogesh with care", 12f, C.SUB).apply { gravity = Gravity.CENTER }.lin(t = dp(16))
    }

    // ===================================================================== pages

    private fun icons(col: LinearLayout) {
        pageTitle(col, "icons")
        val look = card()
        look.addView(shapePreview())
        look.addView(choice("Shape", listOf("Squircle", "Circle", "Rounded", "Pebble"), Prefs.shape) { Store.setInt("shape", it); changed() })
        look.addView(choice("Size", listOf("Small", "Normal", "Large", "Huge"), when {
            Prefs.iconScale < 0.95f -> 0
            Prefs.iconScale > 1.15f -> 3
            Prefs.iconScale > 1.05f -> 2
            else -> 1
        }) { Store.setInt("icon_scale", listOf(88, 100, 112, 124)[it]); changed() })
        look.addView(choice("Style", listOf("Original colours", "Themed (tinted)"), if (Prefs.themed) 1 else 0) {
            Store.setBool("themed", it == 1)
            changed()
        })
        look.addView(choice("Live clock & calendar icons", listOf("On", "Off"), if (Prefs.liveIcons) 0 else 1) {
            Store.setBool("live_icons", it == 0)
            changed()
        })
        look.addView(choice("Badges", listOf("Number", "Dot", "Off"), Prefs.badges) { Store.setInt("badges", it); changed() })
        look.addView(iconPackRow())
        look.addView(link("🧹", "Rebuild icon cache") { rebuildIcons() })
        col.addView(look)
    }

    private fun text(col: LinearLayout) {
        pageTitle(col, "text")
        val tx = card()
        tx.addView(choice("Labels", listOf("Show", "Hide"), if (Prefs.labels) 0 else 1) { Store.setBool("labels", it == 0); changed() })
        val sizes = listOf(11, 12, 13, 14)
        tx.addView(choice("Label size", listOf("Small", "Normal", "Large", "Larger"), sizes.indexOf(Prefs.labelSize).let { if (it < 0) 1 else it }) {
            Store.setInt("label_size", sizes[it])
            changed()
        })
        tx.addView(fontChoice())
        tx.addView(choice("Label colour", listOf("White", "Black (light wallpaper)"), if (Prefs.labelDark) 1 else 0) {
            Store.setBool("label_dark", it == 1)
            changed()
        })
        col.addView(tx)
    }

    private fun home(col: LinearLayout) {
        pageTitle(col, "home")
        val hs = card()
        hs.addView(choice("Columns", listOf("4", "5"), if (Prefs.cols == 5) 1 else 0) { Store.setInt("cols", if (it == 1) 5 else 4); changed() })
        hs.addView(choice("Space between rows", listOf("Compact", "Normal", "Roomy"), Prefs.spacing) { Store.setInt("spacing", it); changed() })
        hs.addView(choice("Side margins", listOf("None", "Normal", "Wide"), Prefs.margins) { Store.setInt("margins", it); changed() })
        hs.addView(choice("Dock glass", listOf("Clear", "Light", "Frosted", "Solid"), when (Prefs.dockAlpha) {
            0 -> 0
            15 -> 1
            50 -> 3
            else -> 2
        }) { Store.setInt("dock_alpha", listOf(0, 15, 30, 50)[it]); changed() })
        hs.addView(choice("Hold empty space", listOf("Menu (One UI)", "Edit mode"), Prefs.longPress) { Store.setInt("long_press", it); changed() })
        hs.addView(choice("Lock home screen layout (One UI)", listOf("Off", "On"), if (Prefs.lockLayout) 1 else 0) {
            Store.setBool("lock_layout", it == 1)
            changed()
        })
        hs.addView(choice("New apps", listOf("Add to Home", "Library only"), if (Prefs.autoAdd) 0 else 1) {
            Store.setBool("auto_add", it == 0)
            changed()
        })
        hs.addView(label("Hold empty space for Edit mode · ＋ Widget adds clocks and Android widgets", 12f, C.SUB).lin(t = dp(8)))
        col.addView(hs)
    }

    private fun folders(col: LinearLayout) {
        pageTitle(col, "folders")
        val fc = card()
        fc.addView(choice("Open folders as", listOf("iOS pages (3 × 3, swipe)", "One scrolling grid"), Prefs.folderStyle) {
            Store.setInt("folder_style", it)
            changed()
        })
        fc.addView(choice("Folder icon shows", listOf("9 mini apps (iOS)", "4 bigger apps"), Prefs.folderPreview) {
            Store.setInt("folder_preview", it)
            changed()
        })
        fc.addView(choice("Blur behind open folders", BLURS, Prefs.blurFolder) { Store.setInt("blur_folder", it); changed() })
        fc.addView(choice("New folders", listOf("Small 1 × 1", "Large 2 × 2 (HyperOS)"), if (Prefs.bigFolders) 1 else 0) {
            Store.setBool("big_folders", it == 1)
            changed()
        })
        fc.addView(label("Drop an app on another to make a folder · hold a folder → Make large (2 × 2): its apps open with one tap", 12f, C.SUB).lin(t = dp(8)))
        col.addView(fc)
    }

    private fun widgets(col: LinearLayout) {
        pageTitle(col, "widgets")
        val ck = card()
        val names = listOf("Big", "Dot-matrix", "Analog", "Stacked", "Nothing", "OxygenOS", "World", "Flip", "Ring")
        ck.addView(choice("Style for new clocks", names, Prefs.clockStyle) {
            Store.setInt("clock_style", it)
            changed()
        })
        ck.addView(choice("Android widget background", listOf("Glass", "None"), if (Prefs.widgetGlass) 0 else 1) {
            Store.setBool("widget_glass", it == 0)
            changed()
        })
        ck.addView(label("Edit mode → ＋ Widget: Yogi clocks (Nothing, OxygenOS, world, flip, ring, analog), month calendar, date, battery, " +
            "and every Android widget with previews. Hold a widget → style, cities, background, size, move.", 12f, C.SUB).lin(t = dp(8)))
        col.addView(ck)
    }

    private fun library(col: LinearLayout) {
        pageTitle(col, "library")
        val dr = card()
        val style = when (Prefs.drawerGroup) { 3 -> 0; 2 -> 2; else -> 1 }
        dr.addView(choice("Style", listOf("iOS App Library", "List", "By colour"), style) {
            Store.setInt("drawer_group", listOf(3, 1, 2)[it])
            changed()
        })
        if (Prefs.drawerGroup == 3) {
            dr.addView(choice("Swipe up opens", listOf("Library (category boxes)", "All apps"), Prefs.drawerOpen) {
                Store.setInt("drawer_open", it)
                changed()
            })
            dr.addView(choice("Tapping a category opens", listOf("Vertical grid", "Horizontal pages", "Inside the list"), Prefs.libFolder) {
                Store.setInt("lib_folder", it)
                changed()
            })
        }
        dr.addView(choice("Background blur", BLURS, Prefs.blurLibrary) { Store.setInt("blur_library", it); changed() })
        dr.addView(choice("Background", listOf("Clear", "Glass", "Dark"), Prefs.libraryTint) { Store.setInt("library_tint", it); changed() })
        dr.addView(choice("Category popup blur", BLURS, Prefs.blurFolder) { Store.setInt("blur_folder", it); changed() })
        dr.addView(choice("Letters (A–Z headers and side bar)", listOf("On", "Off"), if (Prefs.drawerLetters) 0 else 1) {
            Store.setBool("drawer_letters", it == 0)
            changed()
        })
        dr.addView(choice("Category chips (Nothing style)", listOf("Show", "Hide"), if (Prefs.drawerChips) 0 else 1) {
            Store.setBool("drawer_chips", it == 0)
            changed()
        })
        dr.addView(choice("Columns", listOf("4", "5"), if (Prefs.drawerCols == 5) 1 else 0) {
            Store.setInt("drawer_cols", if (it == 1) 5 else 4)
            changed()
        })
        col.addView(dr)
    }

    private fun privateSpace(col: LinearLayout) {
        pageTitle(col, "private")
        Profiles.refresh(this)
        val pc = card()
        val pu = Profiles.privateUser
        if (pu == null) {
            pc.addView(label("No private space on this phone yet. On Android 15+ (Pixel and others): Settings → Security & privacy → " +
                "Private space. Yogi Launcher must be your default home app to see it.", 14f, C.SUB))
            pc.addView(link("⚙", "Open Android private space settings") { Profiles.openPrivateSettings(this) })
        } else {
            val locked = Profiles.isLocked(this, pu)
            pc.addView(statusRow("🔒", "Private space", if (locked) "Locked" else "Unlocked", !locked) {
                Profiles.setLocked(this, pu, !locked)
                col.postDelayed({ build() }, 1500)
            })
            pc.addView(choice("Private tab in the App Library", listOf("Show", "Hide until I search “private”"), if (Prefs.showPrivate) 0 else 1) {
                Store.setBool("show_private", it == 0)
                changed()
            })
            pc.addView(choice("Private apps in search (when unlocked)", listOf("Yes", "No"), if (Prefs.privateInSearch) 0 else 1) {
                Store.setBool("private_search", it == 0)
                changed()
            })
            pc.addView(link("⚙", "Private space settings (Android)") { Profiles.openPrivateSettings(this) })
            pc.addView(link("＋", "Install apps in private space") { Profiles.installApps(this, pu) })
            pc.addView(label("Private apps never go on the home screen and never show in suggestions. When hidden, search " +
                "“private” in the App Library to open it.", 12f, C.SUB).lin(t = dp(8)))
        }
        col.addView(pc)
        col.addView(section("WORK PROFILE"))
        val wc = card()
        val wu = Profiles.workUser
        if (wu == null) {
            wc.addView(label("No work profile. Apps like Island, Shelter or your company's app can create one; " +
                "its apps then get their own 💼 Work tab.", 14f, C.SUB))
        } else {
            val paused = Profiles.isLocked(this, wu)
            wc.addView(statusRow("💼", "Work apps", if (paused) "Paused · tap to turn on" else "On · tap to pause", !paused) {
                Profiles.setLocked(this, wu, !paused)
                col.postDelayed({ build() }, 1500)
            })
        }
        col.addView(wc)
    }

    private fun wallpaper(col: LinearLayout) {
        pageTitle(col, "wallpaper")
        val wc = card()
        wc.addView(label("Yogi wallpapers · drawn on your phone", 13f, C.SUB, true).lin(b = dp(8)))
        val dm = resources.displayMetrics
        val tw = (dm.widthPixels - dp(28 + 32 + 24)) / 3
        val th = (tw * dm.heightPixels.toFloat() / dm.widthPixels).toInt()
        var row: LinearLayout? = null
        for ((i, w) in Wallpapers.ALL.withIndex()) {
            if (i % 3 == 0) {
                row = hbox()
                wc.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val cell = vbox()
            cell.gravity = Gravity.CENTER_HORIZONTAL
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.clipToOutline = true
            iv.background = rounded(C.CARD2, dpf(14f))
            iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(14f)) }
            }
            cell.addView(iv, LinearLayout.LayoutParams(tw, th))
            cell.addView(label(w.name, 12f, C.TEXT).apply { gravity = Gravity.CENTER }.lin(t = dp(4), b = dp(10)))
            cell.setOnClickListener { applyWall(w) }
            row?.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            Thread {
                val b = Wallpapers.render(w, tw / 2, th / 2)
                iv.post { iv.setImageBitmap(b) }
            }.start()
        }
        col.addView(wc)
        val more = card()
        more.addView(link("📷", "Choose your own photo or live wallpaper") {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
            } catch (_: Exception) {
                toast("No wallpaper app found")
            }
        })
        more.addView(choice("Dim wallpaper on the home screen", listOf("Off", "15 %", "30 %", "45 %"), when (Prefs.wallDim) {
            15 -> 1
            30 -> 2
            45 -> 3
            else -> 0
        }) { Store.setInt("wall_dim", listOf(0, 15, 30, 45)[it]); changed() })
        col.addView(more)
    }

    private fun applyWall(w: Wallpapers.Wall) {
        AlertDialog.Builder(this)
            .setTitle("Set “${w.name}”")
            .setItems(arrayOf("Home & lock screen", "Home screen", "Lock screen")) { _, where ->
                toast("Setting wallpaper…")
                Thread {
                    val ok = Wallpapers.apply(this, w, where)
                    runOnUiThread { toast(if (ok) "Wallpaper set ✨" else "This phone didn't allow it") }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun gestures(col: LinearLayout) {
        pageTitle(col, "gestures")
        val gs = card()
        gs.addView(choice("Swipe down", listOf("Notifications", "Search"), Prefs.swipeDown) { Store.setInt("swipe_down", it); changed() })
        gs.addView(choice("Double-tap empty space", listOf("Lock screen", "Nothing"), if (Prefs.doubleTapLock) 0 else 1) {
            Store.setBool("dt_lock", it == 0)
            changed()
        })
        gs.addView(label("Swipe up: App Library & search · hold empty space: edit · hold an icon: menu or move it · " +
            "drop an icon on another: folder · tap the page dots: search", 12f, C.SUB).lin(t = dp(8)))
        col.addView(gs)
        col.addView(section("BUILT IN (NO EXTRA APP NEEDED)"))
        val bc = card()
        val badges = Badges.allowed(this)
        bc.addView(statusRow("🔴", "Notification badges & previews", if (badges) "On" else "Turn on", badges) { Badges.openSettings(this) })
        val lockOn = Badges.lockAllowed(this)
        val running = LockService.instance != null
        bc.addView(statusRow("🔒", "Double-tap lock · accessibility", when {
            running -> "Working"
            lockOn -> "On but not running · tap"
            else -> "Turn on"
        }, running) { Access.ask(this, true) })
        val admin = Locker.adminActive(this)
        bc.addView(statusRow("🛡", "Double-tap lock · device admin (backup)", if (admin) "On" else "Turn on", admin) {
            if (admin) Locker.removeAdmin(this) else Locker.askAdmin(this)
            col.postDelayed({ build() }, 800)
        })
        bc.addView(label("Accessibility lock keeps fingerprint and face unlock. Device admin is a backup that always works, " +
            "but Android then asks for your PIN once after locking. If accessibility says “On but not running”, " +
            "turn it off and on again (Android stops it after the app is updated).", 12f, C.SUB).lin(t = dp(8)))
        bc.addView(link("🧪", "Test: lock the screen now") {
            if (!Locker.lock(this)) toast("Turn on one of the two lock options first")
        })
        bc.addView(label("You can uninstall the old “Yogi Launcher Plus” app: everything is inside Yogi Launcher now.", 12f, C.SUB).lin(t = dp(8)))
        col.addView(bc)
    }

    private fun hidden(col: LinearLayout) {
        pageTitle(col, "hidden")
        val hidden = Prefs.hidden
        val hs = card()
        hs.addView(label("Hidden space (OxygenOS): hidden apps leave Home, the App Library and search. Open them with a " +
            "two-finger swipe up on the home screen, or search “hidden”.", 13f, C.SUB))
        hs.addView(choice("Ask for screen lock to open", listOf("Yes", "No"), if (Prefs.hiddenLock) 0 else 1) {
            Store.setBool("hidden_lock", it == 0)
            changed()
        })
        col.addView(hs)
        val hc = card()
        if (hidden.isEmpty()) {
            hc.addView(label("No hidden apps. Hold an icon → Hide app.", 13f, C.SUB))
        } else {
            for (k in hidden) {
                val app = Apps.get(k)
                val row = hbox()
                row.setPadding(0, dp(8), 0, dp(8))
                row.addView(label(app?.label ?: k.substringBefore('/'), 15f, C.TEXT).lin(0, WRAP, 1f))
                row.addView(label("Show", 14f, C.ACCENT, true).apply {
                    setOnClickListener {
                        Store.setSet("hidden", Prefs.hidden - k)
                        if (Prefs.autoAdd) {
                            Home.load()
                            if (!Home.contains(k)) Home.place(AppItem(k))
                            Home.save()
                        }
                        changed()
                    }
                })
                hc.addView(row)
            }
        }
        col.addView(hc)
    }

    private fun about(col: LinearLayout) {
        pageTitle(col, "about")
        val more = card()
        more.addView(link("↺", "Reset home screen layout") {
            AlertDialog.Builder(this)
                .setTitle("Reset the home screen?")
                .setMessage("Icons and folders are arranged fresh, like the first start. Your apps are not touched.")
                .setPositiveButton("Reset") { _, _ ->
                    for (w in Home.widgets()) if (w.kind == "app") WidgetHost.delete(this, w.wid)
                    Store.setBool("reset_layout", true)
                    changed()
                    toast("Done. Go back to the home screen.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        more.addView(link("🧹", "Rebuild icon cache") { rebuildIcons() })
        more.addView(link("⭐", "Source code & new versions") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/yogeshhtr/YogiLauncher/releases/latest")))
            } catch (_: Exception) {
            }
        })
        more.addView(label("Ideas learned from open-source launchers (Lawnchair, Kvaesitso, Folio). All code here is our own.", 12f, C.SUB).lin(t = dp(8)))
        col.addView(more)
        col.addView(footer())
    }

    private fun rebuildIcons() {
        try { java.io.File(cacheDir, "icons").deleteRecursively() } catch (_: Exception) {}
        Icons.clearMemory()
        changed()
        toast("Icons will be redrawn")
    }

    // ===================================================================== rows

    private fun iconPackRow(): View {
        val packs = IconPacks.installed(this)
        val cur = Prefs.iconPack
        val name = when {
            cur.isEmpty() -> "Original (Pixel / system)"
            YogiIcons.isYogi(cur) -> YogiIcons.name(cur)
            else -> packs.firstOrNull { it.first == cur }?.second ?: cur
        }
        return link("🎨", "Icon pack: $name") {
            val names = listOf("Original (Pixel / system icons)", "✨ Yogi Glass (liquid glass, every app)", "⚫ Yogi Dark (Nothing style)",
                "⚪ Yogi Light (iOS tinted)") + packs.map { it.second } + listOf("🔎 Get icon packs…")
            AlertDialog.Builder(this)
                .setTitle("Icon pack")
                .setItems(names.toTypedArray()) { _, i ->
                    when {
                        i == 0 -> {
                            Store.setStr("icon_pack", "")
                            changed()
                        }
                        i in 1..3 -> {
                            Store.setStr("icon_pack", listOf(YogiIcons.PACK, YogiIcons.DARK, YogiIcons.LIGHT)[i - 1])
                            changed()
                            toast(YogiIcons.name(Prefs.iconPack) + " on")
                        }
                        i <= packs.size + 3 -> {
                            Store.setStr("icon_pack", packs[i - 4].first)
                            changed()
                            toast("Icons from ${packs[i - 4].second}")
                        }
                        else -> try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=icon%20pack&c=apps")))
                        } catch (_: Exception) {
                            toast("Search “icon pack” in your app store")
                        }
                    }
                }
                .show()
        }
    }

    private fun fontChoice(): View {
        val box = vbox()
        box.addView(label("Font", 13f, C.SUB, true).lin(t = dp(10), b = dp(4)))
        val row = hbox()
        val names = listOf("Default", "Light", "Medium", "Condensed", "Serif", "Mono")
        val faces = listOf(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL),
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL),
            android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL),
            android.graphics.Typeface.SERIF,
            android.graphics.Typeface.MONOSPACE
        )
        names.forEachIndexed { i, n ->
            val c = chip(n, i == Prefs.font) { if (i != Prefs.font) { Store.setInt("font", i); changed() } }
            c.typeface = faces[i]
            row.addView(c)
        }
        box.addView(hscroll(row))
        return box
    }

    private fun navRow(icon: String, name: String, sub: String, onClick: () -> Unit): View {
        val r = hbox()
        r.background = ripple(C.CARD, 12f)
        r.setPadding(dp(4), dp(11), dp(4), dp(11))
        r.addView(label(icon, 20f).apply { gravity = Gravity.CENTER }.lin(dp(36), WRAP, r = dp(10)))
        val t = vbox()
        t.addView(label(name, 16f, C.TEXT))
        t.addView(label(sub, 12f, C.SUB))
        r.addView(t.lin(0, WRAP, 1f))
        r.addView(label("›", 20f, C.SUB))
        r.setOnClickListener { onClick() }
        return r
    }

    private fun statusRow(icon: String, name: String, value: String, ok: Boolean, onClick: () -> Unit): View {
        val r = hbox()
        r.background = ripple(C.CARD, 12f)
        r.setPadding(dp(4), dp(10), dp(4), dp(10))
        r.addView(label(icon, 18f).lin(WRAP, WRAP, r = dp(12)))
        r.addView(label(name, 15f, C.TEXT).lin(0, WRAP, 1f))
        r.addView(label(value, 13f, if (ok) C.GREEN else C.ACCENT, true))
        r.setOnClickListener { onClick() }
        return r
    }

    private fun link(icon: String, name: String, onClick: () -> Unit): View {
        val r = hbox()
        r.background = ripple(C.CARD, 12f)
        r.setPadding(dp(4), dp(12), dp(4), dp(12))
        r.addView(label(icon, 18f).lin(WRAP, WRAP, r = dp(12)))
        r.addView(label(name, 15f, C.TEXT).lin(0, WRAP, 1f))
        r.addView(label("›", 18f, C.SUB))
        r.setOnClickListener { onClick() }
        return r
    }

    private fun choice(name: String, options: List<String>, sel: Int, onPick: (Int) -> Unit): View {
        val box = vbox()
        box.addView(label(name, 13f, C.SUB, true).lin(t = dp(10), b = dp(4)))
        val row = hbox()
        options.forEachIndexed { i, o -> row.addView(chip(o, i == sel) { if (i != sel) onPick(i) }) }
        box.addView(hscroll(row))
        return box
    }

    /** Shows the chosen icon shape with a few of your apps. */
    private fun shapePreview(): View {
        val row = hbox()
        row.gravity = Gravity.CENTER
        val size = dp(56)
        val me = try { packageManager.getApplicationIcon(packageName) } catch (_: Exception) { null }
        for (s in listOf(Apps.list.getOrNull(0), Apps.list.getOrNull(1), Apps.list.getOrNull(2), Apps.list.getOrNull(3))) {
            val d = s?.let { a -> IconPacks.drawable(this, a) ?: (try { a.info.getIcon(0) } catch (_: Exception) { null }) } ?: me ?: continue
            val iv = ImageView(this)
            iv.setImageBitmap(Icons.render(d, size))
            row.addView(iv, LinearLayout.LayoutParams(size, size).apply { setMargins(dp(8), dp(4), dp(8), dp(8)) })
        }
        row.setBackgroundColor(Color.TRANSPARENT)
        return row
    }
}
