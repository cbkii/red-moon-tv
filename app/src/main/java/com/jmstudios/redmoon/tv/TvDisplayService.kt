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
        needsNativeStart = true
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
            if (!prefs.identityReady) prefs.prepareIdentity { if (!destroyed) reconcile(false, true) }
            healthAt = SystemClock.elapsedRealtime()
            powerChanged()
            try { if (screenOn) {
                if (overlay?.attached() == false) overlay?.repair()
                updateWarmth(prefs.policy(), Instant.now(), overlay == null)
                val sample = NativeSnapshot.value
                if (prefs.backlightEnabled && !preview && queue.count == 0 &&
                    (sample == null || System.currentTimeMillis() - sample.at >= 50000)) {
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
        StartupEvents.record(this, "foreground", session)
        if (prefs.store.getString("native-read-state", "") == "reading")
            prefs.store.edit().remove("native-read-state").apply()
        controller = newController()
        recoveryRequired = try { RecoveryJournal(this).load()?.let { it.pending != null || it.kind != "schedule" } == true }
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
        prefs.prepareIdentity { if (!destroyed) reconcile(false, true) }
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
            "ensure_running" -> {
                if (intent?.getStringExtra("reason") == "health" && prefs.store.getBoolean("retryable-read", false) && !recoveryRequired && !prefs.store.getBoolean("access-required", false) && retryTask == null) {
                    readiness.reset(); prefs.store.edit().putBoolean("native-blocked", false).apply()
                }
                if (retryTask == null) reconcile(false, needsNativeStart || overlay == null)
            }
            "use_root", "use_apk", "use_root_read", "use_apk_read" -> {
                if (prefs.backlightEnabled || preview || overridePhase != null) status("Pause native scheduling/preview before changing backend")
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
                cancelTransition(); preview = false; main.removeCallbacks(revertPreview)
                prefs.backlightEnabled = false; clearOverride()
                submit("keep", true) { controller.release(); controller.discard(); recoveryRequired = false; prefs.store.edit().putBoolean("native-blocked", false).remove("manual-hold-until").apply(); status("Recovery cleared by user; current native level retained") }
            }
            "pause" -> {
                clearOverride(); prefs.backlightEnabled = false; cancelTransition(); cancelRetry()
                if (preview) revertPreview.run() else submit("pause", true) { controller.release(); status("Backlight paused; current level retained") }
                reconcile(false)
            }
            "phase", "resume" -> {
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
            "warmth" -> warmthOnly()
            "warmth_off" -> { prefs.warmthEnabled = false; warmthOnly(true); TvContinuity.arm(this) }
            else -> reconcile(command != "boundary")
        }
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
                    require(!preview && !prefs.backlightEnabled) { "Pause before configuring" }
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
                    check(!preview && !prefs.backlightEnabled && queue.count == 0) { "Restore/Keep the test and pause first" }
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
                    check(!preview && !prefs.backlightEnabled) { "Pause before closing worker" }
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
        check(prefs.identityReady && screenOn && !preview && !recoveryRequired && !prefs.backlightEnabled) { "Read, pause and resolve recovery before testing" }
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
            main.postDelayed({ if (!destroyed && screenOn && prefs.backlightEnabled) reconcile(true, true) }, 1000)
        } else if (error is ExternalBacklightChange && safe) {
            val until = try { prefs.policy().at(Instant.now(), ZoneId.systemDefault()).next.instant.toEpochMilli() }
                catch (_: IllegalArgumentException) { null }
            // Ownership is relinquished. Neither stale cleanup nor Restore may undo this adjustment.
            controller.discard(); recoveryRequired = false; preview = false
            if (until != null) edit.putLong("manual-hold-until", until) else edit.remove("manual-hold-until")
            brightnessState = DisplayRunState.MANUAL_HOLD
            event = "Manual brightness retained until the next scheduled phase or explicit Resume"
        } else if (error is BacklightContextChange && safe && !preview) {
            controller.retireConfirmed(); recoveryRequired = false
            edit.remove("manual-hold-until").putBoolean("native-blocked", false)
            event = "Picture/input/HDR context changed; old restoration ownership discarded"
            main.postDelayed({ if (!destroyed && screenOn && prefs.backlightEnabled) reconcile(true, true) }, 1000)
        } else {
            edit.remove("manual-hold-until"); recoveryRequired = try { controller.pendingRecovery()?.let { it.pending != null || it.kind != "schedule" } == true } catch (_: Exception) { true }
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
        if (!destroyed && queue.count == 0 && !prefs.backlightEnabled && !prefs.warmthEnabled && overridePhase == null && !preview && (overlay == null || overlay!!.profile.isOff)) stopSelf()
    }
    private fun reconcile(force: Boolean, bootstrap: Boolean = false) {
        val policy = try { prefs.policy() } catch (e: IllegalArgumentException) { status("Invalid schedule: ${e.message}"); return }
        val now = Instant.now()
        val state = policy.at(now, ZoneId.systemDefault())
        if (overrideUntil?.let { now >= it } == true) clearOverride()
        scheduleNext(state.next.instant)
        updateWarmth(policy, now, bootstrap)
        TvContinuity.arm(this)
        if (!screenOn) { brightnessState = DisplayRunState.STANDBY; return }
        if (preview) return
        if (!prefs.backlightEnabled && overridePhase == null) { brightnessState = DisplayRunState.DISABLED; return }
        if (!prefs.identityReady) { brightnessState = DisplayRunState.STARTING; status("Checking installed vendor identity; no native writes"); return }
        if (!prefs.nativeQualified) { brightnessState = DisplayRunState.QUALIFICATION_REQUIRED; status("Native qualification required; no backlight writes"); return }
        if (prefs.store.getBoolean("access-required", false)) { brightnessState = DisplayRunState.ACCESS_REQUIRED; return }
        val setting = overridePhase?.let { p -> policy.settings.first { it.phase == p } } ?: state.current
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
        if (!force && !newBoundary && !releaseManual && !needsNativeStart) return
        if (!force && queue.count > 0) return
        val token = cancelTransition()
        submit("transition", true) {
            if (token != generation.get() || !screenOn) return@submit
            controller.release()
            if (releaseManual || rebaseAfterExplicitResume) {
                check(controller.pendingRecovery()?.pending == null) { "Uncertain write still requires explicit recovery" }
                controller.discard(); rebaseAfterExplicitResume = false
                prefs.store.edit().remove("manual-hold-until").apply()
            }
            if (setting.fadeSeconds > 0 && !prefs.fadesQualified) {
                brightnessState = DisplayRunState.QUALIFICATION_REQUIRED
                status("Configured fade requires qualification; use the ADB fade test. Saved schedule remains enabled; native operation waits for qualification.")
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
            val fade = BrightnessFade(policy, setting, current, duration, prefs.strictTransitions)
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
                else if (nativeAppliedAt == 0L) {
                    nativeAppliedAt = SystemClock.elapsedRealtime()
                    StartupEvents.record(this, "native_applied", "${setting.phase}:${session.lastApplied}")
                }
            }
            step()
        }
    }
    private fun scheduleNext(nextPhase: Instant) {
        var next = nextPhase
        try {
            if (prefs.warmthEnabled && !prefs.linked) {
                val warmNext = DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(Instant.now(), ZoneId.systemDefault()).second
                if (warmNext < next) next = warmNext
            }
        } catch (error: IllegalArgumentException) { prefs.warmthStatus = "Invalid warmth window; backlight remains independent" }
        nextBoundaryAt = next.toEpochMilli()
        val alarm = getSystemService(AlarmManager::class.java)
        val event = PendingIntent.getBroadcast(this, 71, Intent(this, TvReceiver::class.java).setAction(BOUNDARY), PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(event)
        if (prefs.backlightEnabled || prefs.warmthEnabled || overridePhase != null) {
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
        warmthTask?.let { main.removeCallbacks(it) }; warmthTask = null; warmthTarget = null
        try { overlay?.close() } catch (error: RuntimeException) { prefs.warmthStatus = "Warmth teardown failed: ${error.message}" }
        overlay = null
    }
    private fun updateWarmth(policy: BrightnessPolicy, now: Instant, immediate: Boolean = false) {
        try { applyWarmth(policy, now, immediate) }
        catch (error: RuntimeException) { clearWarmth(); prefs.warmthStatus = "Warmth unavailable: ${error.message}. Backlight remains independent." }
    }
    private fun applyWarmth(policy: BrightnessPolicy, now: Instant, immediate: Boolean) {
        val state = policy.at(now, ZoneId.systemDefault())
        val customActive = if (prefs.warmthEnabled && !prefs.linked) DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(now, ZoneId.systemDefault()).first else false
        val strength = when {
            !screenOn || !prefs.warmthEnabled -> 0
            prefs.linked -> prefs.warmth(state.current.phase)
            customActive -> prefs.store.getInt("custom-strength", 40)
            else -> 0
        }
        if (strength > 0 && !Settings.canDrawOverlays(this)) {
            warmthState = DisplayRunState.ACCESS_REQUIRED; clearWarmth(); prefs.warmthStatus = "Warmth unavailable: allow display over other apps. Backlight is independent."; return
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        require(strength in 0..100 && colour in 0..100 && prefs.warmthFade in 0..888) { "Invalid warmth settings" }
        val target = WarmthState(colour, strength)
        if (!immediate && warmthTarget == target && (warmthTask != null || overlay?.attached() == true || strength == 0)) return
        warmthTask?.let { main.removeCallbacks(it) }; warmthTask = null; warmthTarget = target
        if (overlay == null && strength == 0) { warmthState = DisplayRunState.DISABLED; prefs.warmthStatus = "Warmth off"; return }
        if (overlay == null) overlay = TvWarmthOverlay(this)
        val view = overlay!!
        val fade = WarmthTransition(WarmthState(view.profile.color, view.profile.intensity), target, if (immediate) 0 else prefs.warmthFade * 1000L)
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
                    else { warmthTask = null; stopIfIdle() }
                    warmthState = if (strength > 0) DisplayRunState.ACTIVE else DisplayRunState.DISABLED
                    if (strength > 0 && warmthAttachedAt == 0L) main.post {
                        if (view.attached()) {
                            warmthAttachedAt = SystemClock.elapsedRealtime()
                            StartupEvents.record(this@TvDisplayService, "warmth_attached", "$strength")
                        }
                    }
                } catch (error: RuntimeException) { clearWarmth(); prefs.warmthStatus = "Warmth failed: ${error.message}" }
            }
        }
        warmthTask = task; task.run()
        prefs.warmthStatus = if (strength == 0) "Warmth off" else "Warmth $strength%; playback coverage unverified"
    }
    private val revertPreview = Runnable { if (screenOn && preview) restore() }
    private fun probe(): Boolean {
        if (!prefs.identityReady) { brightnessState = DisplayRunState.STARTING; status("Wait for the installed vendor identity check"); return false }
        if (!screenOn || preview || recoveryRequired) { status("Wake the TV and resolve any recovery record before probing"); return false }
        if (prefs.backlightEnabled || overridePhase != null) { status("Pause scheduling before a probe"); return false }
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
        cancelTransition(); prefs.backlightEnabled = false; clearOverride()
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
