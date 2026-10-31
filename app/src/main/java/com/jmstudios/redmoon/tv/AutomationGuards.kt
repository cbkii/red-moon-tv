/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import java.security.MessageDigest
import java.util.BitSet
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A shortcut grants its chosen operation/arguments, never the shared broadcast credential. */
object ShortcutCapability {
    fun sign(master: String, request: AutomationRequest): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "shortcut:" + mac.doFinal(request.copy(id = "").toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Fixed 32 KiB tombstone set. False positives reject work; an old ID never becomes new work. */
class AutomationReplayGuard(encoded: String? = null) {
    private val bits = if (encoded == null) BitSet() else BitSet.valueOf(Base64.getDecoder().decode(encoded))
    private fun positions(id: String): List<Int> {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return (0..3).map { i -> ((hash[i * 4].toInt() and 255) shl 16 or
            ((hash[i * 4 + 1].toInt() and 255) shl 8) or (hash[i * 4 + 2].toInt() and 255)) and 262143 }
    }
    fun contains(id: String) = positions(id).all { bits[it] }
    fun add(id: String) { positions(id).forEach { bits.set(it) } }
    fun encode(): String = Base64.getEncoder().encodeToString(bits.toByteArray())
}

/** Main-thread ordering across deferred reads/identity callbacks, independently for each scope. */
class AutomationOrder {
    data class Ticket(val parts: Map<String, Long>)
    private val versions = mutableMapOf("brightness" to 0L, "warmth" to 0L)
    fun invalidate(scope: String) {
        versions.keys.filter { scope == "both" || it == scope }.forEach { versions[it] = versions.getValue(it) + 1 }
    }
    fun snapshot(scope: String) = Ticket(versions.filterKeys { scope == "both" || it == scope })
    fun admit(scope: String): Ticket { invalidate(scope); return snapshot(scope) }
    fun current(ticket: Ticket) = ticket.parts.all { (part, version) -> versions[part] == version }
}

fun temporaryActive(expires: Long, now: Long) = expires == 0L || expires > now
fun retireTemporary(state: String) = state in setOf("failed", "cancelled", "rejected")
fun automationFailureState(failedGeneration: Int?, requestGeneration: Int?) =
    if (failedGeneration != null && requestGeneration != null && failedGeneration != requestGeneration) "rejected" else "failed"
fun executionInterrupted(previousProcess: String, process: String, previousSession: String, session: String?) =
    previousProcess != process || (previousSession.isNotEmpty() && previousSession != session)
