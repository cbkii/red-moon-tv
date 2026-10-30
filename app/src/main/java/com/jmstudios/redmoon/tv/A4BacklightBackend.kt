/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.*
import android.os.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** APK-UID client. JNI runs in a private, disposable app process, never in su/app_process. */
class A4BacklightBackend(context: Context) : BacklightBackend {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var messenger: Messenger? = null
    private var connection: ServiceConnection? = null
    private var workerPid = -1
    private var nativeContext: String? = null
    private var sequence = 0
    override fun context(): String? = nativeContext

    private fun connect() {
        if (messenger != null) return
        check(Looper.myLooper() != Looper.getMainLooper()) { "Native calls require the controller worker" }
        val ready = CountDownLatch(1)
        val link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { messenger = Messenger(binder); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { messenger = null }
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
        }
        connection = link
        main.post { if (!app.bindService(Intent(app, A4NativeService::class.java), link, Context.BIND_AUTO_CREATE)) ready.countDown() }
        if (!ready.await(5, TimeUnit.SECONDS) || messenger == null) { close(); error("Native worker unavailable (bind timeout)") }
    }

    @Synchronized private fun call(operation: String, value: Int? = null): Bundle {
        connect()
        if (workerPid < 0 && operation != "hello") call("hello")
        val done = CountDownLatch(1)
        var answer: Bundle? = null
        val id = ++sequence
        val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.arg1 == id) { answer = msg.data; done.countDown() }
            true
        })
        val request = Message.obtain(null, 1).apply {
            arg1 = id; replyTo = reply
            data = Bundle().apply { putString("operation", operation); if (value != null) putInt("value", value) }
        }
        try {
            messenger!!.send(request)
            if (!done.await(6, TimeUnit.SECONDS)) {
                // Interrupting Java cannot cancel JNI/Binder. Terminate only our identified worker;
                // a vendor-side setter may nevertheless have completed. The journal remains pending.
                if (workerPid > 0) Process.killProcess(workerPid)
                close()
                error("Native $operation timed out; outcome unknown. Reconcile readback before writing again")
            }
            val result = answer ?: error("Missing native response")
            workerPid = result.getInt("pid", -1)
            result.getString("diagnostics")?.let {
                app.getSharedPreferences("tv-display-v1", Context.MODE_PRIVATE).edit().putString("native-diagnostics", it).apply()
            }
            check(!result.containsKey("error")) { result.getString("error") ?: "Native failure" }
            return result
        } catch (error: RemoteException) {
            close()
            throw IllegalStateException("Native worker died; outcome unknown. Read before retrying", error)
        }
    }
    override fun read(): Int {
        val result = call("read")
        nativeContext = result.getString("context")
        check(!nativeContext.isNullOrBlank()) { "Missing native context" }
        check(result.getInt("minimum", -1) == 0 && result.getInt("maximum", -1) == 100) { "Unsupported native range" }
        val level = result.getInt("level", -1)
        check(level in 0..100) { "Invalid native readback" }
        app.getSharedPreferences("tv-display-v1", Context.MODE_PRIVATE).edit()
            .putString("native-diagnostics", result.getString("diagnostics", ""))
            .apply()
        return level
    }
    override fun claim() { call("claim") }
    override fun write(value: Int) { require(value in 0..100); call("write", value) }
    override fun release() { if (messenger != null) call("release") }
    override fun close() {
        val link = connection
        connection = null; messenger = null; workerPid = -1
        if (link != null) main.post { try { app.unbindService(link) } catch (_: IllegalArgumentException) {} }
    }
}
