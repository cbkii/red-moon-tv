/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import java.io.BufferedReader;
import java.io.FileReader;

/** A real boot identifier, never wall time or a process-local approximation. */
public final class BootIdentity {
    private BootIdentity() {}
    public static String validate(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
            ? value.toLowerCase(java.util.Locale.ROOT) : null;
    }
    public static String read() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            return validate(reader.readLine());
        } catch (Exception unavailable) { return null; }
    }
}
