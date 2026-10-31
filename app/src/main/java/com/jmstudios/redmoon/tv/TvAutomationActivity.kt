/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.KeyEvent
import android.widget.*
import com.jmstudios.redmoon.R
import java.util.UUID

/** Remote-only configuration, also the ACTION_CREATE_SHORTCUT result producer. */
class TvAutomationActivity : Activity() {
    private lateinit var state: AutomationState
    private lateinit var rows: LinearLayout
    private val creating get() = intent.action == Intent.ACTION_CREATE_SHORTCUT
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); state = AutomationState(this); render()
    }
    private fun render() {
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 28, 48, 28) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(14, 23, 36)); addView(rows) })
        rows.addView(TextView(this).apply { text = getString(R.string.automation_title); textSize = 28f; setTextColor(Color.WHITE) })
        rows.addView(TextView(this).apply { text = getString(R.string.automation_help); textSize = 17f; setTextColor(Color.LTGRAY) })
        button(getString(if (state.enabled) R.string.automation_disable else R.string.automation_enable)) {
            if (state.enabled) {
                TvPreferences(this).store.edit().putBoolean("external-control", false).apply()
                state.rotate(); TvDisplayService.command(this, "automation-revoke"); render()
            } else AlertDialog.Builder(this).setMessage(R.string.automation_consent)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                    state.token(); TvPreferences(this).store.edit().putBoolean("external-control", true).apply(); render()
                }.show()
        }
        if (!creating) {
            button(getString(R.string.automation_copy_token)) {
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                    android.content.ClipData.newPlainText(getString(R.string.automation_title), state.token()))
                Toast.makeText(this, R.string.automation_copied, Toast.LENGTH_SHORT).show()
            }
            button(getString(R.string.automation_rotate)) {
                AlertDialog.Builder(this).setMessage(R.string.automation_rotate_confirm)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                        state.rotate(); TvDisplayService.command(this, "automation-revoke"); render()
                    }.show()
            }
            val prefs = TvPreferences(this).store
            button(getString(if (prefs.getBoolean("legacy-control", false)) R.string.automation_legacy_on else R.string.automation_legacy_off)) {
                AlertDialog.Builder(this).setMessage(R.string.automation_legacy_warning)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs.edit().putBoolean("legacy-control", !prefs.getBoolean("legacy-control", false)).apply(); render()
                    }.show()
            }
        }
        val labels = resources.getStringArray(R.array.automation_commands)
        val commands = listOf("brightness_phase", "brightness_adjust", "brightness_set", "brightness_pause", "brightness_resume",
            "warmth_adjust", "warmth_set", "warmth_enable", "preset_apply", "snooze", "resume_auto", "cancel_transition",
            "reconcile", "show_status", "open_controls")
        commands.forEachIndexed { i, command -> button(labels[i]) {
            if (!state.enabled) Toast.makeText(this, R.string.automation_enable_first, Toast.LENGTH_SHORT).show()
            else configure(command, labels[i])
        } }
        if (!creating) rows.addView(TextView(this).apply {
            text = state.report().toString(2); textSize = 14f; setTextColor(Color.LTGRAY)
        })
    }
    private fun button(label: String, action: () -> Unit) {
        rows.addView(Button(this).apply {
            text = label; isAllCaps = false; textSize = 18f; minHeight = 64
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun choose(title: String, choices: Array<String>, save: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(choices) { _, i -> save(i) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun number(title: String, initial: Int, range: IntRange, save: (Int) -> Unit) {
        var n = initial
        val value = Button(this).apply { text = n.toString(); textSize = 32f }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(value)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> save(n) }.create()
        value.setOnKeyListener { _, key, e ->
            if (key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false else {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    val step = if (e.repeatCount > 5) 10 else 1
                    n = (n + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -step else step).coerceIn(range)
                    value.text = n.toString()
                }; true
            }
        }
        value.setOnClickListener { dialog.dismiss(); save(n) }
        dialog.show(); value.requestFocus()
    }
    private fun configure(command: String, label: String) {
        val data = Bundle().apply { putInt("api_version", 1); putString("command", command); putBoolean("feedback", true) }
        var title = label
        fun done() = finishAction(data, title)
        fun timing() {
            if (command in setOf("brightness_pause", "brightness_resume", "warmth_enable", "resume_auto", "cancel_transition", "reconcile", "show_status", "open_controls")) { done(); return }
            if (command == "snooze") {
                number(getString(R.string.automation_duration), 1800, 1..86400) { data.putInt("duration_seconds", it); done() }; return
            }
            number(getString(R.string.automation_fade), if (command.endsWith("_adjust")) 0 else 5, 0..888) { fade ->
                data.putInt("fade_seconds", fade)
                choose(getString(R.string.automation_expiry), resources.getStringArray(R.array.automation_expiries)) { expiry ->
                    data.putString("expiry", listOf("next_boundary", "duration", "until_resumed")[expiry])
                    if (expiry == 1) number(getString(R.string.automation_duration), 1800, 1..86400) { data.putInt("duration_seconds", it); done() }
                    else done()
                }
            }
        }
        fun parameters() {
            when (command) {
                "brightness_phase", "preset_apply" -> choose(label, arrayOf("Day", "Dusk", "Dim")) {
                    val phase = Phase.values()[it]; data.putString("phase", phase.name); title += " · ${phase.title}"; timing()
                }
                "brightness_adjust", "warmth_adjust" -> number(getString(R.string.automation_delta), 5, -20..20) {
                    if (it == 0) return@number
                    data.putInt("delta", it); title += " · ${if (it > 0) "+" else ""}$it"; timing()
                }
                "brightness_set", "warmth_set" -> number(getString(R.string.automation_value), if (command == "brightness_set") 70 else 20,
                    if (command == "brightness_set") 1..100 else 0..100) { data.putInt("value", it); title += " · $it%"; timing() }
                "warmth_enable" -> choose(label, resources.getStringArray(R.array.automation_switch)) {
                    data.putBoolean("enabled", it == 0); title += if (it == 0) " · On" else " · Off"; timing()
                }
                else -> timing()
            }
        }
        if (command in setOf("preset_apply", "snooze", "resume_auto", "cancel_transition"))
            choose(getString(R.string.automation_scope), resources.getStringArray(R.array.automation_scopes)) {
                data.putString("scope", listOf("both", "brightness", "warmth")[it]); parameters()
            }
        else parameters()
    }
    private fun finishAction(data: Bundle, title: String) {
        data.putString("token", state.token())
        // Deliberately no request_id: each invocation is a new button press.
        AutomationContract.parse(AutomationState.data(data), UUID.randomUUID().toString())
        if (!creating) {
            AutomationState.submit(this, data)
            Toast.makeText(this, R.string.automation_sent, Toast.LENGTH_SHORT).show(); return
        }
        val launch = Intent(this, TvAutomationDispatchActivity::class.java).setAction(AutomationContract.ACTION)
            .putExtras(data).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val icon = when {
            data.getString("command")!!.startsWith("brightness") -> android.R.drawable.ic_menu_day
            data.getString("command")!!.startsWith("warmth") -> android.R.drawable.ic_menu_compass
            else -> android.R.drawable.ic_menu_manage
        }
        val info = ShortcutInfo.Builder(this, UUID.randomUUID().toString()).setShortLabel(title)
            .setIcon(Icon.createWithResource(this, icon)).setIntent(launch).build()
        val result = getSystemService(ShortcutManager::class.java).createShortcutResultIntent(info) ?: Intent()
        result.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch).putExtra(Intent.EXTRA_SHORTCUT_NAME, title)
            .putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, icon))
        setResult(RESULT_OK, result); finish()
    }
}

/** Finishes immediately; broadcast recipes avoid even this brief activity/focus transition. */
class TvAutomationDispatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent.action == AutomationContract.ACTION) AutomationState.submit(this, intent.extras)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.automation_rejected, Toast.LENGTH_SHORT).show()
        } finally { finish() }
    }
}
