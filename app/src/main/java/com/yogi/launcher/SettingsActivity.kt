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

/** Everything you can change. Each change rebuilds the home screen when you go back. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun changed() {
        Prefs.bump()
        build()
    }

    private fun build() {
        val (sv, col) = page()
        col.addView(header("Launcher", "Make it yours"))

        // ---------- status
        val st = card()
        val isDefault = System2.isDefaultLauncher(this)
        st.addView(statusRow("🏠", "Default home screen", if (isDefault) "Yes" else "Tap to set", isDefault) { System2.askDefault(this) })
        val badges = Badges.allowed(this)
        st.addView(statusRow("🔴", "Badges & double-tap lock", when {
            !Plus.installed(this) -> "Get Plus"
            badges && Badges.lockReady -> "All on"
            badges -> "Badges on"
            else -> "Tap to set up"
        }, badges) { Badges.openSettings(this) })
        col.addView(st)

        // ---------- look
        col.addView(section("ICONS"))
        val look = card()
        look.addView(shapePreview())
        look.addView(choice("Shape", listOf("Squircle", "Circle", "Rounded", "Pebble"), Prefs.shape) { Store.setInt("shape", it); changed() })
        look.addView(choice("Size", listOf("Small", "Normal", "Large"), when {
            Prefs.iconScale < 0.95f -> 0
            Prefs.iconScale > 1.05f -> 2
            else -> 1
        }) { Store.setInt("icon_scale", listOf(88, 100, 112)[it]); changed() })
        look.addView(choice("Style", listOf("Original colours", "Themed (tinted)"), if (Prefs.themed) 1 else 0) {
            Store.setBool("themed", it == 1)
            changed()
        })
        look.addView(choice("Labels", listOf("Show", "Hide"), if (Prefs.labels) 0 else 1) { Store.setBool("labels", it == 0); changed() })
        look.addView(choice("Badges", listOf("Number", "Dot", "Off"), Prefs.badges) { Store.setInt("badges", it); changed() })
        col.addView(look)

        col.addView(section("HOME SCREEN"))
        val hs = card()
        hs.addView(choice("Columns", listOf("4", "5"), if (Prefs.cols == 5) 1 else 0) { Store.setInt("cols", if (it == 1) 5 else 4); changed() })
        hs.addView(choice("Clock on page 1", listOf("On", "Off"), if (Prefs.clock) 0 else 1) { Store.setBool("clock", it == 0); changed() })
        hs.addView(choice("Dock glass", listOf("Clear", "Light", "Frosted", "Solid"), when (Prefs.dockAlpha) {
            0 -> 0
            15 -> 1
            50 -> 3
            else -> 2
        }) { Store.setInt("dock_alpha", listOf(0, 15, 30, 50)[it]); changed() })
        hs.addView(choice("New apps", listOf("Add to Home", "Library only"), if (Prefs.autoAdd) 0 else 1) {
            Store.setBool("auto_add", it == 0)
            changed()
        })
        col.addView(hs)

        col.addView(section("GESTURES"))
        val gs = card()
        gs.addView(choice("Swipe down", listOf("Notifications", "Search"), Prefs.swipeDown) { Store.setInt("swipe_down", it); changed() })
        gs.addView(choice("Double-tap empty space", listOf("Lock / search", "Nothing"), if (Prefs.doubleTapLock) 0 else 1) {
            Store.setBool("dt_lock", it == 0)
            changed()
        })
        gs.addView(label("Swipe up: all apps & search · hold empty space: edit · hold an icon: menu or move it · " +
            "drop an icon on another: folder · tap the page dots: search", 12f, C.SUB).lin(t = dp(8)))
        col.addView(gs)

        // ---------- hidden apps
        val hidden = Prefs.hidden
        col.addView(section("HIDDEN APPS"))
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

        // ---------- more
        col.addView(section("MORE"))
        val more = card()
        more.addView(link("🖼", "Change wallpaper") {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
            } catch (_: Exception) {
                toast("No wallpaper app found")
            }
        })
        more.addView(link("↺", "Reset home screen layout") {
            AlertDialog.Builder(this)
                .setTitle("Reset the home screen?")
                .setMessage("Icons and folders are arranged fresh, like the first start. Your apps are not touched.")
                .setPositiveButton("Reset") { _, _ ->
                    Store.setBool("reset_layout", true)
                    changed()
                    toast("Done. Go back to the home screen.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        more.addView(link("🧹", "Rebuild icon cache") {
            try { java.io.File(cacheDir, "icons").deleteRecursively() } catch (_: Exception) {}
            Icons.clearMemory()
            changed()
            toast("Icons will be redrawn")
        })
        col.addView(more)

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        col.addView(label("Yogi Launcher $ver · made with care", 12f, C.SUB).apply { gravity = Gravity.CENTER }.lin(t = dp(16)))
        setContentView(sv)
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

    /** Shows the chosen icon shape with this app's own icon. */
    private fun shapePreview(): View {
        val row = hbox()
        row.gravity = Gravity.CENTER
        val size = dp(56)
        val me = try { packageManager.getApplicationIcon(packageName) } catch (_: Exception) { null }
        for (s in listOf(Apps.list.getOrNull(0), Apps.list.getOrNull(1), Apps.list.getOrNull(2))) {
            val d = s?.let { try { it.info.getIcon(0) } catch (_: Exception) { null } } ?: me ?: continue
            val iv = ImageView(this)
            iv.setImageBitmap(Icons.render(d, size))
            row.addView(iv, LinearLayout.LayoutParams(size, size).apply { setMargins(dp(8), dp(4), dp(8), dp(8)) })
        }
        row.setBackgroundColor(Color.TRANSPARENT)
        return row
    }
}
