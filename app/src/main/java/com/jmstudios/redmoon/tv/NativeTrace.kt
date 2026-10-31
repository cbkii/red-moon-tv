/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Bounded process-local evidence. No logcat/preferences/flash writes per sample. */
object NativeTrace {
    data class Event(val at: Long, val operation: String, val level: Int, val context: String?)
    private val events = java.util.ArrayDeque<Event>()
    @Synchronized fun record(operation: String, level: Int, context: String?) {
        if (events.size == 1024) events.removeFirst()
        events.addLast(Event(System.currentTimeMillis(), operation, level, context))
    }
    @Synchronized fun snapshot(): List<Event> = events.toList()
}
