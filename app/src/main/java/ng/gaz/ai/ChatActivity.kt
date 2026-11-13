package ng.gaz.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ng.gaz.ai.Ui.button
import ng.gaz.ai.Ui.col
import ng.gaz.ai.Ui.dp
import ng.gaz.ai.Ui.input
import ng.gaz.ai.Ui.label
import ng.gaz.ai.Ui.lp
import ng.gaz.ai.Ui.round
import ng.gaz.ai.Ui.row
import ng.gaz.ai.Ui.section
import ng.gaz.ai.Ui.spinner
import ng.gaz.ai.Ui.heading

/** One chat: see the conversation, pick or edit a reply, approve and send, or switch auto-reply on. */
class ChatActivity : Activity() {
    companion object {
        const val EXTRA_CHAT = "chat"
        const val EXTRA_PREFILL = "prefill"
    }

    private lateinit var chatId: String
    private lateinit var msgsBox: LinearLayout
    private lateinit var sugBox: LinearLayout
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var modeRow: LinearLayout
    private var tone = "Friendly"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra(EXTRA_CHAT) ?: run { finish(); return }
        val chat = Db.get(this).chat(chatId) ?: run { finish(); return }
        tone = Engine.effectiveTone(this, chat)

        val root = col(16)
        root.addView(heading(chat.title, 24f))
        root.addView(label("${chat.appLabel}${if (chat.isGroup) " · group" else ""}"))

        // Mode: Off / Suggest / Auto
        root.addView(section("For this chat"))
        modeRow = row()
        root.addView(modeRow)
        drawModes()
        root.addView(label("Tone"))
        root.addView(spinner(TONES, tone) {
            tone = TONES[it]
            Db.get(this).setChatField(chatId, "tone", tone)
            regenerate()
        })

        root.addView(section("Conversation"))
        msgsBox = col(0)
        root.addView(msgsBox)
        root.addView(label("GAZ AI only sees messages that arrived as notifications.", 0xFF5A6270.toInt(), 12f))

        root.addView(section("Suggested replies"))
        sugBox = col(0)
        root.addView(sugBox)
        status = label("")
        root.addView(status)

        editor = input("Pick a suggestion or type your reply", intent.getStringExtra(EXTRA_PREFILL) ?: "", multiLine = true) {}
        root.addView(editor, lp(top = 8))
        val actions = row()
        val regen = button("New ideas", Ui.CARD2) { regenerate() }
        val send = button("Send", Ui.ORANGE) { sendNow() }
        actions.addView(regen, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        actions.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, lp(top = 10))
        root.addView(button("Copy reply (paste it in the app yourself)", Ui.CARD) { copy(editor.text.toString()) }, lp(top = 8))

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) })

        drawMessages()
        val saved = Db.get(this).suggestions(chatId)
        if (saved.isNotEmpty()) drawSuggestions(saved) else regenerate()
    }

    private fun drawModes() {
        val chat = Db.get(this).chat(chatId) ?: return
        val current = Engine.effectiveMode(this, chat)
        modeRow.removeAllViews()
        for (m in MODES) {
            val b = button(modeLabel(m), if (m == current) (if (m == "auto") Ui.ORANGE else 0xFF2F6FEB.toInt()) else Ui.CARD2) {
                if (m == "auto" && current != "auto") {
                    Toast.makeText(this, "Auto-reply on: GAZ AI will answer ${chat.title} by itself after a short wait (you can cancel each one).", Toast.LENGTH_LONG).show()
                }
                Db.get(this).setChatField(chatId, "mode", m)
                if (m != "auto") Engine.cancelAuto(this, chatId)
                drawModes()
            }
            b.textSize = 14f
            modeRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        }
    }

    private fun drawMessages() {
        msgsBox.removeAllViews()
        val msgs = Db.get(this).recent(chatId, 25)
        if (msgs.isEmpty()) msgsBox.addView(label("No messages yet."))
        for (m in msgs) {
            val bubble = TextView(this).apply {
                text = if (m.fromMe) m.text else m.text
                setTextColor(Color.WHITE); textSize = 15f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = round(if (m.fromMe) 0xFF5A2A0A.toInt() else Ui.CARD2, 14)
                maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            }
            val wrap = FrameLayout(this)
            wrap.addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (m.fromMe) Gravity.END else Gravity.START
            })
            if (m.fromMe && m.sender == "GAZ AI") {
                wrap.addView(TextView(this).apply { text = "auto"; setTextColor(Ui.DIM); textSize = 10f },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.START })
            }
            msgsBox.addView(wrap, lp(bottom = 6))
        }
    }

    private fun drawSuggestions(list: List<String>) {
        sugBox.removeAllViews()
        list.forEach { s ->
            val b = button(s, Ui.CARD) { editor.setText(s); editor.setSelection(s.length) }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 15f
            b.typeface = android.graphics.Typeface.DEFAULT
            sugBox.addView(b, lp(bottom = 8))
        }
        if (editor.text.isEmpty() && list.isNotEmpty()) editor.setText(list.first())
    }

    private fun regenerate() {
        status.setTextColor(Ui.DIM)
        status.text = "Thinking of replies ($tone)…"
        Engine.generate(this, chatId, fromUser = true, tone = tone) { replies, err ->
            if (isFinishing) return@generate
            if (replies != null) {
                status.text = ""
                editor.setText("")
                drawSuggestions(replies)
            } else {
                status.setTextColor(Ui.RED)
                status.text = err ?: "Something went wrong"
            }
        }
    }

    private fun sendNow() {
        val text = editor.text.toString().trim()
        if (text.isEmpty()) return
        if (Engine.send(this, chatId, text)) {
            Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
            editor.setText("")
            sugBox.removeAllViews()
            drawMessages()
        } else {
            copy(text)
            val chat = Db.get(this).chat(chatId)
            Toast.makeText(this, "Can't send from here (open the chat in ${chat?.appLabel ?: "the app"}). Reply copied, paste it there.", Toast.LENGTH_LONG).show()
            chat?.let { packageManager.getLaunchIntentForPackage(it.pkg)?.let { i -> startActivity(i) } }
        }
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("GAZ AI reply", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
