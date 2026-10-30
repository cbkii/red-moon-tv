/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.app.Service
import android.content.Intent
import android.net.LocalServerSocket
import android.os.*

/** Same UID, separate process: native aborts/hangs cannot take down policy, UI or warmth. */
class A4NativeService : Service() {
    private val thread = HandlerThread("a4-native")
    private var api: A4NativeBridge? = null
    private var lease: LocalServerSocket? = null
    private val baseDiagnostics: String by lazy {
        val source = try { packageManager.getApplicationInfo("com.android.tv.settings", 0).sourceDir }
            catch (_: Exception) { "binding package unavailable" }
        "${A4NativeBridge.PROTOCOL}; device=${Build.DEVICE}; uid=${Process.myUid()}; abi=${Build.SUPPORTED_ABIS.joinToString(",")}; bindings=$source; JNI=/vendor/lib/libcom_mediatek_twoworlds_tv_jni.so"
    }
    override fun onCreate() { super.onCreate(); thread.start() }
    private val endpoint by lazy {
        Messenger(Handler(thread.looper) { request ->
            val result = Bundle().apply {
                putInt("pid", Process.myPid())
                if (request.data.getString("operation") in listOf("hello", "read", "write")) putString("diagnostics", baseDiagnostics)
            }
            try {
                when (request.data.getString("operation")) {
                    "hello" -> Unit
                    "claim" -> if (lease == null) {
                        try { lease = LocalServerSocket("com.cbkii.redmoontv.panel-controller.v2") }
                        catch (e: java.io.IOException) { error("Ownership conflict: another controller holds the live panel lease") }
                    }
                    "release" -> { lease?.close(); lease = null }
                    "read", "write" -> {
                        val binding = api ?: A4NativeBridge(this).also { api = it }
                        if (request.data.getString("operation") == "write") {
                            check(lease != null) { "Claim live control before writing" }
                            binding.write(request.data.getInt("value", -1))
                        }
                        val value = binding.read()
                        result.putInt("level", value)
                        result.putInt("minimum", binding.minimum)
                        result.putInt("maximum", binding.maximum)
                        result.putString("context", binding.context())
                        result.putString("diagnostics", binding.diagnostics())
                    }
                    else -> error("Unknown native operation")
                }
            } catch (error: Throwable) {
                val cause = if (error is java.lang.reflect.InvocationTargetException) error.targetException else error
                result.putString("error", "${cause.javaClass.simpleName}: ${cause.message}")
            }
            try { request.replyTo?.send(Message.obtain(null, 1).apply { arg1 = request.arg1; data = result }) }
            catch (_: RemoteException) { /* Controller journal owns reconciliation after death. */ }
            true
        })
    }
    override fun onBind(intent: Intent?) = endpoint.binder
    override fun onDestroy() {
        // No persistent root daemon, shared executable or disk ownership marker.
        thread.quitSafely()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
