/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.content.*
import android.os.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Private APK IPC. Every connection has a fresh identity; no client-side numeric PID kill. */
class A4BacklightBackend(context: Context) : BacklightBackend {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    @Volatile private var active: Binding? = null
    private var nativeContext: String? = null
    private var sequence = 0
    private class Binding {
        val session = WorkerSession(UUID.randomUUID().toString())
        val ready = CountDownLatch(1)
        lateinit var link: ServiceConnection
        @Volatile var valid = true
        @Volatile var remote: Messenger? = null
        @Volatile var pending: CountDownLatch? = null
        var bound = false // Main thread only.
        var unbindQueued = false // Protected by stateLock.
    }
    override fun context(): String? = nativeContext
    private fun current(binding: Binding) = binding.valid && active === binding
    private fun retire(binding: Binding) {
        synchronized(stateLock) {
            binding.valid = false; binding.remote = null; binding.session.invalidate()
            binding.pending?.countDown(); binding.ready.countDown()
            if (active === binding) active = null
            if (binding.unbindQueued) return
            binding.unbindQueued = true
        }
        main.post {
            if (binding.bound) {
                binding.bound = false
                try { app.unbindService(binding.link) } catch (_: IllegalArgumentException) {}
            }
        }
    }
    private fun connect(): Binding {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Native calls require the controller worker" }
        active?.let { if (current(it) && it.remote != null) return it else retire(it) }
        val binding = Binding()
        binding.link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (current(binding)) {
                    binding.remote = Messenger(binder); binding.ready.countDown()
                } else retire(binding)
            }
            override fun onServiceDisconnected(name: ComponentName) { retire(binding) }
            override fun onBindingDied(name: ComponentName) { retire(binding) }
            override fun onNullBinding(name: ComponentName) { retire(binding) }
        }
        synchronized(stateLock) { active = binding }
        main.post {
            if (current(binding)) {
                try {
                    binding.bound = app.bindService(Intent(app, A4NativeService::class.java), binding.link, Context.BIND_AUTO_CREATE)
                    if (!binding.bound) retire(binding)
                } catch (_: RuntimeException) { retire(binding) }
            }
        }
        if (!binding.ready.await(5, TimeUnit.SECONDS) || !current(binding) || binding.remote == null) {
            retire(binding); error("Native worker unavailable (binding failed or timed out)")
        }
        return binding
    }
    @Synchronized private fun call(operation: String, value: Int? = null): Bundle {
        val binding = connect()
        if (operation != "hello" && !binding.session.established) call("hello")
        check(current(binding)) { "Native connection changed; reconcile before retrying" }
        val done = CountDownLatch(1)
        binding.pending = done
        var answer: Bundle? = null
        val id = ++sequence
        val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (current(binding) && msg.arg1 == id && msg.data.getString("connection") == binding.session.token) {
                answer = msg.data; done.countDown()
            }
            true
        })
        val request = Message.obtain(null, 1).apply {
            arg1 = id; replyTo = reply
            data = Bundle().apply { putString("operation", operation); putString("connection", binding.session.token); if (value != null) putInt("value", value) }
        }
        try {
            val remote = binding.remote ?: error("Native worker disconnected before send")
            remote.send(request)
            if (!done.await(6, TimeUnit.SECONDS)) {
                retire(binding) // Last unbind makes the owned worker terminate itself.
                error("Native $operation timed out; vendor outcome unknown. Read before writing again")
            }
            check(current(binding)) { "Native worker disconnected; outcome unknown" }
            val result = answer ?: error("Native worker died without a response; outcome unknown")
            val worker = result.getString("worker") ?: error("Missing worker identity")
            if (operation == "hello") {
                binding.session.hello(result.getInt("uid", -1), result.getInt("pid", -1), Process.myUid(), Process.myPid(), worker)
            } else binding.session.verify(result.getString("connection"), worker)
            val edit = app.getSharedPreferences("tv-display-v1", Context.MODE_PRIVATE).edit()
            if (operation == "hello") edit.remove("first-initialisation-error")
            result.getString("diagnostics")?.let { edit.putString("native-diagnostics", it) }
            result.getString("first-initialisation-error")?.let { edit.putString("first-initialisation-error", it) }
            edit.apply()
            check(!result.containsKey("error")) { result.getString("error") ?: "Native failure" }
            return result
        } catch (error: RemoteException) {
            retire(binding)
            throw IllegalStateException("Native worker died; outcome unknown. Read before retrying", error)
        } finally { if (binding.pending === done) binding.pending = null }
    }
    override fun read(): Int {
        val result = call("read")
        nativeContext = result.getString("context")
        check(!nativeContext.isNullOrBlank()) { "Missing native context" }
        check(result.getInt("minimum", -1) == 0 && result.getInt("maximum", -1) == 100) { "Unsupported native range" }
        val level = result.getInt("level", -1)
        check(level in 0..100) { "Invalid native readback" }
        return level
    }
    override fun claim() { call("claim") }
    override fun write(value: Int) { require(value in 0..100); call("write", value) }
    override fun release() { if (active?.remote != null) call("release") }
    override fun close() { active?.let { retire(it) }; nativeContext = null }
}
