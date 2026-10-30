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
    private val pendingNative = AtomicInteger()
    @Volatile private var destroyed = false
    private lateinit var prefs: TvPreferences
    private lateinit var controller: BacklightController
    private var overlay: Overlay? = null
    private var warmthTask: Runnable? = null
    private var warmthTarget: WarmthState? = null
    @Volatile private var preview = false
    @Volatile private var recoveryRequired = false
    @Volatile private var rebaseAfterExplicitResume = false
    private var overridePhase: Phase? = null
    private var overrideUntil: Instant? = null
    private var appliedPhase: Phase? = null
    @Volatile private var screenOn = true
    private fun cancelTransition(): Int {
        val token = generation.incrementAndGet()
        synchronized(timerLock) { fadeTask?.cancel(false); fadeTask = null }
        return token
    }
    private fun nextStep(token: Int, delay: Long, step: () -> Unit) {
        synchronized(timerLock) {
            if (!destroyed && token == generation.get()) {
                fadeTask?.cancel(false)
                fadeTask = worker.schedule({ if (token == generation.get() && !destroyed) submit(step) }, delay, TimeUnit.MILLISECONDS)
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
                worker.execute { controller.close() } // Retain recovery evidence.
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        active = true
        prefs = TvPreferences(this)
        prefs.prepareIdentity { if (!destroyed) reconcile(false) }
        controller = newController()
        recoveryRequired = RecoveryJournal.exists(this)
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
        val command = intent?.getStringExtra("command") ?: "reconcile"
        when (command) {
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
                if (command == "read_restart" && (prefs.backlightEnabled || preview)) status("Pause scheduling/preview before restarting the native worker")
                else submit {
                    if (command == "read_restart") controller.close()
                    readNative()
                }
            }
            "probe" -> probe()
            "revert", "restore" -> restore()
            "keep" -> {
                cancelTransition(); preview = false; main.removeCallbacks(revertPreview)
                prefs.backlightEnabled = false; clearOverride()
                submit { controller.release(); controller.discard(); recoveryRequired = false; prefs.store.edit().putBoolean("native-blocked", false).remove("manual-hold-until").apply(); status("Recovery cleared by user; current native level retained") }
            }
            "pause" -> {
                clearOverride(); prefs.backlightEnabled = false; cancelTransition()
                if (preview) revertPreview.run() else submit { controller.release(); status("Backlight paused; current level retained") }
                reconcile(false)
            }
            "phase", "resume" -> {
                if (prefs.nativeQualified && !recoveryRequired && !preview) {
                    val phase = if (command == "phase") Phase.values().firstOrNull { it.name == intent?.getStringExtra("phase") } else null
                    if (command == "phase" && phase == null) { status("Invalid phase command"); return START_STICKY }
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
            "warmth_off" -> { prefs.warmthEnabled = false; warmthOnly(true) }
            else -> reconcile(command != "boundary")
        }
        main.post { stopIfIdle() }
        return START_STICKY
    }
    @Volatile private var lastStatus = ""
    private fun newController() = BacklightController(
        if (prefs.rootBackend) A4RootBacklightBackend(this) else A4BacklightBackend(this),
        RecoveryJournal(this), UUID.randomUUID().toString())
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
        status("Native backlight $level / 100. ${if (prefs.writeContextReady) "Ready for a visible test." else "Read only: range or picture/boot context is unavailable."}")
    }
    private fun fail(error: Exception) {
        prefs.store.edit().putString("native-read-state", "failed").putBoolean("probe-active", false).apply()
        cancelTransition()
        val edit = prefs.store.edit().putBoolean("native-blocked", true)
        if (error is ExternalBacklightChange) {
            val until = try { prefs.policy().at(Instant.now(), ZoneId.systemDefault()).next.instant.toEpochMilli() } catch (_: IllegalArgumentException) { null }
            if (until != null) edit.putLong("manual-hold-until", until) else edit.remove("manual-hold-until")
        } else { edit.remove("manual-hold-until"); recoveryRequired = RecoveryJournal.exists(this) }
        edit.putString("last-native-error", error.message ?: error.javaClass.simpleName).commit() // Failure prohibition must survive ordinary process restart.
        if (!RecoveryJournal.exists(this)) preview = false
        status("Native control stopped: ${error.message}. No automatic setter retry.\n${prefs.store.getString("native-diagnostics", "No loader diagnostics")}")
        try { controller.release() } catch (_: Exception) { controller.close() }
    }
    private fun submit(work: () -> Unit) {
        if (destroyed) return
        if (pendingNative.incrementAndGet() > 3) {
            pendingNative.decrementAndGet(); status("Native operation pending; wait for its result"); return
        }
        worker.execute {
            try { work() } catch (error: Exception) { fail(error) }
            finally { pendingNative.decrementAndGet(); main.post { stopIfIdle() } }
        }
    }
    private fun clearOverride() {
        overridePhase = null; overrideUntil = null
        prefs.store.edit().remove("override-phase").remove("override-until").apply()
    }
    private fun stopIfIdle() {
        if (!destroyed && pendingNative.get() == 0 && !prefs.backlightEnabled && !prefs.warmthEnabled && overridePhase == null && !preview && (overlay == null || overlay!!.profile.isOff)) stopSelf()
    }
    private fun reconcile(force: Boolean) {
        val policy = try { prefs.policy() } catch (e: IllegalArgumentException) { status("Invalid schedule: ${e.message}"); return }
        val now = Instant.now()
        val state = policy.at(now, ZoneId.systemDefault())
        if (overrideUntil?.let { now >= it } == true) clearOverride()
        scheduleNext(state.next.instant)
        updateWarmth(policy, now)
        if (!screenOn || preview) return
        if (!prefs.backlightEnabled && overridePhase == null) return
        if (!prefs.identityReady) { status("Checking installed vendor identity; no native writes"); return }
        if (!prefs.nativeQualified) { status("Native qualification required; no backlight writes"); return }
        val setting = overridePhase?.let { p -> policy.settings.first { it.phase == p } } ?: state.current
        val newBoundary = appliedPhase != setting.phase
        val permission = NativeWriteGate.decide(prefs.store.getBoolean("native-blocked", false),
            prefs.store.getLong("manual-hold-until", 0).takeIf { it > 0 }, now.toEpochMilli(), recoveryRequired)
        if (permission == WritePermission.BLOCK) {
            status(if (recoveryRequired) "Native recovery pending; use Diagnostics before scheduling"
                else if (prefs.store.contains("manual-hold-until")) "Manual adjustment retained until the next boundary or explicit Resume"
                else "Native failure remains blocked until explicit Resume: ${prefs.store.getString("last-native-error", "see Diagnostics")}")
            return
        }
        val releaseManual = permission == WritePermission.RELEASE_MANUAL_HOLD
        if (!force && !newBoundary && !releaseManual) return
        appliedPhase = setting.phase
        val token = cancelTransition()
        submit {
            if (token != generation.get()) return@submit
            controller.release()
            if (releaseManual || rebaseAfterExplicitResume) {
                check(controller.pendingRecovery()?.pending == null) { "Uncertain write still requires explicit recovery" }
                controller.discard(); rebaseAfterExplicitResume = false
                prefs.store.edit().remove("manual-hold-until").apply()
            }
            val current = controller.begin("schedule")
            prefs.store.edit().putBoolean("native-blocked", false).apply()
            val duration = if (prefs.fadesQualified) setting.fadeSeconds * 1000L else 0L
            val fade = BrightnessFade(policy, setting, current, duration)
            status("${setting.phase.title} transition: confirmed native $current → ${setting.level}")
            val began = SystemClock.elapsedRealtime()
            val session = TransitionSession(fade, current,
                { SystemClock.elapsedRealtime() - began }, { token != generation.get() || !screenOn || destroyed }) { controller.write(it) }
            fun step() {
                if (token != generation.get() || destroyed) return
                val running = session.tick()
                status("${setting.phase.title}: confirmed native ${session.lastApplied} → ${setting.level}; ${if (running) "fading" else "applied"}" +
                    if (!prefs.fadesQualified && setting.fadeSeconds > 0) "; fade disabled pending persistence qualification" else "", persist = !running)
                if (running) nextStep(token, fade.nextDelay(SystemClock.elapsedRealtime() - began)) { step() }
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
        try { overlay?.onDestroy() } catch (error: RuntimeException) { prefs.warmthStatus = "Warmth teardown failed: ${error.message}" }
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
            clearWarmth(); prefs.warmthStatus = "Warmth unavailable: allow display over other apps. Backlight is independent."; return
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        require(strength in 0..100 && colour in 0..100 && prefs.warmthFade in 0..888) { "Invalid warmth settings" }
        val target = WarmthState(colour, strength)
        if (!immediate && warmthTask != null && warmthTarget == target) return
        warmthTask?.let { main.removeCallbacks(it) }; warmthTask = null; warmthTarget = target
        if (overlay == null && strength == 0) { prefs.warmthStatus = "Warmth off"; return }
        if (overlay == null) overlay = Overlay(this)
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
                } catch (error: RuntimeException) { clearWarmth(); prefs.warmthStatus = "Warmth failed: ${error.message}" }
            }
        }
        warmthTask = task; task.run()
        prefs.warmthStatus = if (strength == 0) "Warmth off" else "Warmth $strength%; playback coverage unverified"
    }
    private val revertPreview = Runnable { if (screenOn && preview) restore() }
    private fun probe() {
        if (!prefs.identityReady) { status("Wait for the installed vendor identity check"); return }
        if (!screenOn || preview || recoveryRequired) { status("Wake the TV and resolve any recovery record before probing"); return }
        if (prefs.backlightEnabled || overridePhase != null) { status("Pause scheduling before a probe"); return }
        cancelTransition()
        preview = true
        prefs.store.edit().putBoolean("probe-restored", false).apply()
        prefs.store.edit().putBoolean("probe-active", true).apply()
        submit {
            controller.release()
            // A previous schedule's confirmed saved original must be explicitly kept/restored.
            check(controller.pendingRecovery() == null) { "Keep or restore the saved session before probing" }
            val saved = controller.begin("probe")
            val target = if (saved > 5) saved - 5 else saved + 5
            try {
                controller.write(target)
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
        submit {
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
        active = false
        destroyed = true; cancelTransition()
        main.removeCallbacksAndMessages(null); clearWarmth(); unregisterReceiver(screens)
        // Let a bounded in-flight call settle before releasing its worker and lease.
        worker.execute { controller.close() }
        worker.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        writer.println(DiagnosticsExporter.report(this))
        writer.println("pending_native=${pendingNative.get()}; queue=${worker.queue.size}; screen_on=$screenOn; preview=$preview")
    }
    companion object {
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
