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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Independent backlight and warmth state, one native writer, event-driven scheduling. */
class TvDisplayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicInteger()
    private val pendingNative = AtomicInteger()
    @Volatile private var destroyed = false
    private lateinit var prefs: TvPreferences
    private lateinit var backend: BacklightBackend
    private var overlay: Overlay? = null
    private var warmthAnimator: ValueAnimator? = null
    @Volatile private var previewOriginal: Int? = null
    @Volatile private var previewLast: Int? = null
    private var previewContext: String? = null
    private var originalContext: String? = null
    private var lastWritten: Int? = null
    private var original: Int? = null
    private var overridePhase: Phase? = null
    private var overrideUntil: Instant? = null
    private var appliedPhase: Phase? = null
    @Volatile private var screenOn = true
    private val screens = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            generation.incrementAndGet()
            if (screenOn) { if (previewOriginal != null) revertPreview.run(); reconcile(true) } else { warmthAnimator?.cancel(); overlay?.onDestroy(); overlay = null; status("Standby: native writes suspended") }
        }
    }
    override fun onCreate() {
        super.onCreate()
        prefs = TvPreferences(this)
        backend = A4BacklightBackend(this)
        original = prefs.store.getInt("original", -1).takeIf { it in 0..100 }
        lastWritten = prefs.store.getInt("last-written", -1).takeIf { it in 0..100 }
        originalContext = prefs.store.getString("original-context", null)
        overridePhase = Phase.values().firstOrNull { it.name == prefs.store.getString("override-phase", "") }
        overrideUntil = prefs.store.getLong("override-until", 0).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
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
            "read" -> submit { status("Native backlight: ${backend.read()} / 100. Physical effect remains to be verified.") }
            "probe" -> probe()
            "keep" -> { previewOriginal = null; previewLast = null; main.removeCallbacks(revertPreview); reconcile(true) }
            "revert" -> revertPreview.run()
            "restore" -> restore()
            "pause" -> {
                clearOverride(); prefs.backlightEnabled = false; generation.incrementAndGet()
                if (previewOriginal != null) revertPreview.run()
                else submit { backend.release(); status("Backlight changes paused; current level retained") }
                reconcile(false)
            }
            "phase" -> {
                val phase = Phase.values().firstOrNull { it.name == intent?.getStringExtra("phase") }
                if (phase != null && prefs.nativeQualified) {
                    prefs.backlightEnabled = true
                    overridePhase = phase
                    overrideUntil = prefs.policy().at(Instant.now(), ZoneId.systemDefault()).next.instant
                    prefs.store.edit().putString("override-phase", phase.name).putLong("override-until", overrideUntil!!.toEpochMilli()).apply()
                    reconcile(true)
                }
            }
            "resume" -> {
                clearOverride()
                if (prefs.nativeQualified) prefs.backlightEnabled = true
                reconcile(true)
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
    private fun submit(work: () -> Unit) {
        if (destroyed) return
        if (pendingNative.incrementAndGet() > 2) {
            pendingNative.decrementAndGet()
            status("Native operation already pending; wait for its result")
            return
        }
        worker.execute {
            try { work() } catch (error: Exception) {
                generation.incrementAndGet()
                prefs.store.edit().putBoolean("native-blocked", true).apply()
                status("Native control failed: ${error.message}. No automatic write retry.")
            } finally { pendingNative.decrementAndGet(); main.post { stopIfIdle() } }
        }
    }
    private fun clearOverride() {
        overridePhase = null; overrideUntil = null
        prefs.store.edit().remove("override-phase").remove("override-until").apply()
    }
    private fun stopIfIdle() {
        if (!destroyed && pendingNative.get() == 0 && !prefs.backlightEnabled && !prefs.warmthEnabled && overridePhase == null && previewOriginal == null && (overlay == null || overlay!!.profile.isOff)) stopSelf()
    }
    private fun reconcile(force: Boolean) {
        val policy = try { prefs.policy() } catch (e: IllegalArgumentException) { status("Invalid schedule: ${e.message}"); return }
        val now = Instant.now()
        val state = policy.at(now, ZoneId.systemDefault())
        if (overrideUntil?.let { now >= it } == true) { clearOverride() }
        scheduleNext(state.next.instant)
        updateWarmth(policy, now)
        sendBroadcast(Intent(STATUS).setPackage(packageName))
        if (!screenOn || previewOriginal != null) return
        if (!force && prefs.store.getBoolean("native-blocked", false)) return
        if (!prefs.backlightEnabled && overridePhase == null) return
        if (!prefs.nativeQualified) { status("Native qualification required for this firmware/Settings version; no backlight writes"); return }
        val setting = overridePhase?.let { p -> policy.settings.first { it.phase == p } } ?: state.current
        if (!force && appliedPhase == setting.phase) return
        appliedPhase = setting.phase
        val token = generation.incrementAndGet()
        submit {
            if (token != generation.get()) return@submit
            backend.claim()
            val current = backend.read()
            if (original == null || originalContext != backend.context() || current != (lastWritten ?: original)) {
                lastWritten = null
                original = current
                originalContext = backend.context()
                prefs.store.edit().putInt("original", current).putString("original-context", originalContext).remove("last-written").apply()
            }
            prefs.store.edit().putBoolean("native-blocked", false).apply()
            val duration = if (prefs.fadesQualified) setting.fadeSeconds * 1000L else 0L
            val fade = BrightnessFade(policy, setting, current, duration)
            val began = SystemClock.elapsedRealtime()
            val session = TransitionSession(fade, current,
                { SystemClock.elapsedRealtime() - began }, { token != generation.get() || !screenOn }) { next ->
                    backend.write(next)
                    lastWritten = next
                    prefs.store.edit().putInt("last-written", next).apply()
                }
            fun step() {
                try {
                    val running = session.tick()
                    if (token != generation.get()) return
                    status("${setting.phase.title}${if (overridePhase != null) " override" else ""}: native ${session.lastApplied} → ${setting.level}; ${if (running) "fading" else "applied"}" +
                        if (!prefs.fadesQualified && setting.fadeSeconds > 0) "; fade unavailable until repeated writes are qualified" else "")
                    if (running) worker.schedule({ step() }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (error: Exception) {
                    generation.incrementAndGet()
                    prefs.store.edit().putBoolean("native-blocked", true).apply()
                    status("Native write failed: ${error.message}. Outcome may be unknown; read before retrying.")
                }
            }
            step()
        }
    }
    private fun scheduleNext(nextPhase: Instant) {
        var next = nextPhase
        if (prefs.warmthEnabled && !prefs.linked) {
            val warmNext = DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(Instant.now(), ZoneId.systemDefault()).second
            if (warmNext < next) next = warmNext
        }
        val alarm = getSystemService(AlarmManager::class.java)
        val event = PendingIntent.getBroadcast(this, 71, Intent(this, TvReceiver::class.java).setAction(BOUNDARY), PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(event)
        if (prefs.backlightEnabled || prefs.warmthEnabled || overridePhase != null) {
            // RTC deliberately does not wake the device from standby.
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, next.toEpochMilli(), event)
        }
    }
    private fun warmthOnly(immediate: Boolean = false) {
        val now = Instant.now()
        val policy = prefs.policy()
        scheduleNext(policy.at(now, ZoneId.systemDefault()).next.instant)
        updateWarmth(policy, now, immediate)
        sendBroadcast(Intent(STATUS).setPackage(packageName))
    }
    private fun updateWarmth(policy: BrightnessPolicy, now: Instant, immediate: Boolean = false) {
        val state = policy.at(now, ZoneId.systemDefault())
        val customActive = DailyWindow(prefs.warmthStart, prefs.warmthEnd).at(now, ZoneId.systemDefault()).first
        val strength = when {
            !screenOn || !prefs.warmthEnabled -> 0
            prefs.linked -> prefs.warmth(state.current.phase)
            customActive -> prefs.store.getInt("custom-strength", 40)
            else -> 0
        }
        if (strength > 0 && !Settings.canDrawOverlays(this)) {
            prefs.warmthStatus = "Warmth unavailable: allow display over other apps. Backlight is independent."
            warmthAnimator?.cancel(); overlay?.onDestroy(); overlay = null
            return
        }
        val target = Profile(prefs.store.getInt("warmth-colour", 40), strength, 0, false)
        if (overlay == null && strength == 0) { prefs.warmthStatus = "Warmth off"; return }
        if (overlay == null) overlay = Overlay(this)
        if (overlay?.profile == target) return
        warmthAnimator?.cancel()
        val view = overlay!!
        warmthAnimator = ValueAnimator.ofObject(ProfileEvaluator(), view.profile, target).apply {
            duration = if (immediate) 0L else prefs.warmthFade * 1000L
            addUpdateListener {
                try { view.profile = it.animatedValue as Profile }
                catch (e: RuntimeException) { cancel(); prefs.warmthStatus = "Warmth failed: ${e.message}" }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { stopIfIdle() }
            })
            start()
        }
        prefs.warmthStatus = if (strength == 0) "Warmth off" else "Warmth $strength%; playback coverage unverified"
    }
    private val revertPreview = Runnable {
        if (!screenOn) return@Runnable // Retain pending restoration until wake.
        val saved = previewOriginal ?: return@Runnable
        val last = previewLast
        previewOriginal = null; previewLast = null
        submit {
            try {
                val current = backend.read()
                if (last != null && current == last && previewContext == backend.context()) {
                    backend.write(saved)
                    check(backend.read() == saved)
                    prefs.store.edit().putBoolean("probe-restored", true).putString("probe-signature", prefs.signature).apply()
                    status("Probe restored native backlight to $saved. Confirm the physical change before qualification.")
                } else status("Preview restore skipped: native value changed externally; check TV Settings")
            } finally { backend.release() }
        }
    }
    private fun probe() {
        if (!screenOn || previewOriginal != null) return
        if (prefs.backlightEnabled || overridePhase != null) { status("Pause backlight scheduling before a diagnostic probe"); return }
        generation.incrementAndGet()
        prefs.store.edit().putBoolean("probe-restored", false).apply()
        submit {
            backend.claim()
            val saved = backend.read()
            val target = if (saved > 1) saved - 1 else saved + 1
            previewOriginal = saved; previewLast = target; previewContext = backend.context()
            try {
                backend.write(target)
                check(backend.read() == target)
                status("Native probe $saved → $target. Reverting in 15 seconds; inspect the actual screen and native slider.")
            } finally {
                if (!destroyed) main.postDelayed(revertPreview, 15000)
            }
        }
    }
    private fun restore() {
        if (!screenOn) { status("Restoration deferred: wake the TV first"); return }
        if (previewOriginal != null) revertPreview.run()
        prefs.backlightEnabled = false
        clearOverride()
        generation.incrementAndGet()
        submit {
            backend.claim()
            try {
                val saved = original ?: prefs.store.getInt("original", -1)
                val last = lastWritten ?: prefs.store.getInt("last-written", -1)
                val current = backend.read()
                check(saved in 0..100 && last == current && originalContext == backend.context()) { "Restore unavailable: no owned unchanged native level. Use TV Settings." }
                backend.write(saved)
                check(backend.read() == saved)
                original = null; lastWritten = null
                prefs.store.edit().remove("original").remove("last-written").remove("original-context").apply()
                status("Backlight schedule paused; restored native level $saved")
            } finally { backend.release() }
        }
    }
    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        warmthAnimator?.cancel()
        overlay?.onDestroy()
        unregisterReceiver(screens)
        worker.shutdownNow()
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
