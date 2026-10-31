/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VendorBacklightApiTest {
    public static class InstalledNative {
        static int level, maximum, packed, setterStatus, writes, update;
        static List<Integer> selectors;
        protected static String getCurrentInputSourceName_native() { return "HDMI1"; }
        protected static int getConfigValue_native(int input, String key) {
            assertEquals(-1, input);
            return key.equals("g_disp__disp_back_light") ? level : 2;
        }
        protected static int getMinMaxConfigValue_native(int selector, String key) {
            assertEquals("g_disp__disp_back_light", key); selectors.add(selector);
            return selector == -1 ? packed : selector == 0 ? 0 : maximum;
        }
        protected static int setConfigValue_native(int input, String key, int value, int flag) {
            assertEquals(-1, input); assertEquals("g_disp__disp_back_light", key);
            writes++; update = flag;
            if (setterStatus == 0) level = value;
            return setterStatus;
        }
    }
    @Before public void reset() {
        InstalledNative.level = 66; InstalledNative.maximum = 100; InstalledNative.packed = 0;
        InstalledNative.setterStatus = InstalledNative.writes = InstalledNative.update = 0;
        InstalledNative.selectors = new ArrayList<>();
    }
    @Test public void zeroPackedRangeUsesNativeSelectorsInsteadOfAborting() throws Exception {
        VendorBacklightApi api = new VendorBacklightApi(InstalledNative.class);
        assertEquals(java.util.Arrays.asList(-1, 0, 1), InstalledNative.selectors);
        assertTrue(api.hasWriteRange()); assertEquals(66, api.read()); assertEquals(0, InstalledNative.writes);
    }
    @Test public void missingRangeKeepsReadAvailableButProhibitsEverySetter() throws Exception {
        InstalledNative.maximum = 0;
        VendorBacklightApi api = new VendorBacklightApi(InstalledNative.class);
        assertEquals(66, api.read()); assertFalse(api.hasWriteRange());
        try { api.write(61); fail("must block"); } catch (IllegalStateException expected) { }
        assertEquals(0, InstalledNative.writes);
    }
    @Test public void packedRangeIsAcceptedWithoutChangingQueryMode() throws Exception {
        InstalledNative.packed = 100 << 16;
        VendorBacklightApi api = new VendorBacklightApi(InstalledNative.class);
        assertTrue(api.hasWriteRange()); assertEquals(java.util.Arrays.asList(-1), InstalledNative.selectors);
    }
    @Test public void setterMatchesTheActualA4WrapperAndVerifiesReadback() throws Exception {
        VendorBacklightApi api = new VendorBacklightApi(InstalledNative.class);
        api.write(61);
        assertEquals(3, InstalledNative.update); assertEquals(1, InstalledNative.writes); assertEquals(61, api.read());
    }
    @Test public void setterFailureIsNotRetried() throws Exception {
        InstalledNative.setterStatus = -1;
        VendorBacklightApi api = new VendorBacklightApi(InstalledNative.class);
        try { api.write(61); fail("must fail"); } catch (IllegalStateException expected) { }
        assertEquals(1, InstalledNative.writes); assertEquals(66, InstalledNative.level);
    }
    @Test public void inputAndHdrUseReadOnlyInstalledSignatures() throws Exception {
        VendorBacklightApi api = new VendorBacklightApi(InstalledNative.class);
        assertEquals("HDMI1", api.source()); assertEquals(2, api.hdr()); assertEquals(0, InstalledNative.writes);
    }
    @Test public void getterErrorIsNotTreatedAsBrightness() throws Exception {
        InstalledNative.level = -1;
        VendorBacklightApi api = new VendorBacklightApi(InstalledNative.class);
        try { api.read(); fail("must fail"); } catch (IllegalStateException expected) { }
    }
}
