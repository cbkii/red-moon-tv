/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** app_process entry point. Uses installed TV classes; no vendor code is redistributed. */
public final class A4NativeBridge {
    private static final String KEY = "g_disp__disp_back_light";
    private static final File DIRECTORY = new File("/sdcard/Download/RedMoonTV");
    private static final File OWNER = new File(DIRECTORY, "controller-owner");
    private static String bootId() throws Exception {
        return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()), StandardCharsets.US_ASCII).trim();
    }
    public static void main(String[] args) {
        try {
            if (args.length < 1) throw new IllegalArgumentException("Expected get, claim, release or set");
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class, String.class);
            String device = (String) get.invoke(null, "ro.product.device", "");
            if (!device.startsWith("xiaoyushan")) throw new IllegalStateException("Unsupported device: " + device);
            String inside = (String) get.invoke(null, "vendor.mtk.inside", "");
            if (inside.isEmpty() || inside.equals("0")) throw new IllegalStateException("Vendor configuration would use its emulator/no-op path");
            int uid = (Integer) Class.forName("android.os.Process").getMethod("myUid").invoke(null);
            if (uid != 0) throw new SecurityException("Root helper requires UID 0; got " + uid);
            Class<?> api = Class.forName("com.mediatek.twoworlds.tv.MtkTvConfig");
            Object config = api.getMethod("getInstance").invoke(null);
            Method read = api.getMethod("getConfigValue", String.class);
            int before = (Integer) read.invoke(config, KEY);
            if (before < 0 || before > 100) throw new IllegalStateException("Invalid native value: " + before);
            int picture = (Integer) read.invoke(config, "g_video__picture_mode");
            if (picture < 0) throw new IllegalStateException("Cannot read picture context");
            System.out.println("RM_CONTEXT=" + bootId() + ":" + picture);
            System.out.println("RM_BEFORE=" + before);
            if (args[0].equals("get")) {
                int packed = (Integer) api.getMethod("getMinMaxConfigValue", String.class).invoke(config, KEY);
                System.out.println("RM_MIN=" + api.getMethod("getMinValue", int.class).invoke(null, packed));
                System.out.println("RM_MAX=" + api.getMethod("getMaxValue", int.class).invoke(null, packed));
                return;
            }
            if (args.length < 2 || !args[1].matches("com\\.cbkii\\.redmoontv(?:\\.testing|\\.debug)?")) throw new SecurityException("Invalid controller identity");
            if (!DIRECTORY.isDirectory() && !DIRECTORY.mkdirs()) throw new IOException("Cannot create shared controller directory");
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(DIRECTORY, "controller.lock"), "rw");
                 FileLock lock = lockFile.getChannel().tryLock()) {
                if (lock == null) throw new IllegalStateException("Another native operation is in progress");
                String identity = bootId() + ":" + args[1];
                String owner = OWNER.exists() ? new String(Files.readAllBytes(OWNER.toPath()), StandardCharsets.US_ASCII).trim() : "";
                if (!owner.isEmpty() && owner.startsWith(bootId() + ":") && !owner.equals(identity))
                    throw new IllegalStateException("Another Red Moon TV package controls the panel: " + owner.substring(owner.indexOf(':') + 1));
                switch (args[0]) {
                    case "claim":
                        Files.write(OWNER.toPath(), identity.getBytes(StandardCharsets.US_ASCII));
                        System.out.println("RM_CLAIMED=1");
                        break;
                    case "release":
                        if (OWNER.exists() && !OWNER.delete()) throw new IOException("Cannot release controller");
                        System.out.println("RM_RELEASED=1");
                        break;
                    case "set":
                        if (!owner.equals(identity)) throw new SecurityException("Claim native control first");
                        if (args.length != 3 || !args[2].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid level");
                        int target = Integer.parseInt(args[2]);
                        if (target < 0 || target > 100) throw new IllegalArgumentException("Level outside native range");
                        // Use lower-level status, not TvConfig's unconditional zero-return wrapper.
                        int result = (Integer) api.getMethod("setConfigValue", String.class, int.class).invoke(config, KEY, target);
                        int after = (Integer) read.invoke(config, KEY);
                        System.out.println("RM_RESULT=" + result);
                        System.out.println("RM_AFTER=" + after);
                        if (result != 0 || after != target) throw new IllegalStateException("Native write/readback mismatch");
                        break;
                    default: throw new IllegalArgumentException("Unsupported operation");
                }
            }
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getTargetException() : error;
            System.err.println("RM_ERROR=" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            System.exit(1);
        }
    }
}
