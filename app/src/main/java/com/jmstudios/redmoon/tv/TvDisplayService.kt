/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.app.*
import android.app.Notification
import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import com.jmstudios.redmoon.*
import java.time.*
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One serial controller, non-wakeup boundaries, independent warmth failure containment. */
class TvDisplayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = controllerExecutor()
    private val timerLock = Any()
    private var fadeTask: ScheduledFuture<*>? = null
    private val generation = AtomicInteger()
    private val queue = ControllerQueue(worker, { fail(it) }, { main.post { stopIfIdle(); scheduleIdleClose() } })
    private val session = UUID.randomUUID().toString()
    private var monitorTask: ScheduledFuture<*>? = null
    private var idleTask: ScheduledFuture<*>? = null
    @Volatile private var operation = "idle"
    @Volatile private var destroyed = false
    private lateinit var prefs: TvPreferences
    private lateinit var controller: BacklightController
    private lateinit var automation: AutomationState
    private val automationLock = Any()
    @Volatile private var brightnessRequest: Pair<Int, String>? = null
    private var warmthRequest: String? = null
    private var warmthFadeOverride: Int? = null
    private var resumeRequest: String? = null
    private var overlay: Overlay? = null
    private var warmthTask: Runnable? = null
    private var warmthTarget: WarmthState? = null
    @Volatile private var preview = false
    @Volatile private var recoveryRequired = false
    @Volatile private var rebaseAfterExplicitResume = false
    private var overridePhase: Phase? = null
    private var overrideUntil: Instant? = null
    @Volatile private var screenOn = true
    private fun cancelTransition(): Int {
        finishBrightness("cancelled", "Transition superseded or lifecycle changed")
        val token = generation.incrementAndGet()
        synchronized(timerLock) {
            fadeTask?.cancel(false); fadeTask = null
            monitorTask?.cancel(false); monitorTask = null
        }
        return token
    }
    private fun scheduleIdleClose() {
        if (destroyed) return
        idleTask?.cancel(false)
        idleTask = worker.schedule({
            if (queue.count == 0 && !controller.hasClaim() && !preview) controller.close()
        }, 2, TimeUnit.SECONDS)
    }
    private fun monitor(token: Int) {
        synchronized(timerLock) {
            monitorTask?.cancel(false)
            if (!destroyed && screenOn && token == generation.get()) monitorTask = worker.schedule({
                if (token == generation.get() && screenOn && !destroyed) submit("monitor") {
                    if (token == generation.get() && controller.hasClaim()) {
                        controller.observe()
                        monitor(token)
                    }
                }
            }, 5, TimeUnit.SECONDS)
        }
    }
    private fun nextStep(token: Int, delay: Long, step: () -> Unit) {
        synchronized(timerLock) {
            if (!destroyed && token == generation.get()) {
                fadeTask?.cancel(false)
                fadeTask = worker.schedule({ if (token == generation.get() && !destroyed) submit("fade", work = step) }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }
    private val screens = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            cancelTransition()
            if (screenOn) {
                if (preview) revertPreview.run()
                else reconcile(true)
            } else {
                clearWarmth()
                status("Standby: native writes suspended")
                // Lifecycle cleanup must not be dropped by the interactive-request queue limit.
                submit("standby-close", true) { controller.close() } // Retain recovery evidence.
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        active = true; liveSession = session; instance = this
        prefs = TvPreferences(this)
        automation = AutomationState(this); automation.interrupted()
        // A previous process may have died during a read. A new lifecycle owner
        // must not advertise that old in-flight operation, including boot for warmth.
        if (prefs.store.getString("native-read-state", "") == "reading")
            prefs.store.edit().remove("native-read-state").apply()
        prefs.prepareIdentity { if (!destroyed) reconcile(false) }
        controller = newController()
        recoveryRequired = try { RecoveryJournal(this).load()?.let { it.pending != null || it.kind != "schedule" } == true }
            catch (_: Exception) { true }
        prefs.store.edit().putBoolean("probe-active", false).apply()
        if (recoveryRequired) {
            prefs.backlightEnabled = false
            status("Interrupted native session: read and explicitly Restore or Keep in Diagnostics. Automatic writes paused; input/HDR context is unqualified.")
        }
        overridePhase = Phase.values().firstOrNull { it.name == prefs.store.getString("override-phase", "") }
        overrideUntil = prefs.store.getLong("override-until", 0).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        if (recoveryRequired) clearOverride()
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "TV display control", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(71, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_icon_half_moon)
            .setContentTitle("Red Moon TV").setContentText("Independent backlight and warmth schedules")
            .setContentIntent(open).setOngoing(true).build())
        registerReceiver(screens, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        executeCommand(intent?.getStringExtra("command") ?: "reconcile", intent)
        main.post { stopIfIdle() }
        return START_STICKY
    }
    private fun executeCommand(command: String, intent: Intent?) {
        when (command) {
            "automation" -> handleAutomation(requireNotNull(intent))
            "automation-revoke" -> {
                finishBrightness("cancelled", "Integration revoked")
                finishWarmth("cancelled", "Integration revoked")
                automation.temporary("brightness", null); automation.temporary("warmth", null)
                executeCommand("pause", null); warmthOnly(true)
            }
            "use_root", "use_apk", "use_root_read", "use_apk_read" -> {
                if (prefs.backlightEnabled || preview || overridePhase != null || automation.temporary("brightness") != null) status("Pause native scheduling/preview before changing backend")
                else submit {
                    check(controller.pendingRecovery() == null) { "Restore or Keep the saved session before changing backend" }
                    controller.close()
                    prefs.selectBackend(command.startsWith("use_root"))
                    controller = newController()
                    status("${if (prefs.rootBackend) "Root" else "APK"} backend selected. Read first; physical qualification must be repeated.")
                    if (command.endsWith("_read")) readNative()
                }
            }
            "read", "read_restart" -> {
                if (command == "read_restart" && (prefs.backlightEnabled || preview)) status("Pause scheduling/preview before restarting the native worker")
                else submit("read") {
                    if (command == "read_restart") controller.close()
                    readNative()
                }
            }
            "adb" -> handleAdb(requireNotNull(intent))
            "probe" -> probe()
            "revert", "restore" -> restore()
            "keep" -> {
                automation.temporary("brightness", null)
                cancelTransition(); preview = false; main.removeCallbacks(revertPreview)
                prefs.backlightEnabled = false; clearOverride()
                submit("keep", true) { controller.release(); controller.discard(); recoveryRequired = false; prefs.store.edit().putBoolean("native-blocked", false).remove("manual-hold-until").apply(); status("Recovery cleared by user; current native level retained") }
            }
            "pause" -> {
                automation.temporary("brightness", null)
                clearOverride(); prefs.backlightEnabled = false; cancelTransition()
                if (preview) revertPreview.run() else submit("pause", true) { controller.release(); status("Backlight paused; current level retained") }
                reconcile(false)
            }
            "phase", "resume" -> {
                automation.temporary("brightness", null)
                if (prefs.nativeQualified && !recoveryRequired && !preview) {
                    val phase = if (command == "phase") Phase.values().firstOrNull { it.name == intent?.getStringExtra("phase") } else null
                    if (command == "phase" && phase == null) { status("Invalid phase command"); return }
                    rebaseAfterExplicitResume = prefs.store.contains("manual-hold-until")
                    clearOverride(); prefs.backlightEnabled = true
                    prefs.store.edit().putBoolean("native-blocked", false).remove("manual-hold-until").apply()
                    if (command == "phase") {
                        overridePhase = phase
                        overrideUntil = prefs.policy().at(Instant.now(), ZoneId.systemDefault()).next.instant
                        prefs.store.edit().putString("override-phase", overridePhase?.name).putLong("override-until", overrideUntil!!.toEpochMilli()).apply()
                    }
                    reconcile(true)
                } else status("Native qualification/recovery required before scheduling")
            }
            "warmth" -> warmthOnly()
            "warmth_off" -> { finishWarmth("cancelled", "Warmth disabled"); automation.temporary("warmth", null); prefs.warmthEnabled = false; warmthOnly(true) }
            else -> reconcile(command != "boundary")
        }
    }
    private fun finishBrightness(state: String, detail: String, expectedGeneration: Int? = null) {
        val request = synchronized(automationLock) {
            val current = brightnessRequest ?: return
            if (expectedGeneration != null && current.first != expectedGeneration) return
            brightnessRequest = null; current
        }
        automation.result(request.second, "brightness", state, detail)
    }
    private fun finishWarmth(state: String, detail: String) {
        val id = warmthRequest ?: return
        warmthRequest = null; warmthFadeOverride = null
        automation.result(id, "warmth", state, detail)
    }
    private fun handleAutomation(intent: Intent) {
        val id = intent.getStringExtra("automation-id") ?: return
        try {
            val extras = intent.getBundleExtra("automation-extras") ?: error("Missing request")
            val r = AutomationContract.parse(AutomationState.data(extras), id)
            check(automation.authorised(extras.getString("token"))) { "Integration disabled or token revoked" }
            when (r.command) {
                "show_status" -> {
                    automation.start(r, listOf("control"))
                    android.widget.Toast.makeText(this, "${prefs.nativeLevel ?: "?"}% — ${prefs.warmthStatus}", android.widget.Toast.LENGTH_SHORT).show()
                    automation.result(id, "control", "applied", "Cached status; no native read")
                }
                "open_controls" -> {
                    automation.start(r, listOf("control"))
                    startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    automation.result(id, "control", "applied", "Controls opened")
                }
                "reconcile" -> {
                    automation.start(r, listOf("control"))
                    reconcile(false)
                    // A reconciliation is a schedule check, not proof that a target has been reached.
                    automation.result(id, "control", "applied", "Schedule check dispatched; inspect runtime/native freshness")
                }
                else -> {
                    val parts = listOf("brightness", "warmth").filter { r.affects(it) }
                    automation.start(r, parts)
                    parts.forEach { part ->
                        try { if (part == "brightness") automateBrightness(r) else automateWarmth(r) }
                        catch (error: Exception) { automation.result(id, part, "rejected", error.message ?: "Rejected") }
                    }
                }
            }
        } catch (error: Exception) {
            automation.result(id, "dispatch", "rejected", error.message ?: "Invalid request")
        }
    }
    private fun expires(r: AutomationRequest, part: String): Long {
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val boundary = if (part == "warmth" && !prefs.linked)
            DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(now, zone).second
            else prefs.policy().at(now, zone).next.instant
        return AutomationContract.expiry(r, now.toEpochMilli(), boundary.toEpochMilli())
    }
    private fun automateBrightness(r: AutomationRequest) {
        val pause = r.command in setOf("brightness_pause", "snooze", "cancel_transition")
        if (pause) {
            // No native setter. Queued fades retire before guaranteed cleanup is admitted.
            check(!preview) { "A probe must be restored through Diagnostics" }
            val token = cancelTransition(); clearOverride()
            if (r.command == "brightness_pause") {
                prefs.backlightEnabled = false; automation.temporary("brightness", null)
            } else automation.temporary("brightness", JSONObject().put("kind", "hold")
                .put("expires", expires(r, "brightness")).put("request_id", r.id))
            val admitted = submit("automation-hold-${r.id}", true) {
                if (token != generation.get()) {
                    automation.result(r.id, "brightness", "cancelled", "Hold superseded"); return@submit
                }
                try {
                controller.release()
                check(controller.pendingRecovery()?.pending == null) { "Uncertain write requires recovery" }
                controller.discard()
                automation.result(r.id, "brightness", "applied", "Current native value retained; no setter")
                } catch (error: Exception) {
                    automation.result(r.id, "brightness", "failed", "Cleanup requires recovery"); throw error
                }
            }
            check(admitted) { "Cleanup already pending; retry with a new request ID" }
            warmthOnly(); return
        }
        if (r.command in setOf("brightness_resume", "resume_auto")) {
            check(screenOn && prefs.identityReady && !recoveryRequired && !preview) { "Wake TV and resolve recovery/probe first" }
            if (prefs.backlightEnabled) check(prefs.nativeQualified) { "Native qualification required" }
            if (r.command == "brightness_resume") check(prefs.nativeQualified) { "Native qualification required" }
            cancelTransition(); automation.temporary("brightness", null); clearOverride()
            if (r.command == "brightness_resume") prefs.backlightEnabled = true
            rebaseAfterExplicitResume = prefs.store.contains("manual-hold-until")
            prefs.store.edit().remove("manual-hold-until").putBoolean("native-blocked", false).apply()
            // Attach request to the scheduled transition without creating a persistent temporary value.
            if (prefs.backlightEnabled) reconcileAutomationResume(r.id)
            else automation.result(r.id, "brightness", "applied", "Temporary override cleared; saved schedule remains disabled")
            return
        }
        check(screenOn && prefs.identityReady && prefs.nativeQualified && !recoveryRequired && !preview) { "Wake TV, finish qualification and resolve recovery first" }
        check(!prefs.store.getBoolean("native-blocked", false) || prefs.store.contains("manual-hold-until")) { "Native failure blocked; explicitly resume first" }
        val policy = prefs.policy(); val prior = automation.temporary("brightness")
        val selected = r.phase ?: prior?.optString("phase")?.takeIf { it.isNotEmpty() }?.let { Phase.valueOf(it) }
            ?: policy.at(Instant.now(), ZoneId.systemDefault()).current.phase
        val setting = policy.settings.first { it.phase == selected }
        val band = policy.band(setting)
        val value = when (r.command) {
            "brightness_phase", "preset_apply" -> setting.level
            "brightness_set" -> r.value!!
            "brightness_adjust" -> AutomationContract.adjusted(prior?.takeIf { it.optString("kind") == "value" }?.getInt("value")
                ?: prefs.nativeLevel ?: error("Read native brightness first"), r.delta!!, band)
            else -> error("Unsupported brightness operation")
        }
        require(value in band) { "Target must be in ${setting.phase} band $band (saved cap/floor)" }
        val fade = r.fade ?: if (r.command == "brightness_adjust") 0 else setting.fadeSeconds
        check(fade == 0 || prefs.fadesQualified) { "Fade qualification required" }
        val expiry = expires(r, "brightness")
        cancelTransition(); clearOverride()
        rebaseAfterExplicitResume = prefs.store.contains("manual-hold-until")
        prefs.store.edit().remove("manual-hold-until").putBoolean("native-blocked", false).apply()
        automation.temporary("brightness", JSONObject().put("kind", "value").put("phase", selected.name)
            .put("value", value).put("fade", fade).put("expires", expiry).put("request_id", r.id))
        reconcile(true)
    }
    private fun reconcileAutomationResume(id: String) {
        // reconcile retires old work before attaching this request to the new generation.
        resumeRequest = id
        reconcile(true)
    }
    private fun automateWarmth(r: AutomationRequest) {
        check(screenOn || r.command in setOf("warmth_enable", "snooze", "resume_auto", "cancel_transition")) { "Wake TV before changing warmth" }
        finishWarmth("cancelled", "Warmth target superseded")
        val prior = automation.temporary("warmth")
        val policy = prefs.policy(); val now = Instant.now()
        val scheduled = if (!prefs.warmthEnabled) 0 else if (prefs.linked) prefs.warmth(policy.at(now, ZoneId.systemDefault()).current.phase)
            else if (DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(now, ZoneId.systemDefault()).first) prefs.store.getInt("custom-strength", 40) else 0
        val current = prior?.optInt("value") ?: overlay?.profile?.intensity ?: scheduled
        when (r.command) {
            "warmth_enable" -> { prefs.warmthEnabled = r.enabled!!; automation.temporary("warmth", null) }
            "resume_auto" -> automation.temporary("warmth", null)
            else -> {
                val value = when (r.command) {
                    "snooze" -> 0
                    "cancel_transition" -> overlay?.profile?.intensity ?: 0
                    "preset_apply" -> prefs.warmth(requireNotNull(r.phase))
                    "warmth_set" -> r.value!!
                    "warmth_adjust" -> AutomationContract.adjusted(current, r.delta!!, 0..100)
                    else -> error("Unsupported warmth operation")
                }
                automation.temporary("warmth", JSONObject().put("kind", "value").put("value", value)
                    .put("fade", r.fade ?: if (r.command in setOf("warmth_adjust", "snooze", "cancel_transition")) 0 else prefs.warmthFade)
                    .put("expires", expires(r, "warmth")).put("request_id", r.id))
            }
        }
        warmthRequest = r.id
        // Superseding an equal target must still execute its own completion callback.
        warmthTask?.let { main.removeCallbacks(it) }; warmthTask = null
        warmthFadeOverride = if (r.command == "warmth_enable" && r.enabled == false) 0 else r.fade
        warmthOnly()
    }

    private fun handleAdb(intent: Intent) {
        val name = intent.getStringExtra("adb-command") ?: return
        val data = JSONObject(intent.getStringExtra("adb-data") ?: "{}")
        try {
            TvAdbControl.validate(name, data)
            prefs.store.edit().putString("adb-last-request", intent.getStringExtra("adb-id"))
                .remove("adb-error").apply()
            when (name) {
                "configure" -> {
                    require(!preview && !prefs.backlightEnabled && automation.temporary("brightness") == null) { "Pause before configuring" }
                    val policy = TvAdbControl.policy(data)
                    val start = data.optInt("warmth_start", prefs.warmthStart)
                    val end = data.optInt("warmth_end", prefs.warmthEnd)
                    require(start != end) { "Warmth start/end must differ" }
                    if (policy != null) prefs.save(policy)
                    prefs.store.edit().apply {
                        if (data.has("transition_mode")) putString("transition-mode", data.getString("transition_mode"))
                        for ((json, pref) in listOf("warmth" to "warmth", "linked" to "linked"))
                            if (data.has(json)) putBoolean(pref, data.getBoolean(json))
                        for ((json, pref) in listOf("warmth_start" to "warmth-start", "warmth_end" to "warmth-end",
                            "warmth_fade" to "warmth-fade", "strength" to "custom-strength", "colour" to "warmth-colour"))
                            if (data.has(json)) putInt(pref, data.getInt(json))
                    }.commit()
                    warmthOnly(); status("ADB configuration applied")
                }
                "save_config" -> {
                    check(!prefs.store.contains("adb-config-backup")) { "An ADB configuration backup already exists" }
                    val snapshot = JSONObject().put("backlight", prefs.backlightEnabled)
                    val values = JSONObject()
                    prefs.store.all.filterKeys { key -> key in setOf("warmth", "linked", "warmth-start", "warmth-end", "warmth-fade", "custom-strength", "warmth-colour", "transition-mode") ||
                        Phase.values().any { key in listOf("$it-level", "$it-start", "$it-fade", "warmth-$it") } }.forEach { (key, value) -> values.put(key, value) }
                    snapshot.put("values", values)
                    check(prefs.store.edit().putString("adb-config-backup", snapshot.toString()).commit())
                }
                "restore_config" -> {
                    check(!preview && !prefs.backlightEnabled && automation.temporary("brightness") == null && queue.count == 0) { "Restore/Keep the test and pause first" }
                    val saved = JSONObject(prefs.store.getString("adb-config-backup", null) ?: error("No ADB configuration backup"))
                    val keys = setOf("warmth", "linked", "warmth-start", "warmth-end", "warmth-fade", "custom-strength", "warmth-colour", "transition-mode") +
                        Phase.values().flatMap { listOf("$it-level", "$it-start", "$it-fade", "warmth-$it") }
                    val edit = prefs.store.edit(); keys.forEach { edit.remove(it) }
                    val values = saved.getJSONObject("values")
                    values.keys().forEach { key -> when (val value = values.get(key)) { is Boolean -> edit.putBoolean(key, value); is Number -> edit.putInt(key, value.toInt()); is String -> edit.putString(key, value) } }
                    check(edit.remove("adb-config-backup").commit())
                    prefs.backlightEnabled = saved.getBoolean("backlight"); reconcile(true)
                }
                "acknowledge" -> {
                    check(prefs.probeComplete) { "A successful restored native test is required first" }
                    prefs.nativeQualified = true
                    prefs.fadesQualified = data.optBoolean("fades", false)
                    status("Operator acknowledgement recorded; exact APK/path acceptance remains separate")
                }
                "close_worker" -> {
                    check(!preview && !prefs.backlightEnabled && automation.temporary("brightness") == null) { "Pause before closing worker" }
                    submit("close", true) { controller.close() }
                }
                "test_fade" -> testFade(data.getInt("seconds"), data.getInt("delta"))
                "probe" -> check(probe()) { "Probe prerequisites or queue admission not satisfied" }
                else -> executeCommand(name, Intent().putExtra("phase", data.optString("phase", "")))
            }
        } catch (error: Exception) {
            prefs.store.edit().putString("adb-error", error.message ?: error.javaClass.simpleName).apply()
            status("ADB request rejected: ${error.message}")
        }
    }
    /** Bounded experiment, available before fade acknowledgement. Always attempts restore. */
    private fun testFade(seconds: Int, delta: Int) {
        check(prefs.identityReady && screenOn && !preview && !recoveryRequired && !prefs.backlightEnabled && automation.temporary("brightness") == null) { "Read, pause and resolve recovery before testing" }
        val token = cancelTransition()
        check(submit("test-fade") {
            if (!screenOn || token != generation.get()) return@submit
            check(controller.pendingRecovery() == null) { "Resolve saved ownership before testing" }
            preview = true
            prefs.store.edit().putBoolean("probe-active", true).putBoolean("probe-restored", false).apply()
            controller.release()
            val start = controller.begin("probe")
            val target = (start + delta).coerceIn(1, 100)
            require(target != start) { "Test target equals current brightness" }
            val began = SystemClock.elapsedRealtime()
            main.postDelayed(revertPreview, seconds * 1000L + 15000)
            prefs.store.edit().putLong("test-start-at", System.currentTimeMillis()).putInt("test-seconds", seconds)
                .putInt("test-from", start).putInt("test-target", target).apply()
            fun step() {
                if (token != generation.get() || destroyed || !screenOn) return
                val elapsed = SystemClock.elapsedRealtime() - began
                controller.observe()
                val value = QuantisedFade.value(start, target, seconds * 1000L, elapsed)
                controller.write(value) { token == generation.get() && screenOn && !destroyed }
                if (elapsed < seconds * 1000L) nextStep(token,
                    QuantisedFade.nextDelay(start, target, seconds * 1000L, elapsed)) { step() }
                else main.postDelayed(revertPreview, 5000)
            }
            step()
        }) { "Native queue busy; test not started" }
    }
    @Volatile private var lastStatus = ""
    private fun newController() = BacklightController(
        if (prefs.rootBackend) A4RootBacklightBackend(this) else A4BacklightBackend(this),
        RecoveryJournal(this), session)
    private fun status(message: String, persist: Boolean = true) {
        if (lastStatus == message) return
        lastStatus = message
        if (persist) prefs.status = message
        Log.i("RedMoonTV", message)
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra("status", message))
    }
    private fun readNative() {
        prefs.readStarted()
        status("Reading native backlight via ${if (prefs.rootBackend) "root (Magisk may prompt)" else "APK worker"}; no panel write")
        val level = controller.read()
        prefs.readFinished()
        prefs.store.edit().remove("current-native-error").apply()
        status("Native backlight $level / 100. ${if (prefs.writeContextReady) "Ready for a visible test." else "Read only: range or picture/boot context is unavailable."}")
    }
    private fun fail(error: Exception) {
        finishBrightness("failed", error.javaClass.simpleName + ": " + (error.message ?: "Native failure"))
        prefs.store.edit().putString("native-read-state", "failed").putBoolean("probe-active", false).apply()
        cancelTransition()
        val message = error.message ?: error.javaClass.simpleName
        val edit = prefs.store.edit().putBoolean("native-blocked", true)
        try { controller.release() } catch (_: Exception) { controller.close() }
        val safe = try { controller.pendingRecovery()?.pending == null } catch (_: Exception) { false }
        var event: String? = null
        if (error is ExternalBacklightChange && safe) {
            val until = try { prefs.policy().at(Instant.now(), ZoneId.systemDefault()).next.instant.toEpochMilli() }
                catch (_: IllegalArgumentException) { prefs.backlightEnabled = false; null }
            // Ownership is relinquished. Neither stale cleanup nor Restore may undo this adjustment.
            controller.discard(); recoveryRequired = false; preview = false
            automation.temporary("brightness", null)
            if (until != null) edit.putLong("manual-hold-until", until) else edit.remove("manual-hold-until")
            event = "Manual brightness retained until the next scheduled phase or explicit Resume"
        } else if (error is BacklightContextChange && safe && !preview) {
            controller.discard(); recoveryRequired = false
            edit.remove("manual-hold-until").putBoolean("native-blocked", false)
            event = "Picture/input/HDR context changed; old restoration ownership discarded"
            main.postDelayed({ if (!destroyed && screenOn) reconcile(true) }, 1000)
        } else {
            edit.remove("manual-hold-until"); recoveryRequired = RecoveryJournal.exists(this)
        }
        if (!RecoveryJournal.exists(this)) preview = false
        if (event != null) {
            prefs.readFinished()
            edit.putString("native-read-state", "ready").remove("current-native-error")
                .putString("last-native-event", event).putLong("last-native-event-at", System.currentTimeMillis())
        } else edit.putString("current-native-error", message).putString("last-native-error", message)
            .putLong("last-native-error-at", System.currentTimeMillis())
        edit.commit()
        operation = "idle"
        status(event ?: "Native control stopped: $message. No automatic setter retry.")
        controller.close()
    }
    private fun submit(key: String = "interactive", critical: Boolean = false, work: () -> Unit): Boolean {
        if (destroyed) return false
        val token = generation.get()
        val admitted = queue.submit(key, critical) {
            if (!critical && token != generation.get()) return@submit
            operation = key
            try { work() } finally { operation = "idle" }
        }
        if (!admitted && key !in listOf("monitor", "fade")) status("Native request coalesced or busy: $key")
        return admitted
    }
    private fun clearOverride() {
        overridePhase = null; overrideUntil = null
        prefs.store.edit().remove("override-phase").remove("override-until").apply()
    }
    private fun stopIfIdle() {
        if (!destroyed && !automation.pending() && queue.count == 0 && !prefs.backlightEnabled && !prefs.warmthEnabled && overridePhase == null && !preview && (overlay == null || overlay!!.profile.isOff)) stopSelf()
    }
    private fun reconcile(force: Boolean) {
        val policy = try { prefs.policy() } catch (e: IllegalArgumentException) { status("Invalid schedule: ${e.message}"); return }
        val now = Instant.now()
        val expired = automation.expire(now.toEpochMilli())
        val expiredTemporary = "brightness" in expired
        if (expiredTemporary) {
            cancelTransition()
            submit("automation-expiry", true) {
                controller.release()
                check(controller.pendingRecovery()?.pending == null) { "Uncertain expired transition requires recovery" }
                controller.discard()
            }
        }
        if ("warmth" in expired) finishWarmth("cancelled", "Temporary warmth expired")
        val state = policy.at(now, ZoneId.systemDefault())
        if (overrideUntil?.let { now >= it } == true) clearOverride()
        scheduleNext(state.next.instant)
        updateWarmth(policy, now)
        if (!screenOn || preview) return
        val temporary = automation.temporary("brightness")
        if (temporary?.optString("kind") == "hold") return
        if (!prefs.backlightEnabled && overridePhase == null && temporary == null) return
        if (!prefs.identityReady) { status("Checking installed vendor identity; no native writes"); return }
        if (!prefs.nativeQualified) { status("Native qualification required; no backlight writes"); return }
        val baseSetting = (temporary?.optString("phase")?.takeIf { it.isNotEmpty() }?.let { Phase.valueOf(it) }
            ?: overridePhase)?.let { p -> policy.settings.first { it.phase == p } } ?: state.current
        val setting = temporary?.takeIf { it.optString("kind") == "value" }?.let {
            baseSetting.copy(level = it.getInt("value"), fadeSeconds = it.getInt("fade"))
        } ?: baseSetting
        val boundaryKey = "${state.next.instant.toEpochMilli()}:${setting.phase}"
        val newBoundary = prefs.store.getString("active-boundary", "") != boundaryKey
        val permission = NativeWriteGate.decide(prefs.store.getBoolean("native-blocked", false),
            prefs.store.getLong("manual-hold-until", 0).takeIf { it > 0 }, now.toEpochMilli(), recoveryRequired)
        if (permission == WritePermission.BLOCK) {
            status(if (recoveryRequired) "Native recovery pending; use Diagnostics before scheduling"
                else if (prefs.store.contains("manual-hold-until")) "Manual adjustment retained until the next boundary or explicit Resume"
                else "Native failure remains blocked until explicit Resume: ${prefs.store.getString("last-native-error", "see Diagnostics")}")
            return
        }
        val releaseManual = permission == WritePermission.RELEASE_MANUAL_HOLD
        if (!force && !newBoundary && !releaseManual && !expiredTemporary) return
        val automationId = temporary?.optString("request_id")?.takeIf { it.isNotEmpty() } ?: resumeRequest
        resumeRequest = null
        val token = cancelTransition()
        synchronized(automationLock) {
            brightnessRequest = automationId?.takeIf { automation.find(it)?.optString("state") in setOf("accepted", "running") }?.let { token to it }
        }
        submit("transition", true) {
            if (token != generation.get() || !screenOn) return@submit
            controller.release()
            val prior = controller.pendingRecovery()
            if (prior != null && prior.session != session && !newBoundary && !releaseManual && !rebaseAfterExplicitResume)
                controller.adoptSchedule()
            if (releaseManual || rebaseAfterExplicitResume || newBoundary) {
                check(controller.pendingRecovery()?.pending == null) { "Uncertain write still requires explicit recovery" }
                controller.discard(); rebaseAfterExplicitResume = false
                prefs.store.edit().remove("manual-hold-until").apply()
            }
            if (setting.fadeSeconds > 0 && !prefs.fadesQualified) {
                prefs.backlightEnabled = false
                finishBrightness("rejected", "Fade qualification required", token)
                status("Configured fade requires qualification; use the ADB fade test. Schedule is paused, not replaced with an instant change.")
                return@submit
            }
            val current = controller.begin("schedule")
            prefs.store.edit().putBoolean("native-blocked", false).putString("active-boundary", boundaryKey).remove("current-native-error").apply()
            val duration = setting.fadeSeconds * 1000L
            val fade = BrightnessFade(policy, setting, current, duration, prefs.strictTransitions, policy.band(baseSetting))
            status("${setting.phase.title} transition: confirmed native $current → ${setting.level}")
            val began = SystemClock.elapsedRealtime()
            val session = TransitionSession(fade, current,
                { SystemClock.elapsedRealtime() - began }, { token != generation.get() || !screenOn || destroyed }) { controller.write(it) { token == generation.get() && screenOn && !destroyed } }
            fun step() {
                if (token != generation.get() || destroyed) return
                val running = session.tick()
                status("${setting.phase.title}: confirmed native ${session.lastApplied} → ${setting.level}; ${if (running) "fading" else "applied"}" +
                    if (!prefs.fadesQualified && setting.fadeSeconds > 0) "; fade disabled pending persistence qualification" else "", persist = !running)
                if (running) nextStep(token, fade.nextDelay(SystemClock.elapsedRealtime() - began)) { step() }
                else {
                    controller.observe(); prefs.readFinished()
                    finishBrightness("applied", "Confirmed native ${session.lastApplied}; expires ${temporary?.optLong("expires") ?: 0}", token)
                }
            }
            step(); monitor(token)
        }
    }
    private fun scheduleNext(nextPhase: Instant) {
        var next = nextPhase
        automation.nextExpiry()?.let { if (it < next.toEpochMilli()) next = Instant.ofEpochMilli(it) }
        try {
            if (prefs.warmthEnabled && !prefs.linked) {
                val warmNext = DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(Instant.now(), ZoneId.systemDefault()).second
                if (warmNext < next) next = warmNext
            }
        } catch (error: IllegalArgumentException) { prefs.warmthStatus = "Invalid warmth window; backlight remains independent" }
        val alarm = getSystemService(AlarmManager::class.java)
        val event = PendingIntent.getBroadcast(this, 71, Intent(this, TvReceiver::class.java).setAction(BOUNDARY), PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(event)
        if (prefs.backlightEnabled || prefs.warmthEnabled || overridePhase != null || automation.pending()) {
            try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, next.toEpochMilli(), event) }
            catch (_: SecurityException) { alarm.set(AlarmManager.RTC, next.toEpochMilli(), event) }
        }
    }
    private fun warmthOnly(immediate: Boolean = false) {
        val now = Instant.now()
        val policy = prefs.policy()
        scheduleNext(policy.at(now, ZoneId.systemDefault()).next.instant)
        updateWarmth(policy, now, immediate)
        sendBroadcast(Intent(STATUS).setPackage(packageName))
    }
    private fun clearWarmth() {
        finishWarmth("cancelled", "Overlay suspended")
        warmthTask?.let { main.removeCallbacks(it) }; warmthTask = null; warmthTarget = null
        try { overlay?.onDestroy() } catch (error: RuntimeException) { prefs.warmthStatus = "Warmth teardown failed: ${error.message}" }
        overlay = null
    }
    private fun updateWarmth(policy: BrightnessPolicy, now: Instant, immediate: Boolean = false) {
        try { applyWarmth(policy, now, immediate) }
        catch (error: RuntimeException) { finishWarmth("failed", "Overlay unavailable"); clearWarmth(); prefs.warmthStatus = "Warmth unavailable: ${error.message}. Backlight remains independent." }
    }
    private fun applyWarmth(policy: BrightnessPolicy, now: Instant, immediate: Boolean) {
        val state = policy.at(now, ZoneId.systemDefault())
        val customActive = if (prefs.warmthEnabled && !prefs.linked) DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(now, ZoneId.systemDefault()).first else false
        val warmOverride = automation.temporary("warmth")
        val strength = when {
            screenOn && warmOverride != null -> warmOverride.getInt("value")
            !screenOn || !prefs.warmthEnabled -> 0
            prefs.linked -> prefs.warmth(state.current.phase)
            customActive -> prefs.store.getInt("custom-strength", 40)
            else -> 0
        }
        if (strength > 0 && !Settings.canDrawOverlays(this)) {
            finishWarmth("failed", "Overlay permission required")
            clearWarmth(); prefs.warmthStatus = "Warmth unavailable: allow display over other apps. Backlight is independent."; return
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        require(strength in 0..100 && colour in 0..100 && prefs.warmthFade in 0..888) { "Invalid warmth settings" }
        val target = WarmthState(colour, strength)
        if (warmthTask != null && warmthTarget != target) finishWarmth("cancelled", "Warmth schedule changed")
        if (!immediate && warmthTask != null && warmthTarget == target) return
        warmthTask?.let { main.removeCallbacks(it) }; warmthTask = null; warmthTarget = target
        if (overlay == null && strength == 0) { prefs.warmthStatus = "Warmth off"; finishWarmth("applied", "Warmth off"); return }
        if (overlay == null) overlay = Overlay(this)
        val view = overlay!!
        val fade = WarmthTransition(WarmthState(view.profile.color, view.profile.intensity), target, if (immediate) 0 else (warmthFadeOverride ?: warmOverride?.optInt("fade", prefs.warmthFade) ?: prefs.warmthFade) * 1000L)
        val began = SystemClock.elapsedRealtime()
        val task = object : Runnable {
            override fun run() {
                if (destroyed || warmthTask !== this) return
                try {
                    val elapsed = SystemClock.elapsedRealtime() - began
                    val value = fade.valueAt(elapsed)
                    val profile = Profile(value.colour, value.strength, 0, false)
                    if (view.profile != profile) view.profile = profile
                    if (elapsed < fade.duration) main.postDelayed(this, fade.nextDelay(elapsed))
                    else { warmthTask = null; finishWarmth("applied", "Overlay strength ${value.strength}; optical coverage unverified"); stopIfIdle() }
                } catch (error: RuntimeException) { finishWarmth("failed", "Overlay failed"); clearWarmth(); prefs.warmthStatus = "Warmth failed: ${error.message}" }
            }
        }
        warmthTask = task; task.run()
        prefs.warmthStatus = if (strength == 0) "Warmth off" else "Warmth $strength%; playback coverage unverified"
    }
    private val revertPreview = Runnable { if (screenOn && preview) restore() }
    private fun probe(): Boolean {
        if (!prefs.identityReady) { status("Wait for the installed vendor identity check"); return false }
        if (!screenOn || preview || recoveryRequired) { status("Wake the TV and resolve any recovery record before probing"); return false }
        if (prefs.backlightEnabled || overridePhase != null || automation.temporary("brightness") != null) { status("Pause scheduling before a probe"); return false }
        cancelTransition()
        val token = generation.get()
        return submit("probe") {
            if (!screenOn || token != generation.get()) return@submit
            preview = true
            prefs.store.edit().putBoolean("probe-restored", false).putBoolean("probe-active", true).apply()
            controller.release()
            // A previous schedule's confirmed saved original must be explicitly kept/restored.
            check(controller.pendingRecovery() == null) { "Keep or restore the saved session before probing" }
            val saved = controller.begin("probe")
            val target = if (saved > 5) saved - 5 else saved + 5
            try {
                controller.write(target) { token == generation.get() && screenOn && !destroyed }
                status("Probe $saved → $target confirmed. Inspect panel and native slider; reverting in 15 seconds.")
            } finally {
                if (!destroyed) main.postDelayed(revertPreview, 15000)
            }
        }
    }
    private fun restore() {
        if (!screenOn) { status("Restore deferred until the TV is awake"); return }
        cancelTransition(); automation.temporary("brightness", null); prefs.backlightEnabled = false; clearOverride()
        main.removeCallbacks(revertPreview)
        submit("restore", true) {
            if (!screenOn) { status("Restore deferred until wake"); return@submit }
            val kind = controller.pendingRecovery()?.kind
            try {
                if (controller.restore()) {
                    prefs.readFinished()
                    if (kind == "probe") prefs.store.edit().putBoolean("probe-restored", true).putString("probe-signature", prefs.signature).apply()
                    recoveryRequired = false; prefs.store.edit().putBoolean("native-blocked", false).remove("manual-hold-until").apply()
                    status("Native original restored and read back. Confirm visible panel response before qualification.")
                } else status("No saved original to restore")
            } finally { preview = false; prefs.store.edit().putBoolean("probe-active", false).apply() }
        }
    }
    override fun onDestroy() {
        finishWarmth("cancelled", "Service stopped")
        active = false; liveSession = null; instance = null
        destroyed = true; cancelTransition()
        idleTask?.cancel(false)
        main.removeCallbacksAndMessages(null); clearWarmth(); unregisterReceiver(screens)
        // Let a bounded in-flight call settle before releasing its worker and lease.
        closeControllerExecutor(worker) { controller.close() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        writer.println(DiagnosticsExporter.report(this))
        writer.println("operation=$operation; pending_native=${queue.count}; queue=${worker.queue.size}; screen_on=$screenOn; preview=$preview")
    }
    companion object {
        @Volatile private var instance: TvDisplayService? = null
        fun runtime(): JSONObject = instance?.let {
            JSONObject().put("operation", it.operation).put("pending", it.queue.count).put("screen_on", it.screenOn)
                .put("preview", it.preview).put("generation", it.generation.get())
        } ?: JSONObject().put("operation", "stopped").put("pending", 0)
        @Volatile var liveSession: String? = null
            private set
        @Volatile var active: Boolean = false
            private set
        private const val CHANNEL = "tv-display"
        const val STATUS = "com.cbkii.redmoontv.STATUS"
        const val BOUNDARY = "com.cbkii.redmoontv.BOUNDARY"
        fun command(context: Context, name: String = "reconcile", phase: Phase? = null) {
            context.startForegroundService(Intent(context, TvDisplayService::class.java).putExtra("command", name).putExtra("phase", phase?.name))
        }
    }
}
