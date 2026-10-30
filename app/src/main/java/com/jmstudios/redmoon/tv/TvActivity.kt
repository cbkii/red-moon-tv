/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import com.jmstudios.redmoon.BuildConfig
import java.time.Instant
import java.time.ZoneId

/** TV pages with stable focus keys, density-independent targets and no keyboard dependency. */
class TvActivity : Activity() {
    private enum class Page(val title: String) { HOME("Home"), SCHEDULE("Schedule"), WARMTH("Warmth"), DIAGNOSTICS("Diagnostics"), SETTINGS("Settings") }
    private lateinit var prefs: TvPreferences
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private var nativeValue: TextView? = null
    private var warmthValue: TextView? = null
    private var firstControl: View? = null
    private var activeNav: View? = null
    private var page = Page.HOME
    private var renderedState = ""
    private var startedRead = false
    private var scheduleDraft: MutableList<PhaseSetting>? = null
    private val ink = Color.rgb(237, 242, 250)
    private val muted = Color.rgb(165, 182, 206)
    private val accent = Color.rgb(110, 221, 204)
    private val refresh = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshState() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = TvPreferences(this)
        page = Page.values().firstOrNull { it.name == state?.getString("page") } ?: Page.HOME
        val draft = state?.getIntArray("draft")
        if (draft != null && draft.size == 9) scheduleDraft = Phase.values().mapIndexed { i, p ->
            PhaseSetting(p, draft[i * 3], draft[i * 3 + 1], draft[i * 3 + 2])
        }.toMutableList()
        render()
        prefs.prepareIdentity {
            if (!isFinishing && !isDestroyed) {
                refreshState()
                // One read on a new build/first connection, never an automatic setter/retry.
                if (!startedRead && prefs.readState == "not_checked" && android.os.Build.DEVICE.startsWith("xiaoyushan")) {
                    startedRead = true; command("read")
                }
            }
        }
    }
    override fun onStart() { super.onStart(); registerReceiver(refresh, IntentFilter(TvDisplayService.STATUS)) }
    override fun onStop() { unregisterReceiver(refresh); super.onStop() }
    override fun onResume() { super.onResume(); if (::prefs.isInitialized) render() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page", page.name)
        scheduleDraft?.let { out.putIntArray("draft", it.flatMap { s -> listOf(s.level, s.startMinute, s.fadeSeconds) }.toIntArray()) }
        super.onSaveInstanceState(out)
    }
    override fun onBackPressed() { if (page != Page.HOME) open(Page.HOME) else super.onBackPressed() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun setup() = tvSetupStep(prefs.identityReady, RecoveryJournal.exists(this), prefs.readState,
        prefs.nativeLevel, prefs.writeContextReady, prefs.probeComplete, prefs.nativeQualified,
        TvDisplayService.active && prefs.store.getBoolean("probe-active", false))
    private fun stateKey() = listOf(setup(), prefs.rootBackend, prefs.backlightEnabled, prefs.warmthEnabled,
        prefs.probeComplete, prefs.nativeQualified, prefs.store.getBoolean("probe-active", false)).joinToString(":")
    private fun policy() = try { prefs.policy() } catch (_: IllegalArgumentException) { BrightnessPolicy.defaults() }
    private fun summary(): String = when (setup()) {
        TvSetupStep.PROBING -> prefs.status.substringBefore('\n')
        TvSetupStep.CHECKING -> "Checking installed TV bindings…"
        TvSetupStep.CONNECT -> "Connect backlight to read the TV's native level. Warmth works independently."
        TvSetupStep.READING -> if (prefs.rootBackend) "Connecting with root… allow Magisk on the TV if prompted." else "Reading the TV backlight…"
        TvSetupStep.FAILED -> "Connection failed: ${prefs.store.getString("last-native-error", "open Diagnostics for the first error")?.lineSequence()?.firstOrNull()}"
        TvSetupStep.READ_ONLY -> "Native level is readable, but range or picture/boot context is unverified. Writes are blocked."
        TvSetupStep.PROBE -> "Connected. Run the small visible test before enabling backlight control."
        TvSetupStep.CONFIRM -> "The original level was restored. Confirm what you saw on the panel."
        TvSetupStep.RECOVERY -> "A saved native session needs attention. Restore it or explicitly keep the current level."
        TvSetupStep.READY -> if (prefs.backlightEnabled) prefs.status.substringBefore('\n') else "Backlight paused. The current native level is retained."
    }
    private fun refreshState() {
        if (!::rows.isInitialized) return
        if (renderedState != stateKey()) { render(); return }
        statusView.text = summary()
        nativeValue?.text = prefs.nativeLevel?.let { "$it%" } ?: "—"
        warmthValue?.text = prefs.warmthStatus
    }
    private fun open(target: Page) { page = target; render(resetScroll = true) }
    private fun render(resetScroll: Boolean = false) {
        val focused = currentFocus?.tag as? String
        val previousScroll = if (::scroll.isInitialized && !resetScroll) scroll.scrollY else 0
        nativeValue = null; warmthValue = null; firstControl = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.rgb(12, 18, 29))
        }
        setContentView(root)
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(20), 0) }
        root.addView(rail, LinearLayout.LayoutParams(dp(176), -1))
        rail.addView(label("RED MOON", 21, accent).apply { setTypeface(null, Typeface.BOLD); setPadding(dp(8), dp(8), 0, dp(18)) })
        Page.values().forEach { target ->
            val item = makeButton(target.title, "nav-${target.name}", target == page) { open(target) }
            rail.addView(item, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })
            if (target == page) activeNav = item
        }
        rail.addView(label("A4 display control", 13, muted).apply { setPadding(dp(8), dp(16), 0, 0) })
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(16)) }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        text(page.title, 30, bold = true)
        statusView = text(summary(), 17, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        when (page) {
            Page.HOME -> home()
            Page.SCHEDULE -> schedule()
            Page.WARMTH -> warmth()
            Page.DIAGNOSTICS -> diagnostics()
            Page.SETTINGS -> settings()
        }
        renderedState = stateKey()
        firstControl = firstFocusable(rows)
        firstControl?.let { first -> activeNav?.nextFocusRightId = first.id; first.nextFocusLeftId = activeNav?.id ?: View.NO_ID }
        val restored = focused?.let { root.findViewWithTag<View>(it) }?.takeIf { it.isEnabled && it.isFocusable }
        (restored ?: firstControl ?: activeNav)?.requestFocus()
        scroll.post { scroll.scrollTo(0, previousScroll) }
    }
    private fun home() {
        val hero = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10)); background = panel() }
        rows.addView(hero, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        nativeValue = label(prefs.nativeLevel?.let { "$it%" } ?: "—", 44, ink).apply { setTypeface(null, Typeface.BOLD) }
        hero.addView(nativeValue, LinearLayout.LayoutParams(dp(148), -2))
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hero.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
        detail.addView(label("Last confirmed backlight", 18, ink))
        detail.addView(label(if (prefs.rootBackend) "Root connection" else "APK connection", 15, muted))
        val activeProbe = setup() == TvSetupStep.PROBING
        if (activeProbe) {
            actions(listOf("Test running…" to {}, "Restore now" to { command("revert") })).first().isEnabled = false
        } else when (setup()) {
            TvSetupStep.PROBING -> Unit // Handled above while the live service owns the preview.
            TvSetupStep.CHECKING, TvSetupStep.READING -> button(if (setup() == TvSetupStep.READING) "Reading backlight…" else "Checking TV…", "home-wait") {}.isEnabled = false
            TvSetupStep.CONNECT -> button("Connect backlight", "home-connect") { command("read") }
            TvSetupStep.FAILED, TvSetupStep.READ_ONLY -> actions(listOf("Read again" to { command("read") },
                (if (prefs.rootBackend) "Use APK connection" else "Try root connection") to { chooseBackend(!prefs.rootBackend) }))
            TvSetupStep.PROBE -> actions(listOf("Test & restore" to { confirmProbe() }, "Read again" to { command("read") }))
            TvSetupStep.CONFIRM -> actions(listOf("Confirm visible test" to { confirmNative() }, "Repeat test" to { confirmProbe() }))
            TvSetupStep.RECOVERY -> actions(listOf("Restore saved level" to { command("restore") }, "Keep current level" to { confirmKeep() }))
            TvSetupStep.READY -> actions(listOf((if (prefs.backlightEnabled) "Pause backlight" else "Resume schedule") to {
                command(if (prefs.backlightEnabled) "pause" else "resume") }, "Read again" to { command("read") }))
        }
        if (prefs.backlightEnabled && setup() != TvSetupStep.READY) button("Pause native schedule", "home-pause") { command("pause") }
        val p = policy()
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rows.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        p.settings.forEachIndexed { index, setting ->
            val card = makeButton("${setting.phase.title}\n${setting.level}%\n${time(setting.startMinute)}", "phase-${setting.phase}") {
                command("phase", setting.phase)
            }.apply { gravity = Gravity.CENTER; textSize = 18f; isEnabled = setup() == TvSetupStep.READY && !activeProbe }
            group.addView(card, LinearLayout.LayoutParams(0, dp(86), 1f).apply { if (index > 0) leftMargin = dp(10) })
        }
        val phase = p.at(Instant.now(), ZoneId.systemDefault())
        text("Scheduled: ${phase.current.phase.title} · next ${phase.next.setting.phase.title} at ${time(phase.next.setting.startMinute)}", 15, muted)
        text("Phase buttons hold until the next scheduled boundary. Warmth remains independent.", 14, muted)
    }
    private fun schedule() {
        toggle("Backlight schedule", prefs.backlightEnabled, "schedule-enable", prefs.backlightEnabled || setup() == TvSetupStep.READY) {
            command(if (it) "resume" else "pause")
        }
        if (setup() != TvSetupStep.READY) button("Finish connection setup on Home", "schedule-setup") { open(Page.HOME) }
        text(if (scheduleDraft == null) "Select a value to edit. Save applies all three phases together." else "Unsaved changes · all three phases will be validated together", 15, muted)
        val settings = scheduleDraft?.toList() ?: policy().settings
        columns(settings) { setting ->
            text(setting.phase.title, 22, accent, true)
            text(if (setting.phase == Phase.DAY) "Maximum · 70–100%" else "Minimum · ${setting.phase.minimum}–${setting.phase.maximum}%", 13, muted)
            button("${setting.level}%", "level-${setting.phase}") {
                number("${setting.phase.title} backlight", setting.level, setting.phase.minimum, setting.phase.maximum, { "$it%" }) { edit(setting.copy(level = it)) }
            }.textSize = 29f
            button(time(setting.startMinute), "time-${setting.phase}") { clock(setting.startMinute) { edit(setting.copy(startMinute = it)) } }
            button("Fade ${setting.fadeSeconds}s", "fade-${setting.phase}") { fade(setting.fadeSeconds) { edit(setting.copy(fadeSeconds = it)) } }
        }
        if (scheduleDraft != null) actions(listOf("Save schedule" to {
            try { prefs.save(BrightnessPolicy(scheduleDraft!!.toList())); scheduleDraft = null; command(); render() }
            catch (error: IllegalArgumentException) { message(error.message ?: "Invalid schedule") }
        }, "Cancel changes" to { scheduleDraft = null; render() }))
        text("Day ≥ Dusk ≥ Dim. Incoming phase limits apply immediately; fades stay within that phase's band. Times use ${ZoneId.systemDefault()}.", 14, muted)
    }
    private fun warmth() {
        warmthValue = text(prefs.warmthStatus, 18, accent)
        val permission = Settings.canDrawOverlays(this)
        if (!permission) button("Allow warmth over other apps", "warmth-permission") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: RuntimeException) { message("Open Android Settings → Apps → Special app access → Display over other apps → Red Moon TV.") }
        }
        toggle("Warmth schedule", prefs.warmthEnabled, "warmth-enable", permission) { prefs.warmthEnabled = it; command("warmth"); render() }
        toggle("Use brightness phase times", prefs.linked, "warmth-linked") { prefs.linked = it; command("warmth"); render() }
        if (prefs.linked) columns(Phase.values().toList()) { phase ->
            text(phase.title, 21, accent, true)
            button("${prefs.warmth(phase)}% warmth", "warmth-$phase") {
                number("${phase.title} warmth", prefs.warmth(phase), 0, 100, { "$it%" }) {
                    prefs.store.edit().putInt("warmth-$phase", it).apply(); command("warmth"); render()
                }
            }
        } else {
            button("Starts ${time(prefs.warmthStart)}", "warmth-start") { clock(prefs.warmthStart) {
                if (it == prefs.warmthEnd) message("Start and end must differ") else { prefs.warmthStart = it; command("warmth"); render() }
            } }
            button("Ends ${time(prefs.warmthEnd)}", "warmth-end") { clock(prefs.warmthEnd) {
                if (it == prefs.warmthStart) message("Start and end must differ") else { prefs.warmthEnd = it; command("warmth"); render() }
            } }
            button("Strength ${prefs.store.getInt("custom-strength", 40)}%", "warmth-strength") {
                number("Warmth strength", prefs.store.getInt("custom-strength", 40), 0, 100) {
                    prefs.store.edit().putInt("custom-strength", it).apply(); command("warmth"); render()
                }
            }
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        button("Colour · approximately ${500 + colour * 30} K", "warmth-colour") {
            number("Warmth colour", colour, 0, 100, { "${500 + it * 30} K" }) {
                prefs.store.edit().putInt("warmth-colour", it).apply(); command("warmth"); render()
            }
        }
        button("Warmth fade · ${prefs.warmthFade}s", "warmth-fade") { fade(prefs.warmthFade) { prefs.warmthFade = it; command("warmth"); render() } }
        button("Warmth off now", "warmth-off") { command("warmth_off"); render() }
        text("Warmth off never changes the native backlight. Colour is approximate; video-plane coverage still needs the TV.", 14, muted)
    }
    private fun diagnostics() {
        text("${packageName}\n${BuildConfig.SOURCE_SHA.take(12)} · ${BuildConfig.BUILD_ID}\n${BuildConfig.BACKEND_PROTOCOL}", 15, muted)
        actions(listOf("Read backlight" to { command("read") }, "Restart & read" to { command("read_restart") }))
        if (RecoveryJournal.exists(this)) actions(listOf("Restore saved level" to { command("restore") }, "Keep current level" to { confirmKeep() }))
        if (prefs.probeComplete && !prefs.nativeQualified) button("Confirm visible panel test", "diag-confirm") { confirmNative() }
        toggle("Enable fades · persistence checked", prefs.fadesQualified, "diag-fades", prefs.nativeQualified) {
            if (!it) { prefs.fadesQualified = false; render() }
            else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Repeated native writes")
                .setMessage("Enable only after checking vendor persistence and real long-fade/playback behaviour. A restored single probe does not establish repeated-write safety.")
                .setNegativeButton("Cancel", null).setPositiveButton("Verified") { _, _ -> prefs.fadesQualified = true; render() }.show()
        }
        button("Copy full diagnostic report", "diag-copy") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Red Moon TV", DiagnosticsExporter.report(this)))
            message("Copied. The ADB collector also reads this automatically, even when the display service is stopped.")
        }
        text(DiagnosticsExporter.report(this), 13, muted).typeface = Typeface.MONOSPACE
    }
    private fun settings() {
        text("Connection", 22, accent, true)
        button(if (prefs.rootBackend) "Selected: Root · switch to APK" else "Selected: APK · switch to Root", "settings-backend") { chooseBackend(!prefs.rootBackend) }
        text("APK is the default. Root requires a rooted TV and an explicit Magisk grant; it is never selected automatically.", 15, muted)
        button("Pause and transfer control to another build", "settings-transfer") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage("Pause, release the live lease and clear this build's saved original? The current native level is retained.")
                .setNegativeButton("Cancel", null).setPositiveButton("Transfer") { _, _ -> command("keep") }.show()
        }
        val external = prefs.store.getBoolean("external-control", false)
        toggle("External automation", external, "settings-external") { enabled ->
            if (!enabled) { prefs.store.edit().putBoolean("external-control", false).apply(); render() }
            else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Allow installed apps to control Red Moon?")
                .setMessage("Every installed app can send the fixed Day/Dusk/Dim, Pause, Resume, Warmth Off and Read commands. No shell, probe, restore or qualification commands are exposed.")
                .setNegativeButton("Keep off", null).setPositiveButton("Allow") { _, _ -> prefs.store.edit().putBoolean("external-control", true).apply(); render() }.show()
        }
        text("Red Moon TV · ${BuildConfig.VERSION_NAME}\nBuild ${BuildConfig.BUILD_ID} · ${BuildConfig.SOURCE_SHA.take(12)}", 15, muted)
    }
    private fun confirmProbe() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Small backlight test")
            .setMessage("Keep this input and picture mode unchanged. Change the native level by five points, then attempt restoration after 15 seconds. Watch the real panel and Backlight slider. If interrupted, use native TV Settings to recover.")
            .setNegativeButton("Cancel", null).setPositiveButton("Test & restore") { _, _ -> command("probe") }.show()
    }
    private fun confirmNative() {
        if (!prefs.probeComplete) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Did the panel change and return?")
            .setMessage("Confirm only if you saw the physical backlight change and the original level return, and checked current input/picture behaviour. This enables native control on the tested setup. It does not qualify fades or other source/HDR modes.")
            .setNegativeButton("Not confirmed", null).setPositiveButton("Confirmed") { _, _ -> prefs.nativeQualified = true; render() }.show()
    }
    private fun confirmKeep() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Keep the current native level?")
            .setMessage("Check native TV Settings first. This clears the saved original and leaves scheduling paused; it does not write the panel.")
            .setNegativeButton("Cancel", null).setPositiveButton("Keep current") { _, _ -> command("keep") }.show()
    }
    private fun chooseBackend(root: Boolean) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(if (root) "Connect using root?" else "Connect using APK?")
            .setMessage("Pause backlight scheduling and resolve any saved recovery first. ${if (root) "Read will request Magisk permission on the TV." else "No root is requested."} The connection change resets native qualification. No backlight write occurs.")
            .setNegativeButton("Cancel", null).setPositiveButton("Connect") { _, _ -> command(if (root) "use_root_read" else "use_apk_read") }.show()
    }
    private fun edit(setting: PhaseSetting) {
        if (scheduleDraft == null) scheduleDraft = policy().settings.toMutableList()
        scheduleDraft = scheduleDraft!!.map { if (it.phase == setting.phase) setting else it }.toMutableList()
        render()
    }
    private fun command(name: String = "reconcile", phase: Phase? = null) {
        if (name in listOf("pause", "restore", "keep")) prefs.backlightEnabled = false
        if (name in listOf("resume", "phase") && prefs.nativeQualified) prefs.backlightEnabled = true
        if (name == "warmth_off") prefs.warmthEnabled = false
        if (name in listOf("read", "read_restart")) prefs.readStarted()
        TvDisplayService.command(this, name, phase)
        refreshState()
    }
    private fun panel() = GradientDrawable().apply { setColor(Color.rgb(24, 35, 51)); cornerRadius = dp(12).toFloat() }
    private fun firstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ViewGroup) firstFocusable(child)?.let { return it }
            if (child.isEnabled && child.isFocusable) return child
        }
        return null
    }
    private fun label(value: String, size: Int, colour: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(colour) }
    private fun text(value: String, size: Int, colour: Int = ink, bold: Boolean = false): TextView = label(value, size, colour).apply {
        if (bold) setTypeface(null, Typeface.BOLD)
        rows.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    private fun makeButton(value: String, key: String, selected: Boolean = false, action: () -> Unit) = Button(this).apply {
        id = View.generateViewId(); tag = key; text = value; textSize = 17f; isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL; minHeight = dp(54); setPadding(dp(16), dp(8), dp(16), dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(accent); cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply { setColor(if (selected) Color.rgb(37, 78, 82) else Color.rgb(27, 40, 58)); cornerRadius = dp(10).toFloat() })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.rgb(102, 117, 137), Color.rgb(9, 26, 30), ink)))
        setOnClickListener { action() }
    }
    private fun button(value: String, key: String, action: () -> Unit): Button = makeButton(value, key, action = action).also {
        rows.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (firstControl == null) firstControl = it
    }
    private fun actions(items: List<Pair<String, () -> Unit>>): List<Button> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val controls = items.mapIndexed { index, (value, action) ->
            makeButton(value, "action-$page-$index-${value.substringBefore(' ')}", action = action).also {
                row.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(10) })
                if (firstControl == null) firstControl = it
            }
        }
        controls.forEachIndexed { i, b ->
            b.nextFocusLeftId = if (i > 0) controls[i - 1].id else activeNav?.id ?: View.NO_ID
            if (i < controls.lastIndex) b.nextFocusRightId = controls[i + 1].id
        }
        return controls
    }
    private fun toggle(value: String, checked: Boolean, key: String, enabled: Boolean = true, change: (Boolean) -> Unit) =
        button("$value · ${if (checked) "On" else "Off"}", key) { change(!checked) }.apply { isEnabled = enabled }
    private fun <T> columns(items: List<T>, build: (T) -> Unit) {
        val parent = rows
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(12) })
        items.forEachIndexed { index, item ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(14), dp(12), dp(6)); background = panel() }
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(10) })
            rows = column; build(item)
        }
        rows = parent
    }
    private fun number(title: String, initial: Int, min: Int, max: Int, format: (Int) -> String = { it.toString() }, save: (Int) -> Unit) {
        var value = initial.coerceIn(min, max)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        box.addView(label("Left / Right adjust · hold for larger steps\nOK saves · Back cancels", 16, muted))
        val display = makeButton(format(value), "number-value") {}.apply { gravity = Gravity.CENTER; textSize = 36f }
        box.addView(display, LinearLayout.LayoutParams(dp(360), dp(88)))
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> save(value) }.create()
        display.setOnClickListener { dialog.dismiss(); save(value) }
        display.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val step = if (event.repeatCount > 5) 10 else 1
                    value = (value + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -step else step).coerceIn(min, max)
                    display.text = format(value)
                }; true
            } else false
        }
        dialog.show(); display.requestFocus()
    }
    private fun clock(initial: Int, save: (Int) -> Unit) {
        var hour = initial / 60; var minute = initial % 60
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        lateinit var h: Button; lateinit var m: Button
        h = makeButton("Hour %02d".format(hour), "clock-hour") { number("Hour", hour, 0, 23) { hour = it; h.text = "Hour %02d".format(hour) } }
        m = makeButton("Minute %02d".format(minute), "clock-minute") { number("Minute", minute, 0, 59) { minute = it; m.text = "Minute %02d".format(minute) } }
        box.addView(h, LinearLayout.LayoutParams(dp(180), dp(64))); box.addView(m, LinearLayout.LayoutParams(dp(180), dp(64)).apply { leftMargin = dp(12) })
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("24-hour start time").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> save(hour * 60 + minute) }.create()
        dialog.show(); h.requestFocus()
    }
    private fun fade(initial: Int, save: (Int) -> Unit) {
        val presets = arrayOf("Immediate", "5 seconds", "15 seconds", "30 seconds", "60 seconds", "120 seconds", "300 seconds", "600 seconds", "888 seconds", "Custom")
        val values = intArrayOf(0, 5, 15, 30, 60, 120, 300, 600, 888)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Incoming fade").setItems(presets) { _, i ->
            if (i == values.size) number("Fade in seconds", initial, 0, 888, save = save) else save(values[i])
        }.show()
    }
    private fun message(value: String) { AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage(value).setPositiveButton("OK", null).show() }
    private fun time(minute: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
}
