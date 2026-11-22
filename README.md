# Yogi Launcher

<img src="icon_preview.png" width="120" align="right" />

A clean, fast home screen for Android. It is iPhone-simple, has HyperOS-style folders, and adds advanced extras.
It's small (only AndroidX libraries, no tracking, no ads) and works fully offline.

## New in v9
* **Move widgets**: hold a widget and drag it, just like an icon.
* **Yogi Glass redone**: each app keeps its own background and logo, with a lifted logo, a glass dome highlight and a bright rim. Old-style icons, and icons without a monochrome logo in Dark / Light / Themed, keep their normal look.
* **Live sliders**: icon size (with a live preview), dock corners and folder corners.
* **Serif font by default**, applied across the launcher, with bigger text in Settings. Fewer help texts.
* **5 new clocks**: OxygenOS stacked, HyperOS, iOS lock screen, Word clock, Day bars. The OxygenOS clock has more padding.
* **List style App Library**: sort by A–Z / most used / recently updated, an icon grid or a list with names, and a "New & updated" row.
* Simpler lock settings: just the double-tap switch.

## New in v8
* **Smart stack** (iOS): several widgets in one spot, swipe up/down to flip. Add it from *Add widget*, or hold any widget → *Make a smart stack*. Works with Yogi clocks, calendar, battery, suggestions and Android widgets.
* **Smart suggestions**: a widget and an App Library row with the apps you usually open at this time of day.
* **Backup & restore**: save your layout and all settings to a file and restore it after a reset or on a new phone.
* **Lock screen page**: double-tap lock status and test, *Lock screen now* in the hold menu, your own photo as the lock screen wallpaper, and links to the phone's own lock screen clock and notification styles.

## New in v7
* **Double-tap lock that works**: accessibility (keeps fingerprint/face unlock) with a **device admin backup**, a status check (Working / On but not running) and a *Test: lock now* button in Settings → Gestures & notifications.
* **Hold empty space → One UI-style menu** (Edit, Add widget, Wallpapers, Settings, Unlock layout), so Settings is always reachable, even with the layout locked.
* **Blur levels** (Off, Light, Medium, Strong, Max) for home folders, App Library categories and the App Library background, plus a Clear / Glass / Dark library background. On phones that allow it, the wallpaper itself blurs too, like iOS and HyperOS.
* **New Yogi icon styles that keep every app recognisable**: *Glass* (liquid glass), *Dark* (Nothing) and *Light* (iOS tinted). WhatsApp, Viber and Telegram each keep their own look.
* Faster: widgets are reused instead of reloaded on every home rebuild; new notifications only redraw badges.

## New in v6
* **One app.** Badges, notification previews and double-tap lock are built in. You can uninstall *Yogi Launcher Plus*.
* **Private space and work apps**, like the Pixel launcher:
  * *Personal · Work · Private* tabs in the App Library, plus a 🔒 Private box in the iOS view.
  * Unlock and lock the private space with your PIN.
  * *Private space settings* now opens.
  * There is an option to hide the private space until you search "private".
* **Widgets that load**: Android widgets (Google Clock world clock, weather, calendar…) now get their real size.
  * The new picker has previews and search.
  * Each widget has background and settings options.
* **New clocks**: Nothing two-line dots, OxygenOS two-tone, world clock (pick cities), flip, Pixel ring. Also a month calendar.
* **App Library options**:
  * Swipe up opens the Library or All apps.
  * Letters on or off.
  * Categories open as a vertical grid, horizontal pages or inside the list.
* **iOS folders**: 3 × 3 pages you swipe, zooming out of the folder icon, with a 9-app or 4-app folder preview.
* **Wallpapers**: 12 Yogi wallpapers drawn on the phone (bloom, HyperOS waves, Nothing dots…), your own photo, and a dim option.
* **New settings**: Lawnchair-style sections with search.
* **Yogi icons**: a built-in icon set for the apps on every phone (phone, messages, camera, settings, clock, maps, browser, mail, music, notes…). Choose it in Settings → Icons → Icon pack, next to the original Pixel/system icons.
* **Lock home screen layout** (One UI): nothing can be moved or removed by accident.
* **Hidden space** (OxygenOS): hidden apps move into a space you open with a two-finger swipe up (or by searching "hidden"), protected by your screen lock.
* Fixed: after opening a category in the iOS App Library and going Home, the library now opens fresh, with *All apps A–Z* always one tap away.

## New in v5
* 🔒 **Private Space (Android 15+) and 💼 work apps**, as on the Pixel launcher:
  * a **Private** section at the bottom of the App Library, with **lock / unlock** (Android asks for your PIN or
    fingerprint) and a link to Private space settings
  * private apps are never shown in suggestions, never added to the home screen, and disappear completely when locked
  * a **Work** section with **pause / resume**
  * Yogi Launcher must be your default home app; Android then grants private-space access by itself
* 📚 **iOS-style App Library** (the new default): a box per category (Suggestions, Recently updated, Social, Media,
  Games…). Tap one of the 3 big icons to open that app, or the small group to see the whole category.
