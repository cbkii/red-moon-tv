/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

/** A failed JNI class initialiser must not be retried under another defining loader. */
class InitialisationOnce<T : Any> {
    private var value: T? = null
    var failure: Throwable? = null
        private set
    @Synchronized fun get(create: () -> T): T {
        failure?.let { throw it }
        value?.let { return it }
        try { return create().also { value = it } }
        catch (error: Throwable) { failure = error; throw error }
    }
}
