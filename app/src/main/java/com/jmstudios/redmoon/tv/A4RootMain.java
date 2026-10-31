/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import android.net.LocalServerSocket;
import android.os.Process;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.concurrent.*;

/** One optional 32-bit worker, using the installed APK directly. EOF releases control. */
public final class A4RootMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || Process.myUid() != 0 || Process.is64Bit()) return;
        final String token = args[0], worker = UUID.randomUUID().toString();
        final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "a4-root-deadline"); t.setDaemon(true); return t;
        });
        watchdog.setRemoveOnCancelPolicy(true);
        A4NativeBridge bridge = null;
        String firstFailure = null;
        LocalServerSocket lease = null;
        DataInputStream input = new DataInputStream(System.in);
        DataOutputStream output = new DataOutputStream(System.out);
        int previousSequence = 0;
        try {
            while (true) {
                RootProtocol.Request request;
                try { request = RootProtocol.readRequest(input); } catch (EOFException closed) { break; }
                if (!token.equals(request.token) || request.sequence <= previousSequence) break;
                previousSequence = request.sequence;
                // A hung JNI call cannot leave an unbounded privileged process after client death.
                ScheduledFuture<?> deadline = watchdog.schedule(() -> {
                    android.util.Log.e("RedMoonTV", "Root native deadline; operation=" + request.operation + "; stage=" + A4NativeBridge.initialisationStage);
                    Process.killProcess(Process.myPid());
                }, 5, TimeUnit.SECONDS);
                RootProtocol.Reply reply = new RootProtocol.Reply();
                reply.sequence = request.sequence; reply.token = token; reply.worker = worker;
                reply.uid = Process.myUid(); reply.pid = Process.myPid();
                try {
                    if (request.operation == RootProtocol.CLAIM) {
                        if (lease == null) lease = new LocalServerSocket("com.cbkii.redmoontv.panel-controller.v2");
                    } else if (request.operation == RootProtocol.RELEASE) {
                        if (lease != null) lease.close(); lease = null;
                    } else if (request.operation != RootProtocol.HELLO) {
                        if (firstFailure != null) throw new IllegalStateException(firstFailure);
                        if (bridge == null) {
                            try {
                                String boot = BootIdentity.read();
                                bridge = new A4NativeBridge(args[1], boot == null ? null : "id=" + boot);
                            } catch (Throwable failure) {
                                firstFailure = describe(failure) + "; stage=" + A4NativeBridge.initialisationStage;
                                throw failure;
                            }
                        }
                        if (request.operation == RootProtocol.WRITE) {
                            if (lease == null) throw new IllegalStateException("Claim live control before writing");
                            bridge.context(); // Missing context is a write prohibition, never a read prohibition.
                            bridge.write(request.value);
                        }
                        reply.level = bridge.read();
                        try { reply.context = "root:" + bridge.context(); }
                        catch (Exception missing) { reply.diagnostics = "context unavailable: " + describe(missing) + "; "; }
                    }
                    reply.diagnostics += bridge == null ? "root-pipe-v1; JNI not initialised" : bridge.diagnostics();
                } catch (Throwable failure) {
                    reply.error = describe(failure);
                    reply.initialisationError = firstFailure;
                    reply.diagnostics = "root-pipe-v1; first failure=" + firstFailure + "; stage=" + A4NativeBridge.initialisationStage;
                }
                RootProtocol.writeReply(output, reply); output.flush(); deadline.cancel(false);
            }
        } finally {
            try { if (lease != null) lease.close(); }
            finally {
                watchdog.shutdownNow();
                // Also exit if socket cleanup fails; vendor threads must not outlive the client.
                Process.killProcess(Process.myPid());
            }
        }
    }
    private static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getTargetException() : error;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