* 🔍 **Spotlight search finds system settings** too: Wi-Fi, Bluetooth, battery, display, hotspot, developer options…
* ✨ Apps open with a **reveal from the icon** (iOS / HyperOS feel)

## New in v4
* **Widgets on home pages**: real Android widgets, or built-in ones. Built-ins are 4 **clock styles** (big digits,
  **Nothing-style dot-matrix**, analog, stacked), **Date** and **Battery**. Hold a widget to change its style,
  resize it (4×1, 4×2, 4×3, 2×2…), move it or remove it.
* **Live icons**: the Clock app's icon shows the real time and the Calendar icon shows today's date, as on iPhone.
* **Large folders (HyperOS style)**: hold a folder → *Make large (2×2)*. Its first 8 apps open directly from the home
  screen; the last place opens the whole folder.
* **Icon packs**: works with thousands of free packs in the Nova/ADW format, from the Play Store or GitHub.
* **App Library categories (Nothing OS style)**: chips for All · Social · Media · Photos · Games · Work · Finance ·
  Shopping · Travel · Tools. Group apps by **none**, **letter** or **colour** (a rainbow of icons), with 4 or 5 columns.
* **More control**:
  * row spacing (compact / normal / roomy), side margins, icon size up to *Huge*
  * label size, **font** (Default, Light, Medium, Condensed, Serif, Mono), and white or black labels
  * default clock style, new folder size

## Home screen
* **Your wallpaper** shows behind everything, with a frosted **dock** for 4 apps (labels hidden, as on iPhone).
* **Beautiful, uniform icons**: every app icon is redrawn in one shape.
  * Shapes: **Squircle** (continuous curve, as on iPhone), Circle, Rounded square or Pebble.
  * Modern adaptive icons are cut to the shape; old icons sit on a clean white tile.
  * **Themed icons** (Android 13 monochrome layers, or a soft tint) for a calm look.
* A **clock widget** on page 1: time, date, battery and next alarm.
* **Pages** with smooth, springy swiping and page dots. Tap the dots to search.
* **Fast**: icons are drawn once and cached on disk, so the home screen opens instantly. Each icon is a single
  hand-drawn view, with no heavy layouts.

## Editing (jiggle mode)
* **Hold an icon** to open its menu. Keep holding and move, and the menu turns into a **drag**.
* **Hold empty space** for edit mode: icons wiggle, show a **−** to remove, and display Done / Widget / Settings.
* **Drop an icon on another** to make a **folder**. It gets a smart name: Games, Social, Photos, Google…
* Drop onto an icon's edge to **swap** the two. Drag to the screen edge to **change page**, and a new page appears at
  the end.

## Folders (HyperOS style)
* The folder tile shows a 3×3 preview of its apps.
* It opens **large and centred** over a **blurred** home screen (Android 12+), with big, easy icons.
* Tap the title to rename it. Hold an app to remove it from the folder or drag it out.

## Long-press menu
* **The app's latest notification**: read it, and tap to open it.
* **App shortcuts** (for example "New message" or "Scan QR"), with their own icons.
* Edit Home Screen · Remove from Home · App info · **Hide app** · Uninstall.

## Notification badges
* Red **count badges** (or dots) on icons, and summed on folders.
  * They need notification access: Settings → *Gestures & notifications* → Notification badges → allow (on Android 13+, first App info → ⋮ → Allow restricted settings).

## App Library and search: swipe up
* **Suggestions** (your most-opened apps), then all apps **A–Z** with a letter scrubber on the right.
* **Smart search**:
  * Search apps by name, by word, by initials ("gm" → Google Maps) or by package.
  * A **calculator** right in the box: `1250*13%` = 162.5 (tap to copy).
  * **Search the web, Play Store or YouTube** for anything.
* Hold an app to add it to Home or drag it there. Pull down at the top to close.

## Gestures
* Swipe **up**: App Library · swipe **down**: notification shade (or search) · **double-tap** empty space: search ·
  **Home button**: close everything and go back to page 1.

No risky permissions: there is no accessibility service and no package-delete permission, so Play Protect installs it
without warnings. Uninstall opens Android's own app page.

## Widgets page (swipe right)
* Add any **real Android widget** (clock, weather, calendar, notes, music…) with rounded corners. Hold one to
  remove it.

## Settings
* Default launcher and badges status.
* Icon shape, size, style, labels and badge style.
* 4 or 5 columns, the clock, dock glass, and whether new apps go to Home.
* Swipe-down and double-tap actions.
* Hidden apps, wallpaper, layout reset, and rebuilding the icon cache.

## First start
The launcher builds a tidy home screen for you:
* the **dock** gets phone, messages, browser and camera
* **page 1** gets popular apps (WhatsApp, YouTube, eSewa, Khalti, your Yogi apps…)
* **smart folders** are made automatically: Google, Games, Social, Utilities…

## Building
Put this folder in its own GitHub repository. **Actions** then builds **YogiLauncher.apk** (Release `vNN`).
Package `com.yogi.launcher`. Kotlin, Android 5.0+.
