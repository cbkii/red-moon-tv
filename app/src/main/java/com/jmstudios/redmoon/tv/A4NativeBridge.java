/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Loads the installed vendor bindings in an APK native-library namespace; no vendor redistribution. */
public final class A4NativeBridge {
    public static final String PROTOCOL = "installed-jni-v3";
    private static final String KEY = "g_disp__disp_back_light";
    private static Class<?> installedApi;
    private static String installedSource;
    public static volatile String initialisationStage = "not started";
    private final String bootIdentity;
    private final Object config;
    private final Method getter, setter;
    private final String bindingSource;
    private final long loadedAt;
    public final int minimum, maximum;

    public A4NativeBridge(Context context) throws Exception {
        this(installedPaths(context), bootIdentity(context));
    }
    /** Root entry point receives only paths resolved by our package manager, not shell commands. */
    public A4NativeBridge(String dexPath, String boot) throws Exception {
        bootIdentity = boot;
        if (!Build.DEVICE.startsWith("xiaoyushan")) throw new IllegalStateException("Unsupported device: " + Build.DEVICE);
        if (Process.is64Bit()) throw new IllegalStateException("A4 vendor JNI requires a 32-bit process");
        File library = new File("/vendor/lib/libcom_mediatek_twoworlds_tv_jni.so");
        if (!library.canRead()) throw new IllegalStateException("Installed vendor JNI is unavailable");
        long began = SystemClock.elapsedRealtime();
        Class<?> api = resolveApi(dexPath);
        bindingSource = installedSource;
        stage("getInstance");
        config = api.getMethod("getInstance").invoke(null);
        stage("resolve getter/setter signatures");
        getter = api.getMethod("getConfigValue", String.class);
        setter = api.getMethod("setConfigValue", String.class, int.class);
        stage("native getMinMaxConfigValue");
        int packed = (Integer) api.getMethod("getMinMaxConfigValue", String.class).invoke(config, KEY);
        stage("unpack native range");
        minimum = (Integer) api.getMethod("getMinValue", int.class).invoke(null, packed);
        maximum = (Integer) api.getMethod("getMaxValue", int.class).invoke(null, packed);
        if (minimum != 0 || maximum != 100) throw new IllegalStateException("Unexpected vendor range " + minimum + ".." + maximum);
        loadedAt = SystemClock.elapsedRealtime() - began;
        stage("ready");
    }
    public static String installedPaths(Context context) throws Exception {
        ApplicationInfo installed = context.getPackageManager().getApplicationInfo("com.android.tv.settings", android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES);
        List<String> paths = new ArrayList<>();
        if (installed.sharedLibraryFiles != null) for (String path : installed.sharedLibraryFiles) paths.add(path);
        paths.add(installed.sourceDir);
        if (installed.splitSourceDirs != null) for (String path : installed.splitSourceDirs) paths.add(path);
        return android.text.TextUtils.join(File.pathSeparator, paths);
    }
    private static String bootIdentity(Context context) {
        String id = BootIdentity.read();
        if (id != null) return "id=" + id;
        int count = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
        return count < 0 ? null : "count=" + count;
    }
    private static void stage(String value) {
        initialisationStage = value;
        android.util.Log.i("RedMoonTV", "Native initialisation stage: " + value);
    }
    private static synchronized Class<?> resolveApi(String dexPath) throws Exception {
        if (installedApi != null) return installedApi;
        stage("resolve installed bindings");
        Class<?> api;
        String source;
        try {
            api = Class.forName("com.mediatek.twoworlds.tv.MtkTvConfig", false, ClassLoader.getSystemClassLoader());
            source = "system/shared classpath";
        } catch (ClassNotFoundException absent) {
            // The directory is a search path, not a linker-namespace or permission bypass.
            ClassLoader loader = new PathClassLoader(dexPath, "/vendor/lib:/system/lib", ClassLoader.getSystemClassLoader());
            api = Class.forName("com.mediatek.twoworlds.tv.MtkTvConfig", false, loader);
            source = dexPath;
        }
        installedSource = source;
        installedApi = api;
        return api;
    }
    public int read() throws Exception {
        int value = (Integer) getter.invoke(config, KEY);
        if (value < minimum || value > maximum) throw new IllegalStateException("Native getter status/value: " + value);
        return value;
    }
    public void write(int target) throws Exception {
        if (target < 0 || target > 100) throw new IllegalArgumentException("Invalid native level");
        int result = (Integer) setter.invoke(config, KEY, target);
        if (result != 0) throw new IllegalStateException("Native setter status " + result + "; outcome requires readback");
        if (read() != target) throw new IllegalStateException("Native readback mismatch; no automatic retry");
    }
    public String context() throws Exception {
        int picture = (Integer) getter.invoke(config, "g_video__picture_mode");
        if (picture < 0) throw new IllegalStateException("Picture context unavailable");
        if (bootIdentity == null) throw new IllegalStateException("Boot context unavailable; read allowed, writes blocked");
        return Build.FINGERPRINT + ":boot:" + bootIdentity + ":picture=" + picture;
    }
    public String diagnostics() {
        return PROTOCOL + "; uid=" + Process.myUid() + "; abi=" + android.text.TextUtils.join(",", Build.SUPPORTED_ABIS)
            + "; source=" + bindingSource + "; loader=" + config.getClass().getClassLoader()
            + "; JNI=/vendor/lib/libcom_mediatek_twoworlds_tv_jni.so; range=" + minimum + ".." + maximum
            + "; loadMs=" + loadedAt + "; context=boot/picture only; input/HDR identity unqualified";
    }
}
