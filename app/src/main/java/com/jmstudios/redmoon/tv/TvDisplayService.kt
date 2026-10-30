/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.animation.ValueAnimator
import android.app.*
import android.app.Notification
import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import com.jmstudios.redmoon.*
import java.time.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One serial controller, non-wakeup boundaries, independent warmth failure containment. */
class TvDisplayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicInteger()
    private val pendingNative = AtomicInteger()
    @Volatile private var destroyed = false
    private lateinit var prefs: TvPreferences
    private lateinit var controller: BacklightController
    private var overlay: Overlay? = null
    private var warmthAnimator: ValueAnimator? = null
    @Volatile private var preview = false
    @Volatile private var recoveryRequired = false
    @Volatile private var manualHold = false
    private var overridePhase: Phase? = null
    private var overrideUntil: Instant? = null
    private var appliedPhase: Phase? = null
    @Volatile private var screenOn = true
    private val screens = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            generation.incrementAndGet()
            if (screenOn) {
                if (preview) revertPreview.run()
                else reconcile(true)
            } else {
                clearWarmth()
                status("Standby: native writes suspended")
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        prefs = TvPreferences(this)
        controller = BacklightController(A4BacklightBackend(this), RecoveryJournal(this), UUID.randomUUID().toString())
        recoveryRequired = RecoveryJournal.exists(this)
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
            "read" -> submit { status("Confirmed native backlight: ${controller.read()} / 100.\n${prefs.store.getString("native-diagnostics", "")}") }
            "probe" -> probe()
            "revert", "restore" -> restore()
            "keep" -> {
                generation.incrementAndGet(); preview = false; main.removeCallbacks(revertPreview)
                prefs.backlightEnabled = false; clearOverride()
                submit { controller.release(); controller.discard(); recoveryRequired = false; manualHold = false; status("Recovery cleared by user; current native level retained") }
            }
            "pause" -> {
                clearOverride(); prefs.backlightEnabled = false; generation.incrementAndGet()
                if (preview) revertPreview.run() else submit { controller.release(); status("Backlight paused; current level retained") }
                reconcile(false)
            }
            "phase", "resume" -> {
                if (prefs.nativeQualified && !recoveryRequired && !preview) {
                    clearOverride(); prefs.backlightEnabled = true
                    prefs.store.edit().putBoolean("native-blocked", false).apply()
                    if (command == "phase") {
                        overridePhase = Phase.values().firstOrNull { it.name == intent?.getStringExtra("phase") }
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
    private fun status(message: String) {
        if (prefs.status == message) return
        prefs.status = message
        Log.i("RedMoonTV", message)
        sendBroadcast(Intent(STATUS).setPackage(packageName))
    }
    private fun fail(error: Exception) {
        generation.incrementAndGet()
        prefs.store.edit().putBoolean("native-blocked", true).apply()
        if (error is ExternalBacklightChange) manualHold = true
        else recoveryRequired = RecoveryJournal.exists(this)
        if (!RecoveryJournal.exists(this)) preview = false
        status("Native control stopped: ${error.message}. No automatic setter retry.")
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
        if (!screenOn || preview || recoveryRequired) return
        if (!prefs.backlightEnabled && overridePhase == null) return
        if (!prefs.nativeQualified) { status("Native qualification required; no backlight writes"); return }
        val setting = overridePhase?.let { p -> policy.settings.first { it.phase == p } } ?: state.current
        val newBoundary = appliedPhase != setting.phase
        if (manualHold && !newBoundary && !force) return
        if (!force && !newBoundary) return
        if (prefs.store.getBoolean("native-blocked", false) && !manualHold && !force) return
        appliedPhase = setting.phase
        val token = generation.incrementAndGet()
        submit {
            if (token != generation.get()) return@submit
            controller.release()
            if (manualHold) { controller.discard(); manualHold = false }
            val current = controller.begin("schedule")
            prefs.store.edit().putBoolean("native-blocked", false).apply()
            val duration = if (prefs.fadesQualified) setting.fadeSeconds * 1000L else 0L
            val fade = BrightnessFade(policy, setting, current, duration)
            val began = SystemClock.elapsedRealtime()
            val session = TransitionSession(fade, current,
                { SystemClock.elapsedRealtime() - began }, { token != generation.get() || !screenOn || destroyed }) { controller.write(it) }
            fun step() {
                if (token != generation.get() || destroyed) return
                val running = session.tick()
                status("${setting.phase.title}: confirmed native ${session.lastApplied} → ${setting.level}; ${if (running) "fading" else "applied"}" +
                    if (!prefs.fadesQualified && setting.fadeSeconds > 0) "; fade disabled pending persistence qualification" else "")
                if (running) worker.schedule({ if (token == generation.get()) submit { step() } },
                    fade.nextDelay(SystemClock.elapsedRealtime() - began), TimeUnit.MILLISECONDS)
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
        try { warmthAnimator?.cancel() } catch (_: RuntimeException) {}
        warmthAnimator = null
        try { overlay?.onDestroy() } catch (error: RuntimeException) { prefs.warmthStatus = "Warmth teardown failed: ${error.message}" }
        overlay = null
    }
    private fun updateWarmth(policy: BrightnessPolicy, now: Instant, immediate: Boolean = false) {
        try { applyWarmth(policy, now, immediate) }
        catch (error: RuntimeException) { clearWarmth(); prefs.warmthStatus = "Warmth unavailable: ${error.message}. Backlight remains independent." }
    }
    private fun applyWarmth(policy: BrightnessPolicy, now: Instant, immediate: Boolean = false) {
        val state = policy.at(now, ZoneId.systemDefault())
        val customActive = if (prefs.warmthEnabled && !prefs.linked) DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(now, ZoneId.systemDefault()).first else false
        val strength = when {
            !screenOn || !prefs.warmthEnabled -> 0
            prefs.linked -> prefs.warmth(state.current.phase)
            customActive -> prefs.store.getInt("custom-strength", 40)
            else -> 0
        }
        if (strength > 0 && !Settings.canDrawOverlays(this)) {
            prefs.warmthStatus = "Warmth unavailable: allow display over other apps. Backlight is independent."
            clearWarmth()
            return
        }
        val colour = prefs.store.getInt("warmth-colour", 40)
        require(strength in 0..100 && colour in 0..100 && prefs.warmthFade in 0..888) { "Invalid warmth settings" }
        val target = Profile(colour, strength, 0, false)
        if (overlay == null && strength == 0) { prefs.warmthStatus = "Warmth off"; return }
        if (overlay == null) overlay = Overlay(this)
        if (overlay?.profile == target) return
        warmthAnimator?.cancel()
        val view = overlay!!
        warmthAnimator = ValueAnimator.ofObject(ProfileEvaluator(), view.profile, target).apply {
            duration = if (immediate) 0L else prefs.warmthFade * 1000L
            addUpdateListener {
                try { view.profile = it.animatedValue as Profile }
                catch (e: RuntimeException) { cancel(); main.post { clearWarmth(); prefs.warmthStatus = "Warmth failed: ${e.message}" } }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { stopIfIdle() }
            })
            start()
        }
        prefs.warmthStatus = if (strength == 0) "Warmth off" else "Warmth $strength%; playback coverage unverified"
    }
    private val revertPreview = Runnable { if (screenOn && preview) restore() }
    private fun probe() {
        if (!screenOn || preview || recoveryRequired) { status("Wake the TV and resolve any recovery record before probing"); return }
        if (prefs.backlightEnabled || overridePhase != null) { status("Pause scheduling before a probe"); return }
        generation.incrementAndGet()
        preview = true
        prefs.store.edit().putBoolean("probe-restored", false).apply()
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
        generation.incrementAndGet(); prefs.backlightEnabled = false; clearOverride()
        main.removeCallbacks(revertPreview)
        submit {
            val kind = controller.pendingRecovery()?.kind
            try {
                if (controller.restore()) {
                    if (kind == "probe") prefs.store.edit().putBoolean("probe-restored", true).putString("probe-signature", prefs.signature).apply()
                    recoveryRequired = false; manualHold = false
                    status("Native original restored and read back. Confirm visible panel response before qualification.")
                } else status("No saved original to restore")
            } finally { preview = false }
        }
    }
    override fun onDestroy() {
        destroyed = true; generation.incrementAndGet()
        main.removeCallbacksAndMessages(null); clearWarmth(); unregisterReceiver(screens)
        // Let a bounded in-flight call settle before releasing its worker and lease.
        worker.execute { controller.close() }
        worker.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        private const val CHANNEL = "tv-display"
        const val STATUS = "com.cbkii.redmoontv.STATUS"
        const val BOUNDARY = "com.cbkii.redmoontv.BOUNDARY"
        fun command(context: Context, name: String = "reconcile", phase: Phase? = null) {
            context.startForegroundService(Intent(context, TvDisplayService::class.java).putExtra("command", name).putExtra("phase", phase?.name))
        }
    }
}
