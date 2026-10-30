/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import java.time.*

/** Leanback entry point with ordinary focusable controls; no touch-only controls. */
class TvActivity : Activity() {
    private lateinit var prefs: TvPreferences
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var building = false
    private var controlTag = 1
    private var diagnostics = false
    private var scheduleDraft: MutableList<PhaseSetting>? = null
    private val refresh = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshStatus(intent.getStringExtra("status")) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TvPreferences(this)
        render()
    }
    override fun onStart() { super.onStart(); registerReceiver(refresh, IntentFilter(TvDisplayService.STATUS)) }
    override fun onStop() { unregisterReceiver(refresh); super.onStop() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) refreshStatus() }
    private fun refreshStatus(progress: String? = null) {
        val state = try { prefs.policy().at(Instant.now(), ZoneId.systemDefault()) }
            catch (error: IllegalArgumentException) { status.text = "Invalid saved schedule: ${error.message}"; return }
        status.text = "${progress ?: prefs.status}\n${prefs.warmthStatus}\nScheduled phase: ${state.current.phase.title} · next ${state.next.setting.phase.title} at ${time(state.next.setting.startMinute)} (${ZoneId.systemDefault()})"
    }
    private fun render() {
        val oldFocus = currentFocus?.tag as? Int
        val oldScroll = if (::rows.isInitialized) (rows.parent as? ScrollView)?.scrollY ?: 0 else 0
        controlTag = 1
        building = true
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 32); setBackgroundColor(Color.rgb(18, 22, 30)) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(rows) })
        text("Red Moon TV", 30f).setTypeface(null, Typeface.BOLD)
        text("A4 native backlight · independent warmth", 18f)
        status = text("", 17f)
        refreshStatus()
        button(if (diagnostics) "Close diagnostics" else "Diagnostics and native qualification") { diagnostics = !diagnostics; render() }
        if (diagnostics) {
        text("${com.jmstudios.redmoon.BuildConfig.APPLICATION_ID} · ${com.jmstudios.redmoon.BuildConfig.SOURCE_SHA.take(12)} · build ${com.jmstudios.redmoon.BuildConfig.BUILD_ID}", 16f)
        button("Copy bounded diagnostic report") {
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(ClipData.newPlainText("Red Moon TV diagnostics", DiagnosticsExporter.report(this)))
            message("Diagnostic report copied. Vendor log collection is documented in A4-VALIDATION.md.")
        }
        button("Keep current native level and clear saved recovery") {
            AlertDialog.Builder(this).setMessage("Clears the saved original and any uncertain write. Scheduling stays paused. Use only after checking the native TV Backlight setting.")
                .setNegativeButton("Cancel", null).setPositiveButton("Keep") { _, _ -> command("keep"); render() }.show()
        }
        button("Read native backlight (APK context)") { command("read") }.requestFocus()
        button("Test a nearby native level for 15 seconds, then restore") {
            AlertDialog.Builder(this).setTitle("Native backlight probe")
                .setMessage("Tests a nearby five-point change using the TV API and attempts restoration after 15 seconds. Check the actual screen and native Backlight slider. Uses the installed vendor API from the APK. Root is not requested. If interrupted, restore in TV Settings. The test does not qualify fades or input switching.")
                .setNegativeButton("Cancel", null).setPositiveButton("Test") { _, _ -> command("probe") }.show()
        }
        button("Revert current preview") { command("revert") }
        toggle("Native control, restoration and input/picture behaviour physically verified", prefs.nativeQualified) { checked ->
            if (checked && !prefs.probeComplete) {
                message("Complete the native read/change/restore probe first, then verify the screen and input/picture behaviour.")
                render()
            } else { prefs.nativeQualified = checked; if (!checked) { prefs.backlightEnabled = false; command("pause") } }
        }
        toggle("Repeated writes are qualified for fades (persistence checked)", prefs.fadesQualified) { checked ->
            if (checked && !prefs.nativeQualified) { message("Verify native control first. Repeated-write persistence must be separately established."); render() }
            else prefs.fadesQualified = checked
        }
        }
        text("Backlight schedule", 23f)
        toggle("Enable backlight schedule", prefs.backlightEnabled) { checked ->
            if (checked && !prefs.nativeQualified) { message("The backlight probe must be physically verified first."); render() }
            else { prefs.backlightEnabled = checked; command(if (checked) "resume" else "pause") }
        }
        if (scheduleDraft == null) button("Edit Day / Dusk / Dim schedule") { scheduleDraft = prefs.policy().settings.toMutableList(); render() }
        scheduleDraft?.toList()?.forEach { setting ->
            text("${setting.phase.title} · draft ${time(setting.startMinute)}", 20f)
            button("${if (setting.phase == Phase.DAY) "Maximum" else "Minimum"} and target: ${setting.level}%") {
                number("${setting.phase.title} level", setting.level, setting.phase.minimum, setting.phase.maximum) { editDraft(setting.copy(level = it)) }
            }
            button("Starts: ${time(setting.startMinute)}") { clock(setting.startMinute) { editDraft(setting.copy(startMinute = it)) } }
            button("Incoming fade: ${setting.fadeSeconds} seconds") {
                val presets = arrayOf("0", "5", "15", "30", "60", "120", "300", "600", "888", "Custom")
                AlertDialog.Builder(this).setTitle("Incoming fade in seconds").setItems(presets) { _, which ->
                    if (which == 9) number("Incoming fade", setting.fadeSeconds, 0, 888) { editDraft(setting.copy(fadeSeconds = it)) }
                    else editDraft(setting.copy(fadeSeconds = presets[which].toInt()))
                }.show()
            }
        }
        if (scheduleDraft != null) {
            button("Save all three phases") {
                try { prefs.save(BrightnessPolicy(scheduleDraft!!.toList())); scheduleDraft = null; command(); render() }
                catch (error: IllegalArgumentException) { message(error.message ?: "Invalid schedule") }
            }
            button("Cancel schedule edits") { scheduleDraft = null; render() }
        }
        text("Phase limits apply immediately; fades continue within them. For example, 90 → Dusk 40 steps to 80 before fading. Native percentages are slider positions, not measured light output.", 16f)
        text("Temporary phase · automatic schedule resumes at the next boundary", 17f)
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; rows.addView(quick)
        Phase.values().forEach { phase ->
            val b = Button(this).apply { tag = controlTag++; text = phase.title; setOnClickListener { if (prefs.nativeQualified) { command("phase", phase); render() } else message("Verify native control first") } }
            quick.addView(b, LinearLayout.LayoutParams(0, 64, 1f))
        }
        button("Resume scheduled phase") { command("resume"); render() }
        button("Pause backlight changes (keep current level)") { command("pause"); render() }
        button("Pause and transfer control to another installed build") {
            AlertDialog.Builder(this).setMessage("Pauses this build, releases its live lease and clears its saved original after pending work settles. Then open the other build and Resume. The current panel level is retained.")
                .setNegativeButton("Cancel", null).setPositiveButton("Release control") { _, _ -> command("keep"); render() }.show()
        }
        button("Pause and restore saved native backlight") { command("restore"); render() }
        text("Warmth", 23f)
        toggle("Enable warmth schedule", prefs.warmthEnabled) { prefs.warmthEnabled = it; command("warmth") }
        toggle("Follow brightness phase times", prefs.linked) { prefs.linked = it; command("warmth"); render() }
        if (prefs.linked) Phase.values().forEach { phase ->
            button("${phase.title} warmth: ${prefs.warmth(phase)}%") { number("Warmth strength", prefs.warmth(phase), 0, 100) {
                prefs.store.edit().putInt("warmth-$phase", it).apply(); command("warmth"); render()
            }}
        } else {
            button("Warmth starts: ${time(prefs.warmthStart)}") { clock(prefs.warmthStart) { value ->
                if (value == prefs.warmthEnd) message("Start and end must differ") else { prefs.warmthStart = value; command("warmth"); render() }
            }}
            button("Warmth ends: ${time(prefs.warmthEnd)}") { clock(prefs.warmthEnd) { value ->
                if (value == prefs.warmthStart) message("Start and end must differ") else { prefs.warmthEnd = value; command("warmth"); render() }
            }}
            button("Custom warmth: ${prefs.store.getInt("custom-strength", 40)}%") { number("Warmth strength", prefs.store.getInt("custom-strength", 40), 0, 100) {
                prefs.store.edit().putInt("custom-strength", it).apply(); command("warmth"); render()
            }}
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        button("Approximate warmth colour: ${500 + colour * 30} K") { number("Warmth colour · 500–3500 K", colour, 0, 100, { "${500 + it * 30} K" }) {
            prefs.store.edit().putInt("warmth-colour", it).apply(); command("warmth"); render()
        }}
        button("Warmth fade: ${prefs.warmthFade} seconds") { number("Warmth fade", prefs.warmthFade, 0, 888) { prefs.warmthFade = it; command("warmth"); render() } }
        button("Allow display over other apps") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        button("Warmth off now (backlight unchanged)") { command("warmth_off"); render() }
        toggle("Allow explicit tvQuickActions / ADB control intents", prefs.store.getBoolean("external-control", false)) {
            prefs.store.edit().putBoolean("external-control", it).apply()
        }
        text("Use Up/Down to move, OK to edit, Left/Right to adjust, Back to cancel. Native backlight and playback filter coverage require testing on this A4. Pausing warmth never restores daytime brightness.", 16f)
        building = false
        if (oldFocus != null) {
            rows.findViewWithTag<View>(oldFocus)?.requestFocus()
            (rows.parent as ScrollView).post { (rows.parent as ScrollView).scrollTo(0, oldScroll) }
        }
    }
    private fun text(value: String, size: Float): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(8, 12, 8, 8); rows.addView(this)
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        tag = controlTag++; text = label; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; minHeight = 60
        setOnClickListener { action() }; rows.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
        rows.addView(CheckBox(this).apply { tag = controlTag++; text = label; textSize = 18f; setTextColor(Color.WHITE); isChecked = checked
            setPadding(8, 10, 8, 10); setOnCheckedChangeListener { _, value -> if (!building) change(value) }
        })
    }
    private fun number(title: String, value: Int, min: Int, max: Int, format: (Int) -> String = { it.toString() }, save: (Int) -> Unit) {
        val picker = NumberPicker(this).apply {
            minValue = min; maxValue = max; this.value = value; wrapSelectorWheel = false
            setFormatter { format(it) }; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    this.value = (this.value + (if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1) * (if (event.repeatCount > 5) 10 else 1)).coerceIn(min, max)
                    true
                } else false
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(picker).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> save(picker.value) }.show()
        picker.requestFocus()
    }
    private fun clock(value: Int, save: (Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val hour = NumberPicker(this).apply { minValue = 0; maxValue = 23; this.value = value / 60; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        val minute = NumberPicker(this).apply { minValue = 0; maxValue = 59; this.value = value % 60; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        box.addView(hour); box.addView(minute)
        AlertDialog.Builder(this).setTitle("Start time · hour : minute").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> save(hour.value * 60 + minute.value) }.show()
        hour.requestFocus()
    }
    private fun editDraft(setting: PhaseSetting) {
        scheduleDraft = scheduleDraft?.map { if (it.phase == setting.phase) setting else it }?.toMutableList()
        render()
    }
    private fun command(name: String = "reconcile", phase: Phase? = null) {
        if (name in listOf("pause", "restore", "keep")) prefs.backlightEnabled = false
        if (name in listOf("resume", "phase") && prefs.nativeQualified) prefs.backlightEnabled = true
        if (name == "warmth_off") prefs.warmthEnabled = false
        TvDisplayService.command(this, name, phase)
    }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show() }
    private fun time(minute: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
}
