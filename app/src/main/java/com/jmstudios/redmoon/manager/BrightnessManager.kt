/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.manager

import android.content.Context

/** TV fork: legacy overlay lifecycle must never write generic Android brightness. */
class BrightnessManager(@Suppress("UNUSED_PARAMETER") context: Context) {
    var brightnessLowered: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) { /* Owned exclusively by the native TV controller. */ }
}
