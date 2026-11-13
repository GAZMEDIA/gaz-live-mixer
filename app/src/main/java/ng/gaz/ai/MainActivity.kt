package ng.gaz.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ng.gaz.ai.Ui.button
import ng.gaz.ai.Ui.card
import ng.gaz.ai.Ui.col
import ng.gaz.ai.Ui.dp
import ng.gaz.ai.Ui.input
import ng.gaz.ai.Ui.label
import ng.gaz.ai.Ui.lp
import ng.gaz.ai.Ui.round
import ng.gaz.ai.Ui.section
import ng.gaz.ai.Ui.spinner
import ng.gaz.ai.Ui.switch
import ng.gaz.ai.Ui.heading

/** Setup screen + list of chats. */
class MainActivity : Activity() {

    private lateinit var statusBox: LinearLayout
    private lateinit var chatsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.channels(this)
        val root = col(18)
        root.addView(heading("GAZ AI", 28f))
        root.addView(label("Your Messages. Your Style. Your Personal AI Assistant.", Ui.DIM, 14f))

        statusBox = col(0)
        root.addView(statusBox, lp(top = 10))

        root.addView(switch("GAZ AI is on", Prefs.bool(this, Prefs.ENABLED, true)) {
            Prefs.putBool(this, Prefs.ENABLED, it)
        }, lp(top = 8))
        root.addView(switch("Floating bubble (shows replies over any app)", Prefs.bool(this, "bubble", false)) { on ->
            Prefs.putBool(this, "bubble", on)
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Allow \"Display over other apps\" for GAZ AI, then come back", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else FloatService.start(this)
            } else FloatService.stop(this)
        })

        // ---------------- Chats
        root.addView(section("Your chats"))
        chatsBox = col(0)
        root.addView(chatsBox)

        // ---------------- Brain
        root.addView(section("AI brain (free)"))
        val brain = card()
        val engines = listOf("Google Gemini (free, needs internet)", "My PC with Ollama (free, same Wi-Fi)")
        val geminiBox = col(0)
        val ollamaBox = col(0)
        fun showEngine() {
            val g = Prefs.str(this, Prefs.ENGINE, "gemini") == "gemini"
            geminiBox.visibility = if (g) android.view.View.VISIBLE else android.view.View.GONE
            ollamaBox.visibility = if (g) android.view.View.GONE else android.view.View.VISIBLE
        }
        brain.addView(spinner(engines, if (Prefs.str(this, Prefs.ENGINE, "gemini") == "gemini") engines[0] else engines[1]) {
            Prefs.putStr(this, Prefs.ENGINE, if (it == 0) "gemini" else "ollama"); showEngine()
        })
        geminiBox.addView(label("Gemini API key (free from Google AI Studio)"))
        geminiBox.addView(input("Paste your API key", Prefs.str(this, Prefs.API_KEY), secret = true) { Prefs.putStr(this, Prefs.API_KEY, it.trim()) })
        geminiBox.addView(button("Get a free key", Ui.CARD2) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }, lp(top = 8))
        geminiBox.addView(label("Model"))
        geminiBox.addView(input(Prefs.DEFAULT_MODEL, Prefs.str(this, Prefs.MODEL, Prefs.DEFAULT_MODEL)) { Prefs.putStr(this, Prefs.MODEL, it.trim()) })
        ollamaBox.addView(label("PC address (run Ollama on your PC)"))
        ollamaBox.addView(input("192.168.1.20:11434", Prefs.str(this, Prefs.OLLAMA_URL)) { Prefs.putStr(this, Prefs.OLLAMA_URL, it.trim()) })
        ollamaBox.addView(label("Ollama model"))
        ollamaBox.addView(input("llama3.2", Prefs.str(this, Prefs.OLLAMA_MODEL, "llama3.2")) { Prefs.putStr(this, Prefs.OLLAMA_MODEL, it.trim()) })
        brain.addView(geminiBox)
        brain.addView(ollamaBox)
        val testOut = label("")
        brain.addView(button("Test the AI", Ui.ORANGE) { testBrain(testOut) }, lp(top = 12))
        brain.addView(testOut)
        root.addView(brain)
        showEngine()

        // ---------------- You
        root.addView(section("About you"))
        val you = card()
        you.addView(label("Your name"))
        you.addView(input("e.g. Jones", Prefs.str(this, Prefs.MY_NAME)) { Prefs.putStr(this, Prefs.MY_NAME, it) })
        you.addView(label("About you (helps replies sound right)"))
        you.addView(input("e.g. Broadcast engineer in Port Harcourt, I run Gaz Media Production, I like…", Prefs.str(this, Prefs.ABOUT_ME), multiLine = true) {
            Prefs.putStr(this, Prefs.ABOUT_ME, it)
        })
        you.addView(label("How you write: paste 5–10 real messages you have sent, one per line"))
        you.addView(input("Paste your own messages here", Prefs.str(this, Prefs.STYLE), multiLine = true) { Prefs.putStr(this, Prefs.STYLE, it) })
        root.addView(you)

        // ---------------- Defaults
        root.addView(section("Default behaviour"))
        val def = card()
        def.addView(label("For new chats"))
        def.addView(spinner(MODES.map { modeLabel(it) }, modeLabel(Prefs.str(this, Prefs.DEFAULT_MODE, "suggest"))) {
            Prefs.putStr(this, Prefs.DEFAULT_MODE, MODES[it])
        })
        def.addView(label("Default tone"))
        def.addView(spinner(TONES, Prefs.str(this, Prefs.DEFAULT_TONE, "Friendly")) { Prefs.putStr(this, Prefs.DEFAULT_TONE, TONES[it]) })
        def.addView(label("Auto-reply waits at least this many seconds (looks more natural; you can cancel)"))
        def.addView(input("20", Prefs.int(this, Prefs.AUTO_DELAY, 20).toString(), number = true) {
            it.toIntOrNull()?.let { v -> Prefs.putInt(this, Prefs.AUTO_DELAY, v.coerceIn(5, 600)) }
        })
        def.addView(switch("Allow auto-replies in group chats", Prefs.bool(this, Prefs.AUTO_GROUPS, false)) {
            Prefs.putBool(this, Prefs.AUTO_GROUPS, it)
        })
        root.addView(def)

        root.addView(label("Gaz Media Production · Everything Multimedia", 0xFF5A6270.toInt(), 12f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(12))
        })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) })
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.bool(this, "bubble", false) && Settings.canDrawOverlays(this)) FloatService.start(this)
        refreshStatus()
        refreshChats()
    }

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, GazListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    @SuppressLint("BatteryLife")
    private fun refreshStatus() {
        statusBox.removeAllViews()
        fun step(ok: Boolean, text: String, fix: String?, action: (() -> Unit)?) {
            val c = card()
            c.addView(TextView(this).apply {
                this.text = (if (ok) "✓  " else "!  ") + text
                setTextColor(if (ok) Ui.GREEN else Color.WHITE); textSize = 15f
            })
            if (!ok && fix != null && action != null) c.addView(button(fix, Ui.ORANGE) { action() }, lp(top = 8))
            statusBox.addView(c, lp(bottom = 8))
        }
        val listen = listenerOn()
        step(listen, if (listen) "Can read your chat notifications" else "Allow GAZ AI to read chat notifications",
            "Open notification access") {
            Toast.makeText(this, "Find GAZ AI in the list and switch it on", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!listen && Build.VERSION.SDK_INT >= 33) {
            statusBox.addView(label("If Android says \"Restricted setting\": open App info for GAZ AI → tap ⋮ (top right) → \"Allow restricted settings\", then try again.", Ui.DIM, 13f))
            statusBox.addView(button("Open GAZ AI app info", Ui.CARD2) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, lp(bottom = 8))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            step(notif, if (notif) "Can show reply suggestions" else "Allow GAZ AI to show reply suggestions", "Allow notifications") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        step(battery, if (battery) "Allowed to run in the background" else "Stop Android from closing GAZ AI in the background",
            "Allow background") {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        val hasBrain = when (Prefs.str(this, Prefs.ENGINE, "gemini")) {
            "ollama" -> Prefs.str(this, Prefs.OLLAMA_URL).isNotBlank()
            else -> Prefs.str(this, Prefs.API_KEY).isNotBlank()
        }
        step(hasBrain, if (hasBrain) "AI brain is set up" else "Add your free Gemini API key below", null, null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshChats() {
        chatsBox.removeAllViews()
        val chats = Db.get(this).chats()
        if (chats.isEmpty()) {
            chatsBox.addView(label("No chats yet. When a message arrives on WhatsApp, Messenger, Instagram, Telegram or SMS, it shows up here."))
            return
        }
        for (c in chats) {
            val mode = Engine.effectiveMode(this, c)
            val when_ = DateUtils.getRelativeTimeSpanString(c.lastTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val b = button("${c.title}  ·  ${c.appLabel}\n${c.lastText.take(60)}\n${modeLabel(mode)} · ${Engine.effectiveTone(this, c)} · $when_", Ui.CARD) {
                startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CHAT, c.id))
            }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 14f
            b.typeface = android.graphics.Typeface.DEFAULT
            b.background = round(if (mode == "auto") 0xFF2A1A0E.toInt() else Ui.CARD, 12)
            chatsBox.addView(b, lp(bottom = 8))
        }
    }

    private fun testBrain(out: TextView) {
        out.setTextColor(Ui.DIM)
        out.text = "Asking the AI…"
        val chat = Chat("test", "test", "Ada", "WhatsApp", false, null, null, System.currentTimeMillis(), "")
        val history = listOf(Msg(false, "Ada", "Hi, how was your day? Are you free this weekend?", System.currentTimeMillis()))
        Thread {
            val result = runCatching { Brain.suggest(this, chat, history, Prefs.str(this, Prefs.DEFAULT_TONE, "Friendly")) }
            runOnUiThread {
                result.onSuccess {
                    out.setTextColor(Ui.GREEN)
                    out.text = "It works! Example replies to \"Hi, how was your day? Are you free this weekend?\":\n\n" +
                        it.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")
                }.onFailure {
                    out.setTextColor(Ui.RED)
                    out.text = it.message ?: "Something went wrong"
                }
                refreshStatus()
            }
        }.start()
    }
}
