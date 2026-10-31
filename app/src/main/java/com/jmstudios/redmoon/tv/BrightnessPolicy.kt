/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import java.time.*

enum class Phase(val title: String, val minimum: Int, val maximum: Int) {
    DAY("Day", 70, 100), DUSK("Dusk", 10, 80), DIM("Dim", 1, 20)
}

data class PhaseSetting(val phase: Phase, val level: Int, val startMinute: Int, val fadeSeconds: Int)
data class Boundary(val instant: Instant, val setting: PhaseSetting)
data class ScheduleState(val current: PhaseSetting, val next: Boundary)

/** The only authority for scheduled values. Preferences also define one-sided bounds. */
class BrightnessPolicy(val settings: List<PhaseSetting>) {
    init {
        require(settings.map { it.phase } == Phase.values().toList()) { "Expected Day, Dusk and Dim" }
        settings.forEach {
            require(it.level in it.phase.minimum..it.phase.maximum) { "${it.phase.title}: level must be ${it.phase.minimum}–${it.phase.maximum}" }
            require(it.startMinute in 0..1439) { "Time must be 00:00–23:59" }
            require(it.fadeSeconds in 0..888) { "Fade must be 0–888 seconds" }
        }
        require(settings[0].level >= settings[1].level && settings[1].level >= settings[2].level) { "Levels must follow Day ≥ Dusk ≥ Dim" }
        val windows = settings.indices.map { i -> (settings[(i + 1) % 3].startMinute - settings[i].startMinute + 1440) % 1440 }
        require(windows.all { it > 0 } && windows.sum() == 1440) { "Use distinct times in Day → Dusk → Dim order" }
        settings.indices.forEach { i -> require(settings[i].fadeSeconds <= windows[i] * 60) { "${settings[i].phase.title}: fade exceeds its window" } }
    }
    fun band(setting: PhaseSetting): IntRange = if (setting.phase == Phase.DAY) 70..setting.level else setting.level..setting.phase.maximum
    fun constrain(setting: PhaseSetting, value: Int): Int = value.coerceIn(band(setting))
    fun at(now: Instant, zone: ZoneId): ScheduleState {
        val date = now.atZone(zone).toLocalDate()
        val events = (-2L..2L).flatMap { offset -> settings.map { setting ->
            Boundary(resolveLocal(date.plusDays(offset), setting.startMinute, zone), setting)
        }}.sortedWith(compareBy<Boundary> { it.instant }.thenBy { it.setting.startMinute })
        return ScheduleState(events.last { it.instant <= now }.setting, events.first { it.instant > now })
    }
    companion object {
        fun defaults() = BrightnessPolicy(listOf(
            PhaseSetting(Phase.DAY, 80, 7 * 60, 0),
            PhaseSetting(Phase.DUSK, 40, 18 * 60 + 30, 0),
            PhaseSetting(Phase.DIM, 5, 22 * 60, 0)))
    }
}

/** Quantised, strict incoming-band fade. Native calls are never animation-frame driven. */
class BrightnessFade(policy: BrightnessPolicy, val setting: PhaseSetting, current: Int, requestedDurationMillis: Long, strictBand: Boolean = true) {
    private val start = if (strictBand) policy.constrain(setting, current) else current
    private val allowed = if (strictBand) policy.band(setting) else minOf(current, setting.level)..maxOf(current, setting.level)
    init { require(current in 0..100 && requestedDurationMillis in 0..888000) }
    val durationMillis = if (start == setting.level) 0L else requestedDurationMillis
    /** Next changed integer, capped at two writes/second; no duplicate 500 ms polling. */
    fun nextDelay(elapsedMillis: Long): Long {
        return QuantisedFade.nextDelay(start, setting.level, durationMillis, elapsedMillis)
    }
    fun valueAt(elapsedMillis: Long): Int =
        QuantisedFade.value(start, setting.level, durationMillis, elapsedMillis).coerceIn(allowed)
}

/** Independent on/off window with the same skipped/repeated local-time rules. */
class DailyWindow(private val start: Int, private val end: Int) {
    init { require(start in 0..1439 && end in 0..1439 && start != end) }
    fun at(now: Instant, zone: ZoneId): Pair<Boolean, Instant> {
        val date = now.atZone(zone).toLocalDate()
        val events = (-2L..2L).flatMap { offset -> listOf(start to true, end to false).map { (minute, active) ->
            Triple(resolveLocal(date.plusDays(offset), minute, zone), minute, active)
        }}.sortedWith(compareBy<Triple<Instant, Int, Boolean>> { it.first }.thenBy { it.second })
        return events.last { it.first <= now }.third to events.first { it.first > now }.first
    }
}

internal fun resolveLocal(date: LocalDate, minute: Int, zone: ZoneId): Instant {
    val local = date.atStartOfDay().plusMinutes(minute.toLong())
    val offsets = zone.rules.getValidOffsets(local)
    return if (offsets.isEmpty()) zone.rules.getTransition(local).dateTimeAfter.atZone(zone).toInstant()
        else local.toInstant(offsets.first())
}
