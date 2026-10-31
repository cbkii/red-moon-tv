/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.app.*
import android.app.Notification
import android.content.*
import android.os.*
import android.provider.Settings
import android.hardware.display.DisplayManager
import android.view.Display
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
    private val readiness = ReadinessRetry()
    private var retryTask: Runnable? = null
    @Volatile private var retryAt = 0L
    @Volatile private var needsNativeStart = true
    private var catchUp = true
    @Volatile private var brightnessState = DisplayRunState.STARTING
    @Volatile private var warmthState = DisplayRunState.DISABLED
    @Volatile private var startReason = "creating"
    private val createdAt = SystemClock.elapsedRealtime()
    private var foregroundAt = 0L
    private var nativeAppliedAt = 0L
    private var warmthAttachedAt = 0L
    private var nextBoundaryAt = 0L
    private var firstStart = true
    private var healthAt = 0L
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
    private val identityWaits = mutableSetOf<String>()
    private var overlay: TvWarmthOverlay? = null
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
    private fun nextStep(token: Int, delay: Long, step: () -> Unit) {
        synchronized(timerLock) {
            if (!destroyed && token == generation.get()) {
                fadeTask?.cancel(false)
                fadeTask = worker.schedule({ if (token == generation.get() && !destroyed) submit("fade", work = step) }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }
    private fun displayAwake(): Boolean {
        val power = getSystemService(PowerManager::class.java).isInteractive
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return power && (display == null || display.state != Display.STATE_OFF)
    }
    private fun powerChanged() {
        val awake = displayAwake()
        if (awake == screenOn) return
        screenOn = awake
        cancelTransition(); cancelRetry()
        needsNativeStart = true; catchUp = true
        StartupEvents.record(this, if (awake) "wake" else "standby")
        if (awake) {
            readiness.reset()
            if (preview) revertPreview.run() else reconcile(true, true)
        } else {
            brightnessState = DisplayRunState.STANDBY; warmthState = DisplayRunState.STANDBY
            clearWarmth(); status("Standby: native writes suspended")
            submit("standby-close", true) { controller.close() }
        }
    }
    private val screens = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                TvStorage.context(context) // Finish a first-upgrade migration; never read CE while locked.
                prefs.prepareIdentity { if (!destroyed) reconcile(true, true) }
            } else powerChanged()
        }
    }
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { if (id == Display.DEFAULT_DISPLAY) powerChanged() }
        override fun onDisplayRemoved(id: Int) { if (id == Display.DEFAULT_DISPLAY) powerChanged() }
        override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) powerChanged() }
    }
    private val health = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (!prefs.identityReady) prefs.prepareIdentity { if (!destroyed) reconcile(false, startupCatchUp(catchUp, needsNativeStart, prefs.backlightEnabled)) }
            healthAt = SystemClock.elapsedRealtime()
            powerChanged()
            if (automation.nextExpiry()?.let { it <= System.currentTimeMillis() } == true) reconcile(false)
            try { if (screenOn) {
                if (overlay?.attached() == false) overlay?.repair()
                updateWarmth(prefs.policy(), Instant.now(), overlay == null)
                val sample = NativeSnapshot.value
                if ((prefs.backlightEnabled || automation.temporary("brightness")?.optString("kind") == "value") && !preview && queue.count == 0 &&
                    (sample == null || SystemClock.elapsedRealtime() - sample.elapsed >= 50000)) {
                    if (controller.hasClaim()) submit("monitor") { controller.observe() }
                    else if (retryTask == null) reconcile(false, true)
                }
            }
            } catch (error: RuntimeException) {
                warmthState = DisplayRunState.RETRY_WAIT
                prefs.warmthStatus = "Health reconciliation failed: ${error.message}"
            }
            main.postDelayed(this, 60000)
        }
    }
    private fun cancelRetry() {
        retryTask?.let { main.removeCallbacks(it) }; retryTask = null; retryAt = 0L
    }
    override fun onCreate() {
        super.onCreate()
        // Promotion precedes storage migration, vendor hashing or root work.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "TV display control", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(71, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_icon_half_moon)
            .setContentTitle("Red Moon TV").setContentText("Independent backlight and warmth schedules")
            .setContentIntent(open).setOngoing(true).build())
        foregroundAt = SystemClock.elapsedRealtime()
        active = true; liveSession = session; instance = this
        prefs = TvPreferences(this)
        automation = AutomationState(this); automation.interrupted()
        // A previous process may have died during a read. A new lifecycle owner
        // must not advertise that old in-flight operation, including boot for warmth.
        StartupEvents.record(this, "foreground", session)
        if (prefs.store.getString("native-read-state", "") == "reading")
            prefs.store.edit().remove("native-read-state").apply()
        controller = newController()
        recoveryRequired = try { RecoveryJournal(this).load()?.let { it.pending != null || it.kind != "schedule" || it.identity.isBlank() } == true }
            catch (_: Exception) { true }
        prefs.store.edit().putBoolean("probe-active", false).apply()
        if (!recoveryRequired && prefs.store.getBoolean("retryable-read", false) && !prefs.store.getBoolean("access-required", false))
            prefs.store.edit().putBoolean("native-blocked", false).apply()
        if (recoveryRequired) status("Interrupted native write/probe: automatic brightness waits for explicit Restore or Keep; warmth remains independent.")
        overridePhase = Phase.values().firstOrNull { it.name == prefs.store.getString("override-phase", "") }
        overrideUntil = prefs.store.getLong("override-until", 0).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        screenOn = displayAwake()
        registerReceiver(screens, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_UNLOCKED)
        })
        getSystemService(DisplayManager::class.java).registerDisplayListener(displays, main)
        main.postDelayed(health, 60000)
        TvContinuity.arm(this)
        prefs.prepareIdentity { if (!destroyed) reconcile(false, startupCatchUp(catchUp, needsNativeStart, prefs.backlightEnabled)) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra("reason") ?: if (intent == null) "sticky_restart" else "command"
        startReason = reason
        if (firstStart) { firstStart = false; StartupEvents.record(this, "start", reason) }
        executeCommand(intent?.getStringExtra("command") ?: "ensure_running", intent)
        TvContinuity.arm(this)
        main.post { stopIfIdle(); TvContinuity.arm(this) }
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
            "ensure_running" -> {
                if (intent?.getStringExtra("reason") == "health" && prefs.store.getBoolean("retryable-read", false) && !recoveryRequired && !prefs.store.getBoolean("access-required", false) && retryTask == null) {
                    readiness.reset(); prefs.store.edit().putBoolean("native-blocked", false).apply()
                }
                if (retryTask == null) reconcile(false, startupCatchUp(catchUp, needsNativeStart, prefs.backlightEnabled))
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
                cancelRetry(); readiness.reset()
                prefs.store.edit().remove("access-required").apply()
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
                clearOverride(); prefs.backlightEnabled = false; cancelTransition(); cancelRetry()
                if (preview) revertPreview.run() else submit("pause", true) { controller.release(); status("Backlight paused; current level retained") }
                reconcile(false)
            }
            "phase", "resume" -> {
                automation.temporary("brightness", null)
                if (prefs.nativeQualified && !recoveryRequired && !preview) {
                    val phase = if (command == "phase") Phase.values().firstOrNull { it.name == intent?.getStringExtra("phase") } else null
                    if (command == "phase" && phase == null) { status("Invalid phase command"); return }
                    rebaseAfterExplicitResume = prefs.store.contains("manual-hold-until")
                    cancelRetry(); readiness.reset()
                    prefs.store.edit().remove("access-required").apply()
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
            "warmth" -> { finishWarmth("cancelled", "Warmth changed in controls"); automation.temporary("warmth", null); warmthOnly() }
            "warmth_off" -> { finishWarmth("cancelled", "Warmth disabled"); automation.temporary("warmth", null); prefs.warmthEnabled = false; warmthOnly(true); TvContinuity.arm(this) }
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
    private fun finishWarmth(state: String, detail: String, expectedId: String? = null) {
        val id = warmthRequest ?: return
        if (expectedId != null && id != expectedId) return
        warmthRequest = null; warmthFadeOverride = null
        automation.result(id, "warmth", state, detail)
    }
    private fun handleAutomation(intent: Intent, refreshed: Boolean = false) {
        val id = intent.getStringExtra("automation-id") ?: return
        try {
            val extras = intent.getBundleExtra("automation-extras") ?: error("Missing request")
            val r = AutomationContract.parse(AutomationState.data(extras), id)
            if (automation.find(id)?.optString("state") !in AutomationResult.active) return
            check(automation.authorised(extras.getString("token"))) { "Integration disabled or token revoked" }
            if (r.affects("brightness") && r.command !in setOf("brightness_pause", "snooze", "cancel_transition", "show_status", "open_controls", "reconcile") && !prefs.identityReady) {
                if (!identityWaits.add(id)) return
                val timeout = Runnable {
                    if (identityWaits.remove(id)) {
                        automation.result(id, "dispatch", "rejected", "Vendor identity unavailable after 10 seconds")
                        stopIfIdle()
                    }
                }
                main.postDelayed(timeout, 10000)
                prefs.prepareIdentity {
                    if (!destroyed && prefs.identityReady && identityWaits.remove(id)) {
                        main.removeCallbacks(timeout); handleAutomation(intent)
                    }
                }
                return
            }
            val sampleAge = NativeSnapshot.value?.let { SystemClock.elapsedRealtime() - it.elapsed }
            if (AutomationContract.needsRelativeRead(r.command, automation.temporary("brightness")?.optString("kind") == "value", sampleAge) &&
                screenOn && prefs.identityReady && prefs.nativeQualified && !recoveryRequired && !preview) {
                check(!refreshed) { "Fresh native sample expired before dispatch" }
                check(submit("automation-read-${r.id}", true) {
                    try {
                        controller.read(); prefs.readFinished()
                        main.post {
                            if (!destroyed) handleAutomation(intent, true)
                            else automation.result(id, "brightness", "cancelled", "Service stopped")
                        }
                    } catch (error: Exception) {
                        automation.result(id, "brightness", "failed", "Fresh native reading unavailable")
                        throw error
                    }
                }) { "Relative read unavailable" }
                return
            }
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
                    check(submit("automation-context-${r.id}", true) {
                        try {
                            if (screenOn && (prefs.backlightEnabled || automation.temporary("brightness")?.optString("kind") == "value")) {
                                if (controller.hasClaim()) controller.observe() else controller.read()
                                prefs.readFinished()
                            }
                            main.post {
                                if (!destroyed) {
                                    reconcile(false, needsNativeStart)
                                    automation.result(id, "control", "applied", "Context and schedule checked; target completion is separate")
                                } else automation.result(id, "control", "cancelled", "Service stopped")
                            }
                        } catch (error: Exception) {
                            automation.result(id, "control", "failed", "Context check failed; inspect recovery/status")
                            throw error
                        }
                    }) { "Context check unavailable" }
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
            val token = cancelTransition(); cancelRetry(); clearOverride()
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
                    // Retain confirmed restoration evidence; pausing never means Keep.
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
            cancelTransition(); cancelRetry(); readiness.reset(); prefs.store.edit().remove("access-required").apply()
            automation.temporary("brightness", null); clearOverride()
            if (r.command == "brightness_resume") prefs.backlightEnabled = true
            needsNativeStart = true
            rebaseAfterExplicitResume = prefs.store.contains("manual-hold-until")
            prefs.store.edit().remove("manual-hold-until").putBoolean("native-blocked", false).apply()
            // Attach request to the scheduled transition without creating a persistent temporary value.
            if (prefs.backlightEnabled) reconcileAutomationResume(r.id)
            else automation.result(r.id, "brightness", "applied", "Temporary override cleared; saved schedule remains disabled")
            return
        }
        check(screenOn && prefs.identityReady && prefs.nativeQualified && !recoveryRequired && !preview && !prefs.store.getBoolean("access-required", false)) { "Wake TV, finish qualification and resolve recovery first" }
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
                "ensure_running" -> TvContinuity.ensureRunning(this, "adb")
                "fault" -> {
                    check(BuildConfig.APPLICATION_ID.endsWith(".testing") || BuildConfig.DEBUG) { "Fault injection is TESTING/debug only" }
                    when (data.getString("fault")) {
                        "detach_overlay" -> overlay?.close()
                        "stop_service" -> { cancelTransition(); stopSelf() }
                        "clear" -> ValidationBackend.clear()
                        else -> ValidationBackend.arm(data.getString("fault"))
                    }
                }
                "configure" -> {
                    require(!preview && !prefs.backlightEnabled && automation.temporary("brightness") == null) { "Pause before configuring" }
                    val policy = TvAdbControl.policy(data)
                    val start = data.optInt("warmth_start", prefs.warmthStart)
                    val end = data.optInt("warmth_end", prefs.warmthEnd)
                    require(start != end) { "Warmth start/end must differ" }
                    if (policy != null) prefs.save(policy)
                    prefs.store.edit().apply {
                        if (data.has("transition_mode")) putString("transition-mode", data.getString("transition_mode"))
                        for ((json, pref) in listOf("warmth" to "warmth", "linked" to "linked", "enforce_schedule" to "enforce-schedule", "external_control" to "external-control"))
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
                    prefs.store.all.filterKeys { key -> key in setOf("warmth", "linked", "warmth-start", "warmth-end", "warmth-fade", "custom-strength", "warmth-colour", "transition-mode", "enforce-schedule", "external-control") ||
                        Phase.values().any { key in listOf("$it-level", "$it-start", "$it-fade", "warmth-$it") } }.forEach { (key, value) -> values.put(key, value) }
                    snapshot.put("values", values)
                    check(prefs.store.edit().putString("adb-config-backup", snapshot.toString()).commit())
                }
                "restore_config" -> {
                    check(!preview && !prefs.backlightEnabled && automation.temporary("brightness") == null && queue.count == 0) { "Restore/Keep the test and pause first" }
                    val saved = JSONObject(prefs.store.getString("adb-config-backup", null) ?: error("No ADB configuration backup"))
                    val keys = setOf("warmth", "linked", "warmth-start", "warmth-end", "warmth-fade", "custom-strength", "warmth-colour", "transition-mode", "enforce-schedule", "external-control") +
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
        ValidationBackend(if (prefs.rootBackend) A4RootBacklightBackend(this) else A4BacklightBackend(this)),
        RecoveryJournal(this), session) { prefs.signature }
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
        cancelTransition(); needsNativeStart = true
        val message = error.message ?: error.javaClass.simpleName
        val edit = prefs.store.edit().putBoolean("native-blocked", true)
        try { controller.release() } catch (_: Exception) { controller.close() }
        val safe = try { controller.pendingRecovery()?.pending == null } catch (_: Exception) { false }
        var event: String? = null
        if (error is ExternalBacklightChange && safe && prefs.enforceSchedule && !preview) {
            controller.retireConfirmed(); recoveryRequired = false
            edit.remove("manual-hold-until").putBoolean("native-blocked", false)
            event = "Scheduled brightness enforcement: readback changed; fresh baseline will be used"
            main.postDelayed({ if (!destroyed && screenOn && (prefs.backlightEnabled || automation.temporary("brightness")?.optString("kind") == "value")) reconcile(true, true) }, 1000)
        } else if (error is ExternalBacklightChange && safe) {
            val until = try { prefs.policy().at(Instant.now(), ZoneId.systemDefault()).next.instant.toEpochMilli() }
                catch (_: IllegalArgumentException) { null }
            // Ownership is relinquished. Neither stale cleanup nor Restore may undo this adjustment.
            controller.discard(); recoveryRequired = false; preview = false
            automation.temporary("brightness", null)
            if (until != null) edit.putLong("manual-hold-until", until) else edit.remove("manual-hold-until")
            brightnessState = DisplayRunState.MANUAL_HOLD
            event = "Manual brightness retained until the next scheduled phase or explicit Resume"
        } else if (error is BacklightContextChange && safe && !preview) {
            controller.retireConfirmed(); recoveryRequired = false
            edit.remove("manual-hold-until").putBoolean("native-blocked", false)
            event = "Picture/input/HDR context changed; old restoration ownership discarded"
            main.postDelayed({ if (!destroyed && screenOn && (prefs.backlightEnabled || automation.temporary("brightness")?.optString("kind") == "value")) reconcile(true, true) }, 1000)
        } else {
            edit.remove("manual-hold-until"); recoveryRequired = try { controller.pendingRecovery()?.let { it.pending != null || it.kind != "schedule" || it.identity.isBlank() } == true } catch (_: Exception) { true }
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
        if (event == null) {
            val uncertain = !safe
            val access = error is NativeAccessRequired || message.contains("permission", true) || message.contains("denied", true)
            val transient = listOf("unavailable", "timed out", "died", "disconnected", "binder", "service not", "outcome unknown")
                .any { message.contains(it, true) }
            val delay = readiness.next(uncertain, access, transient)
            prefs.store.edit().putBoolean("retryable-read", !uncertain && !access && transient).apply()
            brightnessState = when {
                uncertain || recoveryRequired -> DisplayRunState.RECOVERY_REQUIRED
                access -> DisplayRunState.ACCESS_REQUIRED
                transient -> DisplayRunState.RETRY_WAIT
                else -> DisplayRunState.ACCESS_REQUIRED
            }
            if (access) prefs.store.edit().putBoolean("access-required", true).commit()
            StartupEvents.record(this, "native_failure", "$brightnessState:$message")
            if (delay != null && prefs.backlightEnabled && screenOn) {
                val token = generation.get()
                val task = Runnable {
                    retryTask = null; retryAt = 0L
                    if (!destroyed && token == generation.get() && prefs.backlightEnabled && screenOn && !recoveryRequired) {
                        prefs.store.edit().putBoolean("native-blocked", false).apply()
                        reconcile(false, true)
                    }
                }
                retryTask = task; retryAt = SystemClock.elapsedRealtime() + delay
                main.postDelayed(task, delay)
            }
        }
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
        if (::prefs.isInitialized) TvContinuity.arm(this)
        if (!destroyed && identityWaits.isEmpty() && warmthRequest == null && !automation.pending() && queue.count == 0 && !prefs.backlightEnabled && !prefs.warmthEnabled && overridePhase == null && !preview && (overlay == null || overlay!!.profile.isOff)) stopSelf()
    }
    private fun reconcile(force: Boolean, bootstrap: Boolean = false) {
        val policy = try { prefs.policy() } catch (e: IllegalArgumentException) { status("Invalid schedule: ${e.message}"); return }
        val now = Instant.now()
        val expiringBrightness = automation.temporary("brightness")
        val expired = automation.expire(now.toEpochMilli())
        val expiredTemporary = "brightness" in expired
        if (expiredTemporary) {
            if (expiringBrightness?.optString("kind") == "hold" && prefs.backlightEnabled) rebaseAfterExplicitResume = true
            cancelTransition()
            submit("automation-expiry", true) {
                controller.release()
                check(controller.pendingRecovery()?.pending == null) { "Uncertain expired transition requires recovery" }
                // Expiry retires timers, not the saved restoration baseline.
            }
        }
        if ("warmth" in expired) finishWarmth("cancelled", "Temporary warmth expired")
        val state = policy.at(now, ZoneId.systemDefault())
        if (overrideUntil?.let { now >= it } == true) clearOverride()
        scheduleNext(state.next.instant)
        updateWarmth(policy, now, (bootstrap && catchUp) || overlay == null)
        catchUp = false
        TvContinuity.arm(this)
        if (!screenOn) { brightnessState = DisplayRunState.STANDBY; return }
        if (preview) return
        val temporary = automation.temporary("brightness")
        if (temporary?.optString("kind") == "hold") { brightnessState = DisplayRunState.MANUAL_HOLD; return }
        if (!prefs.backlightEnabled && overridePhase == null && temporary == null) { brightnessState = DisplayRunState.DISABLED; return }
        if (!prefs.identityReady) { brightnessState = DisplayRunState.STARTING; status("Checking installed vendor identity; no native writes"); return }
        if (!prefs.nativeQualified) { brightnessState = DisplayRunState.QUALIFICATION_REQUIRED; status("Native qualification required; no backlight writes"); return }
        if (prefs.store.getBoolean("access-required", false)) { brightnessState = DisplayRunState.ACCESS_REQUIRED; return }
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
            brightnessState = when {
                recoveryRequired -> DisplayRunState.RECOVERY_REQUIRED
                prefs.store.contains("manual-hold-until") -> DisplayRunState.MANUAL_HOLD
                else -> DisplayRunState.ACCESS_REQUIRED
            }
            status(if (recoveryRequired) "Native recovery pending; use Diagnostics before scheduling"
                else if (prefs.store.contains("manual-hold-until")) "Manual adjustment retained until the next boundary or explicit Resume"
                else "Native failure remains blocked until explicit Resume: ${prefs.store.getString("last-native-error", "see Diagnostics")}")
            return
        }
        val releaseManual = permission == WritePermission.RELEASE_MANUAL_HOLD
        if (!force && !newBoundary && !releaseManual && !expiredTemporary && !needsNativeStart) return
        if (!force && queue.count > 0 && !expiredTemporary) return
        val automationId = temporary?.optString("request_id")?.takeIf { it.isNotEmpty() } ?: resumeRequest
        resumeRequest = null
        val token = cancelTransition()
        synchronized(automationLock) {
            brightnessRequest = automationId?.takeIf { automation.find(it)?.optString("state") in setOf("accepted", "running") }?.let { token to it }
        }
        submit("transition", true) {
            if (token != generation.get() || !screenOn) return@submit
            controller.release()
            if (releaseManual || rebaseAfterExplicitResume) {
                check(controller.pendingRecovery()?.pending == null) { "Uncertain write still requires explicit recovery" }
                controller.retireConfirmed(); rebaseAfterExplicitResume = false
                prefs.store.edit().remove("manual-hold-until").apply()
            }
            if (setting.fadeSeconds > 0 && !prefs.fadesQualified) {
                brightnessState = DisplayRunState.QUALIFICATION_REQUIRED
                status("Configured fade requires qualification; use the ADB fade test. Saved schedule remains enabled; native operation waits for qualification.")
                finishBrightness("rejected", "Fade qualification required", token)
                return@submit
            }
            val current = controller.begin("schedule")
            if (nativeAppliedAt == 0L) StartupEvents.record(this, "native_ready", "${setting.phase}:$current")
            prefs.store.edit().putBoolean("native-blocked", false).putString("active-boundary", boundaryKey).remove("current-native-error").apply()
            needsNativeStart = false; readiness.reset(); cancelRetry()
            prefs.readStarted(); prefs.readFinished()
            prefs.store.edit().remove("retryable-read").apply()
            brightnessState = DisplayRunState.ACTIVE
            val duration = if (bootstrap) 0L else setting.fadeSeconds * 1000L
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
                    if (nativeAppliedAt == 0L) {
                        nativeAppliedAt = SystemClock.elapsedRealtime()
                        StartupEvents.record(this, "native_applied", "${setting.phase}:${session.lastApplied}")
                    }
                }
            }
            step()
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
        nextBoundaryAt = if (prefs.backlightEnabled || prefs.warmthEnabled || overridePhase != null || automation.pending()) next.toEpochMilli() else 0L
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
        try { overlay?.close() } catch (error: RuntimeException) { prefs.warmthStatus = "Warmth teardown failed: ${error.message}" }
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
            warmthState = DisplayRunState.ACCESS_REQUIRED; clearWarmth(); prefs.warmthStatus = "Warmth unavailable: allow display over other apps. Backlight is independent."; return
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        require(strength in 0..100 && colour in 0..100 && prefs.warmthFade in 0..888) { "Invalid warmth settings" }
        val target = WarmthState(colour, strength)
        if (warmthTask != null && warmthTarget != target) finishWarmth("cancelled", "Warmth schedule changed")
        if (!immediate && warmthTarget == target && (warmthTask != null || (warmthRequest == null && (overlay?.attached() == true || strength == 0)))) return
        warmthTask?.let { main.removeCallbacks(it) }; warmthTask = null; warmthTarget = target
        if (overlay == null && strength == 0) { warmthState = DisplayRunState.DISABLED; prefs.warmthStatus = "Warmth off"; finishWarmth("applied", "Warmth off"); return }
        if (overlay == null) overlay = TvWarmthOverlay(this)
        val view = overlay!!
        val fade = WarmthTransition(WarmthState(view.profile.color, view.profile.intensity), target, if (immediate) 0 else (warmthFadeOverride ?: warmOverride?.optInt("fade", prefs.warmthFade) ?: prefs.warmthFade) * 1000L)
        val began = SystemClock.elapsedRealtime()
        val warmId = warmthRequest
        fun complete(attempt: Int = 0) {
            if (warmId == null || warmId != warmthRequest) return
            if (strength == 0 || view.attached()) finishWarmth("applied", "Overlay strength $strength; optical coverage unverified", warmId)
            else if (attempt < 3) main.postDelayed({ complete(attempt + 1) }, 200)
            else finishWarmth("failed", "Overlay window did not attach", warmId)
        }
        val task = object : Runnable {
            override fun run() {
                if (destroyed || warmthTask !== this) return
                try {
                    val elapsed = SystemClock.elapsedRealtime() - began
                    val value = fade.valueAt(elapsed)
                    val profile = Profile(value.colour, value.strength, 0, false)
                    if (view.profile != profile) view.profile = profile
                    if (elapsed < fade.duration) main.postDelayed(this, fade.nextDelay(elapsed))
                    else { warmthTask = null; complete(); stopIfIdle() }
                    warmthState = if (strength > 0) DisplayRunState.ACTIVE else DisplayRunState.DISABLED
                    if (strength > 0 && warmthAttachedAt == 0L) main.post {
                        if (view.attached()) {
                            warmthAttachedAt = SystemClock.elapsedRealtime()
                            StartupEvents.record(this@TvDisplayService, "warmth_attached", "$strength")
                        }
                    }
                } catch (error: RuntimeException) { finishWarmth("failed", "Overlay failed"); clearWarmth(); prefs.warmthStatus = "Warmth failed: ${error.message}" }
            }
        }
        warmthTask = task; task.run()
        prefs.warmthStatus = if (strength == 0) "Warmth off" else "Warmth $strength%; playback coverage unverified"
    }
    private val revertPreview = Runnable { if (screenOn && preview) restore() }
    private fun probe(): Boolean {
        if (!prefs.identityReady) { brightnessState = DisplayRunState.STARTING; status("Wait for the installed vendor identity check"); return false }
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
        identityWaits.forEach { automation.result(it, "dispatch", "cancelled", "Service stopped while checking vendor identity") }; identityWaits.clear()
        finishWarmth("cancelled", "Service stopped")
        active = false; liveSession = null; instance = null
        destroyed = true; cancelTransition(); cancelRetry()
        idleTask?.cancel(false)
        main.removeCallbacksAndMessages(null); clearWarmth(); unregisterReceiver(screens)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displays)
        StartupEvents.record(this, "destroy", session)
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
                .put("session", it.session).put("pid", android.os.Process.myPid())
                .put("brightness_state", it.brightnessState.name).put("warmth_state", it.warmthState.name)
                .put("reason", it.startReason).put("created_elapsed", it.createdAt).put("foreground_elapsed", it.foregroundAt)
                .put("native_applied_elapsed", it.nativeAppliedAt).put("warmth_attached_elapsed", it.warmthAttachedAt)
                .put("overlay_attached", it.overlay?.attached() == true)
                .put("overlay_strength", it.overlay?.profile?.intensity ?: 0)
                .put("health_elapsed", it.healthAt).put("next_health_elapsed", TvContinuity.nextHealth)
                .put("next_boundary_at", it.nextBoundaryAt).put("retry_at_elapsed", it.retryAt)
                .put("retry_attempts", it.readiness.attempts).put("native_claimed", it.controller.hasClaim())
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
