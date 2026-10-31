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
import com.jmstudios.redmoon.R
import java.time.Instant
import java.time.ZoneId

/** TV pages with stable focus keys, density-independent targets and no keyboard dependency. */
class TvActivity : Activity() {
    private enum class Page(val title: Int) { HOME(R.string.tv_page_home), SCHEDULE(R.string.tv_page_schedule), WARMTH(R.string.tv_page_warmth), DIAGNOSTICS(R.string.tv_page_diagnostics), SETTINGS(R.string.tv_page_settings) }
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
    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) { TvContinuity.ensureRunning(this, "activity"); render() }
        intent.getStringExtra("automation-open-id")?.let { id ->
            val automation = AutomationState(this)
            if (automation.find(id)?.optString("command") == "open_controls")
                automation.result(id, "control", "applied", "Controls became visible")
            intent.removeExtra("automation-open-id")
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page", page.name)
        scheduleDraft?.let { out.putIntArray("draft", it.flatMap { s -> listOf(s.level, s.startMinute, s.fadeSeconds) }.toIntArray()) }
        super.onSaveInstanceState(out)
    }
    override fun onBackPressed() { if (page != Page.HOME) open(Page.HOME) else super.onBackPressed() }
    private fun phaseTitle(phase: Phase) = getString(when (phase) {
        Phase.DAY -> R.string.tv_phase_day; Phase.DUSK -> R.string.tv_phase_dusk; Phase.DIM -> R.string.tv_phase_dim
    })
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun setup() = tvSetupStep(prefs.identityReady, prefs.sessionState() == NativeSessionState.RECOVERY, prefs.readState,
        prefs.nativeLevel, prefs.writeContextReady, prefs.probeComplete, prefs.nativeQualified,
        TvDisplayService.active && prefs.store.getBoolean("probe-active", false))
    private fun stateKey() = listOf(setup(), prefs.rootBackend, prefs.backlightEnabled, prefs.warmthEnabled,
        prefs.probeComplete, prefs.nativeQualified, prefs.store.getBoolean("probe-active", false)).joinToString(":")
    private fun policy() = try { prefs.policy() } catch (_: IllegalArgumentException) { BrightnessPolicy.defaults() }
    private fun summary(): String = when (setup()) {
        TvSetupStep.PROBING -> prefs.status.substringBefore('\n')
        TvSetupStep.CHECKING -> getString(R.string.tv_message_001)
        TvSetupStep.CONNECT -> getString(R.string.tv_message_002)
        TvSetupStep.READING -> if (prefs.rootBackend) getString(R.string.tv_message_003) else getString(R.string.tv_message_004)
        TvSetupStep.FAILED -> getString(R.string.tv_message_006, prefs.store.getString("last-native-error", getString(R.string.tv_message_005))?.lineSequence()?.firstOrNull())
        TvSetupStep.READ_ONLY -> getString(R.string.tv_message_007)
        TvSetupStep.PROBE -> getString(R.string.tv_message_008)
        TvSetupStep.CONFIRM -> getString(R.string.tv_message_009)
        TvSetupStep.RECOVERY -> getString(R.string.tv_message_010)
        TvSetupStep.READY -> if (prefs.backlightEnabled) prefs.status.substringBefore('\n') else getString(R.string.tv_message_011)
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
        rail.addView(label(getString(R.string.tv_text_001), 21, accent).apply { setTypeface(null, Typeface.BOLD); setPadding(dp(8), dp(8), 0, dp(18)) })
        Page.values().forEach { target ->
            val item = makeButton(getString(target.title), "nav-${target.name}", target == page) { open(target) }
            rail.addView(item, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })
            if (target == page) activeNav = item
        }
        rail.addView(label(getString(R.string.tv_text_002), 13, muted).apply { setPadding(dp(8), dp(16), 0, 0) })
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(16)) }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        text(getString(page.title), 30, bold = true)
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
        detail.addView(label(getString(R.string.tv_text_003), 18, ink))
        detail.addView(label(if (prefs.rootBackend) getString(R.string.tv_message_012) else getString(R.string.tv_message_013), 15, muted))
        val activeProbe = setup() == TvSetupStep.PROBING
        if (activeProbe) {
            actions(listOf(getString(R.string.tv_message_014) to {}, getString(R.string.tv_message_015) to { command("revert") })).first().isEnabled = false
        } else when (setup()) {
            TvSetupStep.PROBING -> Unit // Handled above while the live service owns the preview.
            TvSetupStep.CHECKING, TvSetupStep.READING -> button(if (setup() == TvSetupStep.READING) getString(R.string.tv_message_016) else getString(R.string.tv_message_017), "home-wait") {}.isEnabled = false
            TvSetupStep.CONNECT -> button(getString(R.string.tv_text_004), "home-connect") { command("read") }
            TvSetupStep.FAILED, TvSetupStep.READ_ONLY -> actions(listOf(getString(R.string.tv_message_018) to { command("read") },
                (if (prefs.rootBackend) getString(R.string.tv_message_019) else getString(R.string.tv_message_020)) to { chooseBackend(!prefs.rootBackend) }))
            TvSetupStep.PROBE -> actions(listOf(getString(R.string.tv_message_021) to { confirmProbe() }, getString(R.string.tv_message_022) to { command("read") }))
            TvSetupStep.CONFIRM -> actions(listOf(getString(R.string.tv_message_023) to { confirmNative() }, getString(R.string.tv_message_024) to { confirmProbe() }))
            TvSetupStep.RECOVERY -> actions(listOf(getString(R.string.tv_message_025) to { command("restore") }, getString(R.string.tv_message_026) to { confirmKeep() }))
            TvSetupStep.READY -> actions(listOf((if (prefs.backlightEnabled) getString(R.string.tv_message_027) else getString(R.string.tv_message_028)) to {
                command(if (prefs.backlightEnabled) "pause" else "resume") }, getString(R.string.tv_message_029) to { command("read") }))
        }
        if (prefs.backlightEnabled && setup() != TvSetupStep.READY) button(getString(R.string.tv_text_005), "home-pause") { command("pause") }
        val p = policy()
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rows.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        p.settings.forEachIndexed { index, setting ->
            val card = makeButton(getString(R.string.tv_message_030, phaseTitle(setting.phase), setting.level, time(setting.startMinute)), "phase-${setting.phase}") {
                command("phase", setting.phase)
            }.apply { gravity = Gravity.CENTER; textSize = 18f; isEnabled = setup() == TvSetupStep.READY && !activeProbe }
            group.addView(card, LinearLayout.LayoutParams(0, dp(86), 1f).apply { if (index > 0) leftMargin = dp(10) })
        }
        val phase = p.at(Instant.now(), ZoneId.systemDefault())
        text(getString(R.string.tv_message_031, phaseTitle(phase.current.phase), phaseTitle(phase.next.setting.phase), time(phase.next.setting.startMinute)), 15, muted)
        text(getString(R.string.tv_text_006), 14, muted)
    }
    private fun schedule() {
        toggle(getString(R.string.tv_text_007), prefs.backlightEnabled, "schedule-enable", prefs.backlightEnabled || setup() == TvSetupStep.READY) {
            command(if (it) "resume" else "pause")
        }
        if (setup() != TvSetupStep.READY) button(getString(R.string.tv_text_008), "schedule-setup") { open(Page.HOME) }
        text(if (scheduleDraft == null) getString(R.string.tv_message_032) else getString(R.string.tv_message_033), 15, muted)
        val settings = scheduleDraft?.toList() ?: policy().settings
        columns(settings) { setting ->
            text(phaseTitle(setting.phase), 22, accent, true)
            text(if (setting.phase == Phase.DAY) getString(R.string.tv_message_034) else getString(R.string.tv_message_035, setting.phase.minimum, setting.phase.maximum), 13, muted)
            button("${setting.level}%", "level-${setting.phase}") {
                number(getString(R.string.tv_message_036, phaseTitle(setting.phase)), setting.level, setting.phase.minimum, setting.phase.maximum, { "$it%" }) { edit(setting.copy(level = it)) }
            }.textSize = 29f
            button(time(setting.startMinute), "time-${setting.phase}") { clock(setting.startMinute) { edit(setting.copy(startMinute = it)) } }
            button(getString(R.string.tv_message_037, setting.fadeSeconds), "fade-${setting.phase}") { fade(setting.fadeSeconds) { edit(setting.copy(fadeSeconds = it)) } }
        }
        if (scheduleDraft != null) actions(listOf(getString(R.string.tv_message_038) to {
            try { prefs.save(BrightnessPolicy(scheduleDraft!!.toList())); scheduleDraft = null; command(); render() }
            catch (error: IllegalArgumentException) { message(error.message ?: getString(R.string.tv_message_039)) }
        }, getString(R.string.tv_message_040) to { scheduleDraft = null; render() }))
        text(getString(if (prefs.strictTransitions) R.string.tv_message_041 else R.string.tv_smooth_limits, ZoneId.systemDefault()), 14, muted)
    }
    private fun warmth() {
        warmthValue = text(prefs.warmthStatus, 18, accent)
        val permission = Settings.canDrawOverlays(this)
        if (!permission) button(getString(R.string.tv_text_009), "warmth-permission") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: RuntimeException) { message(getString(R.string.tv_message_042)) }
        }
        toggle(getString(R.string.tv_text_010), prefs.warmthEnabled, "warmth-enable", permission) { prefs.warmthEnabled = it; command("warmth"); render() }
        toggle(getString(R.string.tv_text_011), prefs.linked, "warmth-linked") { prefs.linked = it; command("warmth"); render() }
        if (prefs.linked) columns(Phase.values().toList()) { phase ->
            text(phaseTitle(phase), 21, accent, true)
            button(getString(R.string.tv_message_043, prefs.warmth(phase)), "warmth-$phase") {
                number(getString(R.string.tv_message_044, phaseTitle(phase)), prefs.warmth(phase), 0, 100, { "$it%" }) {
                    prefs.store.edit().putInt("warmth-$phase", it).apply(); command("warmth"); render()
                }
            }
        } else {
            button(getString(R.string.tv_message_045, time(prefs.warmthStart)), "warmth-start") { clock(prefs.warmthStart) {
                if (it == prefs.warmthEnd) message(getString(R.string.tv_message_046)) else { prefs.warmthStart = it; command("warmth"); render() }
            } }
            button(getString(R.string.tv_message_047, time(prefs.warmthEnd)), "warmth-end") { clock(prefs.warmthEnd) {
                if (it == prefs.warmthStart) message(getString(R.string.tv_message_048)) else { prefs.warmthEnd = it; command("warmth"); render() }
            } }
            button(getString(R.string.tv_message_049, prefs.store.getInt("custom-strength", 40)), "warmth-strength") {
                number(getString(R.string.tv_text_012), prefs.store.getInt("custom-strength", 40), 0, 100) {
                    prefs.store.edit().putInt("custom-strength", it).apply(); command("warmth"); render()
                }
            }
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        button(getString(R.string.tv_message_050, 500 + colour * 30), "warmth-colour") {
            number(getString(R.string.tv_text_013), colour, 0, 100, { getString(R.string.tv_message_051, 500 + it * 30) }) {
                prefs.store.edit().putInt("warmth-colour", it).apply(); command("warmth"); render()
            }
        }
        button(getString(R.string.tv_message_052, prefs.warmthFade), "warmth-fade") { fade(prefs.warmthFade) { prefs.warmthFade = it; command("warmth"); render() } }
        button(getString(R.string.tv_text_014), "warmth-off") { command("warmth_off"); render() }
        text(getString(R.string.tv_text_015), 14, muted)
    }
    private fun diagnostics() {
        text(getString(R.string.tv_message_053, packageName, BuildConfig.SOURCE_SHA.take(12), BuildConfig.BUILD_ID, BuildConfig.BACKEND_PROTOCOL), 15, muted)
        actions(listOf(getString(R.string.tv_message_054) to { command("read") }, getString(R.string.tv_message_055) to { command("read_restart") }))
        if (RecoveryJournal.exists(this)) actions(listOf(getString(R.string.tv_message_056) to { command("restore") }, getString(R.string.tv_message_057) to { confirmKeep() }))
        if (prefs.probeComplete && !prefs.nativeQualified) button(getString(R.string.tv_text_016), "diag-confirm") { confirmNative() }
        toggle(getString(R.string.tv_text_017), prefs.fadesQualified, "diag-fades", prefs.nativeQualified) {
            if (!it) { prefs.fadesQualified = false; render() }
            else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.tv_text_018))
                .setMessage(getString(R.string.tv_text_019))
                .setNegativeButton(getString(R.string.tv_text_020), null).setPositiveButton(getString(R.string.tv_text_021)) { _, _ -> prefs.fadesQualified = true; render() }.show()
        }
        button(getString(R.string.tv_text_022), "diag-copy") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.tv_message_058), DiagnosticsExporter.report(this)))
            message(getString(R.string.tv_message_059))
        }
        text(DiagnosticsExporter.report(this), 13, muted).typeface = Typeface.MONOSPACE
    }
    private fun settings() {
        button(getString(R.string.automation_title), "settings-automation") { startActivity(Intent(this, TvAutomationActivity::class.java)) }
        toggle(getString(R.string.tv_smooth_fades), !prefs.strictTransitions, "settings-smooth", !prefs.backlightEnabled) { smooth ->
            if (!smooth) { prefs.store.edit().putString("transition-mode", "strict").apply(); render() }
            else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(getString(R.string.tv_smooth_fades)).setMessage(getString(R.string.tv_smooth_explanation))
                .setNegativeButton(getString(R.string.tv_text_020), null)
                .setPositiveButton(getString(R.string.tv_smooth_accept)) { _, _ ->
                    prefs.store.edit().putString("transition-mode", "smooth").apply(); render()
                }.show()
        }
        text(getString(R.string.tv_transition_pause), 14, muted)
        text(getString(R.string.tv_text_023), 22, accent, true)
        button(if (prefs.rootBackend) getString(R.string.tv_message_060) else getString(R.string.tv_message_061), "settings-backend") { chooseBackend(!prefs.rootBackend) }
        text(getString(R.string.tv_text_024), 15, muted)
        button(getString(R.string.tv_text_025), "settings-transfer") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage(getString(R.string.tv_text_026))
                .setNegativeButton(getString(R.string.tv_text_020), null).setPositiveButton(getString(R.string.tv_text_027)) { _, _ -> command("keep") }.show()
        }
        toggle(getString(R.string.tv_enforce_schedule), prefs.enforceSchedule, "settings-enforce") { enabled ->
            prefs.store.edit().putBoolean("enforce-schedule", enabled).apply(); render()
        }
        text(getString(R.string.tv_continuity_explanation), 14, muted)
        text(getString(R.string.tv_message_062, BuildConfig.VERSION_NAME, BuildConfig.BUILD_ID, BuildConfig.SOURCE_SHA.take(12)), 15, muted)
    }
    private fun confirmProbe() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.tv_text_033))
            .setMessage(getString(R.string.tv_text_034))
            .setNegativeButton(getString(R.string.tv_text_020), null).setPositiveButton(getString(R.string.tv_text_035)) { _, _ -> command("probe") }.show()
    }
    private fun confirmNative() {
        if (!prefs.probeComplete) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.tv_text_036))
            .setMessage(getString(R.string.tv_text_037))
            .setNegativeButton(getString(R.string.tv_text_038), null).setPositiveButton(getString(R.string.tv_text_039)) { _, _ -> prefs.nativeQualified = true; render() }.show()
    }
    private fun confirmKeep() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.tv_text_040))
            .setMessage(getString(R.string.tv_text_041))
            .setNegativeButton(getString(R.string.tv_text_020), null).setPositiveButton(getString(R.string.tv_text_042)) { _, _ -> command("keep") }.show()
    }
    private fun chooseBackend(root: Boolean) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(if (root) getString(R.string.tv_message_063) else getString(R.string.tv_message_064))
            .setMessage(getString(R.string.tv_message_067, if (root) getString(R.string.tv_message_065) else getString(R.string.tv_message_066)))
            .setNegativeButton(getString(R.string.tv_text_020), null).setPositiveButton(getString(R.string.tv_text_043)) { _, _ -> command(if (root) "use_root_read" else "use_apk_read") }.show()
    }
    private fun edit(setting: PhaseSetting) {
        if (scheduleDraft == null) scheduleDraft = policy().settings.toMutableList()
        scheduleDraft = scheduleDraft!!.map { if (it.phase == setting.phase) setting else it }.toMutableList()
        render()
    }
    private fun command(name: String = "reconcile", phase: Phase? = null) {
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
        button(getString(R.string.tv_message_070, value, if (checked) getString(R.string.tv_message_068) else getString(R.string.tv_message_069)), key) { change(!checked) }.apply { isEnabled = enabled }
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
        box.addView(label(getString(R.string.tv_text_044), 16, muted))
        val display = makeButton(format(value), "number-value") {}.apply { gravity = Gravity.CENTER; textSize = 36f }
        box.addView(display, LinearLayout.LayoutParams(dp(360), dp(88)))
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(box)
            .setNegativeButton(getString(R.string.tv_text_020), null).setPositiveButton(getString(R.string.tv_text_045)) { _, _ -> save(value) }.create()
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
        h = makeButton(getString(R.string.tv_text_046).format(hour), "clock-hour") { number(getString(R.string.tv_text_047), hour, 0, 23) { hour = it; h.text = getString(R.string.tv_message_071).format(hour) } }
        m = makeButton(getString(R.string.tv_text_048).format(minute), "clock-minute") { number(getString(R.string.tv_text_049), minute, 0, 59) { minute = it; m.text = getString(R.string.tv_message_072).format(minute) } }
        box.addView(h, LinearLayout.LayoutParams(dp(180), dp(64))); box.addView(m, LinearLayout.LayoutParams(dp(180), dp(64)).apply { leftMargin = dp(12) })
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.tv_text_050)).setView(box)
            .setNegativeButton(getString(R.string.tv_text_020), null).setPositiveButton(getString(R.string.tv_text_045)) { _, _ -> save(hour * 60 + minute) }.create()
        dialog.show(); h.requestFocus()
    }
    private fun fade(initial: Int, save: (Int) -> Unit) {
        val presets = arrayOf(getString(R.string.tv_message_073), getString(R.string.tv_message_074), getString(R.string.tv_message_075), getString(R.string.tv_message_076), getString(R.string.tv_message_077), getString(R.string.tv_message_078), getString(R.string.tv_message_079), getString(R.string.tv_message_080), getString(R.string.tv_message_081), getString(R.string.tv_message_082))
        val values = intArrayOf(0, 5, 15, 30, 60, 120, 300, 600, 888)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.tv_text_051)).setItems(presets) { _, i ->
            if (i == values.size) number(getString(R.string.tv_text_052), initial, 0, 888, save = save) else save(values[i])
        }.show()
    }
    private fun message(value: String) { AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage(value).setPositiveButton(getString(R.string.tv_text_053), null).show() }
    private fun time(minute: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
}
