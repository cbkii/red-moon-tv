/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

interface BacklightBackend {
    fun context(): String? = null
    fun read(): Int
    fun claim()
    fun write(value: Int)
    fun release()
    fun close() {}
}

