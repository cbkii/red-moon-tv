/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import java.lang.reflect.Method;

/** The exact signatures in this A4's installed TVNative, restricted to backlight/picture. */
public final class VendorBacklightApi {
    private static final String BACKLIGHT = "g_disp__disp_back_light";
    private final Method getter, setter, source;
    public final int minimum, maximum;
    public final String rangeSource;

    public VendorBacklightApi(Class<?> nativeApi) throws Exception {
        getter = nativeApi.getDeclaredMethod("getConfigValue_native", int.class, String.class);
        setter = nativeApi.getDeclaredMethod("setConfigValue_native", int.class, String.class, int.class, int.class);
        Method range = nativeApi.getDeclaredMethod("getMinMaxConfigValue_native", int.class, String.class);
        getter.setAccessible(true); setter.setAccessible(true); range.setAccessible(true);
        Method input;
        try { input = nativeApi.getDeclaredMethod("getCurrentInputSourceName_native"); input.setAccessible(true); }
        catch (NoSuchMethodException unavailable) { input = null; }
        source = input;
        int low = -1, high = -1;
        String source;
        try {
            int packed = (Integer) range.invoke(null, -1, BACKLIGHT);
            low = (short) (packed & 0xffff); high = (short) (packed >>> 16);
            source = "packed=" + packed;
            // The A4 report returned zero for the legacy packed query. The installed
            // native signature also supports separate minimum/maximum selectors.
            // Read these; never replace missing range metadata with invented bounds.
            if (packed <= 0) {
                low = (Integer) range.invoke(null, 0, BACKLIGHT);
                high = (Integer) range.invoke(null, 1, BACKLIGHT);
                source += "; selectors=" + low + ".." + high;
            }
        } catch (ReflectiveOperationException unavailable) {
            source = "unavailable: " + unavailable;
        }
        minimum = low; maximum = high; rangeSource = source;
    }
    public boolean hasWriteRange() { return minimum == 0 && maximum == 100; }
    public int read() throws Exception {
        int value = (Integer) getter.invoke(null, -1, BACKLIGHT);
        if (value < 0 || value > 100) throw new IllegalStateException("Native getter status/value: " + value);
        return value;
    }
    public int picture() throws Exception {
        return (Integer) getter.invoke(null, -1, "g_video__picture_mode");
    }
    /** Read-only signatures/keys verified in the supplied TvSettingsPlus DEX. */
    public String source() throws Exception {
        if (source == null) throw new IllegalStateException("Input source query unavailable; read only");
        Object result = source.invoke(null);
        if (!(result instanceof String) || ((String) result).trim().isEmpty() || ((String) result).length() > 256)
            throw new IllegalStateException("Input source identity unavailable; read only");
        return (String) result;
    }
    public int hdr() throws Exception {
        int value = (Integer) getter.invoke(null, -1, "g_consumer_disp__hdr_flag");
        if (value < 0) throw new IllegalStateException("HDR context unavailable; read only");
        return value; // Raw vendor enum; do not guess HDR format from its number.
    }
    public void write(int value) throws Exception {
        if (!hasWriteRange()) throw new IllegalStateException("Native range unavailable: " + rangeSource + "; read only");
        if (value < 0 || value > 100) throw new IllegalArgumentException("Invalid native level");
        // The supplied A4 MtkTvConfigBase.setConfigValue(String,int) delegates
        // to this exact native signature with input=-1 and update=3.
        int status = (Integer) setter.invoke(null, -1, BACKLIGHT, value, 3);
        if (status != 0) throw new IllegalStateException("Native setter status " + status + "; outcome requires readback");
        if (read() != value) throw new IllegalStateException("Native readback mismatch; no automatic retry");
    }
}
