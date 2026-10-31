/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Persisted before a setter; pending is deliberately separate from confirmed readback. */
data class RecoveryRecord(val original: Int, val confirmed: Int, val pending: Int?, val context: String,
                          val kind: String, val session: String)
interface RecoveryStore {
    fun load(): RecoveryRecord?
    fun save(record: RecoveryRecord)
    fun clear()
}
class BacklightContextChange(message: String) : IllegalStateException(message)
class ExternalBacklightChange(message: String) : IllegalStateException(message)

/** Single serial caller. Cancellation never asserts that an in-flight native call was cancelled. */
class BacklightController(private val backend: BacklightBackend, private val journal: RecoveryStore,
                          private val session: String) {
    private var expected: Int? = null
    private var expectedContext: String? = null
    private var claimed = false
    private var active: RecoveryRecord? = null
    fun pendingRecovery() = journal.load()
    fun read() = backend.read().also { NativeTrace.record("read", it, backend.context()) }
    /** Confirmed schedule journals may survive a process death; pending/probe records never auto-resume. */
    fun adoptSchedule(): Boolean {
        val prior = journal.load() ?: return false
        check(prior.kind == "schedule" && prior.pending == null) { "Explicit recovery required" }
        val current = backend.read()
        if (backend.context() != prior.context) throw BacklightContextChange("Input/picture/HDR/boot context changed")
        if (current != prior.confirmed) throw ExternalBacklightChange("Native brightness adjusted while controller was absent")
        journal.save(prior.copy(session = session)); return true
    }
    /** Reads only while active, so a steady phase can yield before its next scheduled write. */
    fun observe() {
        check(claimed)
        val current = backend.read()
        if (backend.context() != expectedContext) throw BacklightContextChange("Input/picture/HDR context changed")
        NativeTrace.record("observe", current, backend.context())
        if (current != expected) throw ExternalBacklightChange("Native brightness adjusted; retained to next phase")
    }
    fun hasClaim() = claimed
    fun begin(kind: String): Int {
        check(!claimed) { "A native session is already active" }
        backend.claim(); claimed = true
        try {
            val current = backend.read()
            val context = backend.context()?.takeIf { it.isNotBlank() } ?: error("No native context; reads are available, writes blocked")
            val prior = journal.load()
            // Never erase a pending/previous-process recovery transaction on a resume request.
            check(prior == null || (prior.pending == null && prior.session == session && prior.kind == kind)) {
                "Recovery is pending; explicitly reconcile or discard it before scheduling"
            }
            if (prior != null && context != prior.context) throw BacklightContextChange("Input/picture/HDR context changed")
            if (prior != null && current != prior.confirmed) {
                throw ExternalBacklightChange("Native value/context changed; schedule yields until the next boundary or Resume")
            }
            expected = current; expectedContext = context
            active = prior ?: RecoveryRecord(current, current, null, context, kind, session)
            return current
        } catch (error: Exception) { release(); throw error }
    }
    fun write(target: Int, allowed: () -> Boolean = { true }) {
        require(target in 0..100)
        check(claimed)
        val record = active ?: journal.load() ?: error("No recovery transaction")
        check(record.pending == null) { "Uncertain previous write requires reconciliation" }
        val current = backend.read()
        if (backend.context() != expectedContext) throw BacklightContextChange("Input/picture/HDR context changed")
        if (current != expected) throw ExternalBacklightChange("Native brightness adjusted; retained to next phase")
        if (!allowed()) throw java.util.concurrent.CancellationException("Transition retired before setter")
        if (current == target) return
        active = record.copy(pending = target)
        journal.save(active!!)
        backend.write(target)
        val actual = backend.read()
        check(actual == target && backend.context() == expectedContext) { "Write/context readback mismatch; recovery pending" }
        active = record.copy(confirmed = actual, pending = null)
        journal.save(active!!)
        expected = actual
        NativeTrace.record("write", actual, expectedContext)
    }
    /** Explicit user recovery or same-session timer only; never automatic across a process restart. */
    fun restore(): Boolean {
        val record = journal.load() ?: return false
        if (!claimed) { backend.claim(); claimed = true }
        try {
            val current = backend.read()
            check(backend.context() == record.context) { "Context changed; use native TV Settings to restore" }
            val owned = record.pending ?: record.confirmed
            if (current != owned && !(record.pending != null && current == record.confirmed)) {
                throw ExternalBacklightChange("Restoration skipped: external native adjustment")
            }
            journal.save(record.copy(confirmed = current, pending = record.original))
            backend.write(record.original)
            check(backend.read() == record.original && backend.context() == record.context) { "Restore readback failed" }
            NativeTrace.record("restore", record.original, record.context)
            journal.clear(); active = null; expected = null
            return true
        } finally { release() }
    }
    /** Called only on explicit Keep / next-boundary after a known external adjustment. */
    fun discard() { check(!claimed); journal.clear(); active = null; expected = null }
    fun release() {
        if (claimed) {
            // Do not clear our local ownership state until the release has completed.
            backend.release(); claimed = false
        }
    }
    fun close() { backend.close(); claimed = false }
}
