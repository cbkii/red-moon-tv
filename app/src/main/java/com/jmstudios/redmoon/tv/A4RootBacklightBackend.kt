/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.io.*
import java.util.UUID
import java.util.concurrent.*

/** Optional privileged transport. One process per session; no shell API or setter retry. */
class A4RootBacklightBackend(context: Context) : BacklightBackend {
    private val app = context.applicationContext
    private var process: java.lang.Process? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var reader: ExecutorService? = null
    private var identity: String? = null
    private var contextIdentity: String? = null
    private var sequence = 0
    private var token = ""
    private var broken = false
    private val stderr = StringBuilder()
    private fun connect() {
        check(!broken) { "Root worker failed; explicitly restart before another attempt" }
        if (process != null) return
        check(TvPreferences(app).rootBackend) { "Root backend has not been selected" }
        check(android.os.Build.DEVICE.startsWith("xiaoyushan") && !android.os.Process.is64Bit()) { "Root bridge requires the 32-bit A4" }
        token = UUID.randomUUID().toString(); sequence = 0
        val apk = app.applicationInfo.sourceDir
        val bindings = A4NativeBridge.installedPaths(app)
        // Paths come from PackageManager. Quoting still handles whitespace and shell metacharacters.
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val command = "CLASSPATH=${quote(apk)} exec /system/bin/app_process32 /system/bin " +
            "--nice-name=${quote(app.packageName + ":a4-root")} com.jmstudios.redmoon.tv.A4RootMain ${quote(token)} ${quote(bindings)}"
        val child = ProcessBuilder("su", "-c", command).start()
        process = child
        input = DataInputStream(BufferedInputStream(child.inputStream))
        output = DataOutputStream(BufferedOutputStream(child.outputStream))
        reader = Executors.newSingleThreadExecutor { r -> Thread(r, "a4-root-reply").apply { isDaemon = true } }
        synchronized(stderr) { stderr.setLength(0) }
        kotlin.concurrent.thread(name = "a4-root-stderr", isDaemon = true) {
            try { child.errorStream.bufferedReader().use { stream ->
                val buffer = CharArray(512)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    synchronized(stderr) {
                        stderr.append(buffer, 0, count)
                        if (stderr.length > 4096) stderr.delete(0, stderr.length - 4096)
                    }
                }
            } } catch (_: IOException) {}
        }
    }
    @Synchronized private fun call(operation: Int, value: Int = -1): RootProtocol.Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Root calls require the controller worker" }
        val began = SystemClock.elapsedRealtime()
        var failed = true
        try {
            connect()
            if (operation != RootProtocol.HELLO && identity == null) call(RootProtocol.HELLO)
            val request = RootProtocol.Request(++sequence, token, operation, value)
            val source = input ?: error("Root pipe disconnected")
            val sink = output ?: error("Root pipe disconnected")
            val future = reader!!.submit<RootProtocol.Reply> {
                RootProtocol.writeRequest(sink, request); sink.flush()
                RootProtocol.readReply(source, request)
            }
            val response = try { future.get(if (operation == RootProtocol.HELLO) 20 else 7, TimeUnit.SECONDS) }
                catch (error: Exception) {
                    future.cancel(true); broken = true
                    val detail = synchronized(stderr) { stderr.toString().takeLast(2048) }
                    closeTransport()
                    throw IllegalStateException("Root transport failed/timed out; outcome unknown. No retry. $detail", error)
                }
            val worker = "${response.pid}:${response.worker}"
            check(identity == null || identity == worker) { "Root worker changed; reconcile before retrying" }
            identity = worker
            val edit = app.getSharedPreferences("tv-display-v1", Context.MODE_PRIVATE).edit()
            edit.putString("native-diagnostics", "root-pipe-v1; worker=$worker; ${response.diagnostics}")
            if (operation == RootProtocol.HELLO) edit.remove("first-initialisation-error")
            if (response.initialisationError.isNotEmpty()) edit.putString("first-initialisation-error", response.initialisationError)
            if (response.error.isNotEmpty()) edit.putString("root-last-error", response.error)
            edit.apply()
            check(response.error.isEmpty()) { response.error }
            failed = false
            return response
        } finally {
            NativeCallStats.record("root", when (operation) {
                RootProtocol.WRITE -> "write"; RootProtocol.READ -> "read"; else -> "ipc-$operation"
            }, SystemClock.elapsedRealtime() - began, failed)
        }
    }
    override fun context(): String? = contextIdentity
    override fun read(): Int {
        val result = call(RootProtocol.READ)
        contextIdentity = result.context.takeIf { it.isNotBlank() }
        check(result.level in 0..100) { "Invalid root native level" }
        return result.level
    }
    override fun claim() { call(RootProtocol.CLAIM) }
    override fun write(value: Int) { require(value in 0..100); call(RootProtocol.WRITE, value) }
    override fun release() { if (process != null && !broken) call(RootProtocol.RELEASE) }
    private fun closeTransport() {
        // Close stdin first: the root worker owns its own exit/lease cleanup; no numeric PID kill.
        try { output?.close() } catch (_: IOException) {}
        output = null
        process?.destroy(); process = null
        reader?.shutdownNow(); reader = null
        input = null; identity = null; contextIdentity = null
    }
    @Synchronized override fun close() { closeTransport(); broken = false }
}
