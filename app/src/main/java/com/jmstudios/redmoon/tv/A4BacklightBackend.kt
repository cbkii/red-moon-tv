/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

interface BacklightBackend {
    fun context(): String? = null
    fun read(): Int
    fun claim()
    fun write(value: Int)
    fun release()
}

/** Every call is bounded. No staging files or generic Android brightness fallback. */
class A4BacklightBackend(private val context: Context) : BacklightBackend {
    private var nativeContext: String? = null
    override fun context(): String? = nativeContext
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun execute(vararg args: String): Map<String, String> {
        val tvApk = context.packageManager.getApplicationInfo("com.android.tv.settings", 0).sourceDir
        val classpath = context.applicationInfo.sourceDir + ":" + tvApk
        val command = "CLASSPATH=${quote(classpath)} /system/bin/app_process /system/bin com.jmstudios.redmoon.tv.A4NativeBridge " + args.joinToString(" ") { quote(it) }
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val bytes = ByteArrayOutputStream()
        val reader = thread(isDaemon = true, name = "a4-native-output") {
            process.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(bytes) { if (bytes.size() < 32768) bytes.write(buffer, 0, minOf(count, 32768 - bytes.size())) }
                }
            }
        }
        try {
            check(process.waitFor(12, TimeUnit.SECONDS)) { "Native command timed out; outcome unknown. Read back before retrying." }
            reader.join(1000)
            val output = synchronized(bytes) { bytes.toString("UTF-8") }
            val fields = output.lineSequence().filter { it.startsWith("RM_") && '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
            check(process.exitValue() == 0 && "RM_ERROR" !in fields) { fields["RM_ERROR"] ?: "Native helper failed (${process.exitValue()}): ${output.takeLast(1200)}" }
            return fields
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }
    override fun read(): Int {
        val fields = execute("get")
        val value = fields["RM_BEFORE"]?.toIntOrNull()
        check(value != null && value in 0..100) { "No valid native readback" }
        check(fields["RM_MIN"]?.toIntOrNull() == 0 && fields["RM_MAX"]?.toIntOrNull() == 100) { "Unsupported native range: $fields" }
        nativeContext = fields["RM_CONTEXT"]
        check(!nativeContext.isNullOrBlank()) { "Missing native picture/boot context" }
        return value
    }
    override fun claim() { check(execute("claim", context.packageName)["RM_CLAIMED"] == "1") }
    override fun write(value: Int) {
        require(value in 0..100)
        val fields = execute("set", context.packageName, value.toString())
        check(fields["RM_RESULT"] == "0" && fields["RM_AFTER"]?.toIntOrNull() == value) { "Unverified native write" }
    }
    override fun release() { check(execute("release", context.packageName)["RM_RELEASED"] == "1") }
}
