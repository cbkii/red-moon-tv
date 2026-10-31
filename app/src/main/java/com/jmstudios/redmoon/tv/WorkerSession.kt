/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** Correlates one private binding/handshake; stale worker identities are never reused. */
class WorkerSession(val token: String) {
    private var worker: String? = null
    var alive = true
        private set
    val established: Boolean get() = alive && worker != null
    fun hello(uid: Int, pid: Int, ownerUid: Int, ownerPid: Int, identity: String) {
        check(alive && uid == ownerUid && pid > 0 && pid != ownerPid && identity.isNotBlank()) { "Unexpected native worker identity" }
        worker = identity
    }
    fun verify(connection: String?, identity: String?) {
        check(alive && connection == token && worker != null && identity == worker) { "Stale native connection/worker response" }
    }
    fun invalidate() { alive = false; worker = null }
}
