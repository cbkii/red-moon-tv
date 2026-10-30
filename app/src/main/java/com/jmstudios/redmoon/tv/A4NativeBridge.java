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
import java.util.ArrayList;
import java.util.List;

/** Loads the installed vendor bindings in an APK native-library namespace; no vendor redistribution. */
public final class A4NativeBridge {
    public static final String PROTOCOL = "installed-jni-v4";
    private static Class<?> installedApi;
    private static String installedSource;
    public static volatile String initialisationStage = "not started";
    private final String bootIdentity;
    private final VendorBacklightApi nativeApi;
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
        stage("resolve installed TVNative signatures and range selectors");
        nativeApi = new VendorBacklightApi(api);
        minimum = nativeApi.minimum; maximum = nativeApi.maximum;
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
            api = Class.forName("com.mediatek.twoworlds.tv.TVNative", false, ClassLoader.getSystemClassLoader());
            source = "system/shared classpath";
        } catch (ClassNotFoundException absent) {
            // The directory is a search path, not a linker-namespace or permission bypass.
            ClassLoader loader = new PathClassLoader(dexPath, "/vendor/lib:/system/lib", ClassLoader.getSystemClassLoader());
            api = Class.forName("com.mediatek.twoworlds.tv.TVNative", false, loader);
            source = dexPath;
        }
        installedSource = source;
        installedApi = api;
        return api;
    }
    public int read() throws Exception {
        return nativeApi.read();
    }
    public void write(int target) throws Exception {
        if (target < 0 || target > 100) throw new IllegalArgumentException("Invalid native level");
        nativeApi.write(target);
    }
    public String context() throws Exception {
        if (!nativeApi.hasWriteRange()) throw new IllegalStateException("Native range unverified (" + nativeApi.rangeSource + "); read only");
        int picture = nativeApi.picture();
        if (picture < 0) throw new IllegalStateException("Picture context unavailable");
        if (bootIdentity == null) throw new IllegalStateException("Boot context unavailable; read allowed, writes blocked");
        return Build.FINGERPRINT + ":boot:" + bootIdentity + ":picture=" + picture;
    }
    public String diagnostics() {
        return PROTOCOL + "; uid=" + Process.myUid() + "; abi=" + android.text.TextUtils.join(",", Build.SUPPORTED_ABIS)
            + "; source=" + bindingSource + "; loader=" + installedApi.getClassLoader()
            + "; JNI=/vendor/lib/libcom_mediatek_twoworlds_tv_jni.so; range=" + minimum + ".." + maximum
            + "; rangeQuery=" + nativeApi.rangeSource + "; loadMs=" + loadedAt + "; context=boot/picture only; input/HDR identity unqualified";
    }
}
