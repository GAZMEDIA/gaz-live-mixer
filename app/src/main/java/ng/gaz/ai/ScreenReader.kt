package ng.gaz.ai

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the chat you have open on screen (any messaging app), so GAZ AI knows the full
 * conversation history, and types the chosen reply into that app's message box.
 */
class ScreenReader : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val readLater = Runnable { readScreen() }

    override fun onServiceConnected() {
        instance = this
        Engine.channels(this)
        if (Prefs.bool(this, "bubble", false)) FloatService.start(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (!isChatApp(pkg)) {
            // Left the chat app: forget the on-screen chat
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && Engine.screenChatId != null) {
                Engine.setScreenChat(null)
            }
            return
        }
        main.removeCallbacks(readLater)
        main.postDelayed(readLater, 600)
    }

    private fun isChatApp(pkg: String) = pkg in GazListener.KNOWN.keys || pkg in EXTRA_CHAT_APPS

    // ------------------------------------------------------------------ reading

    private data class Item(val text: String, val rect: Rect, val editable: Boolean, val id: String?)

    private fun readScreen() {
        if (!Prefs.bool(this, Prefs.ENABLED, true)) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!isChatApp(pkg)) return
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val w = screen.width().coerceAtLeast(1)
        val h = screen.height().coerceAtLeast(1)

        val items = ArrayList<Item>()
        collect(root, items, 0)
        if (items.isEmpty()) return

        val input = items.firstOrNull { it.editable }
        if (input == null) { Engine.setScreenChat(null); return }      // not inside a conversation

        // Chat name: WhatsApp id, else the first text in the top bar
        val title = items.firstOrNull { it.id?.endsWith("conversation_contact_name") == true || it.id?.endsWith("toolbar_title") == true }?.text
            ?: items.filter { !it.editable && it.rect.top < screen.top + h * 0.13 && it.rect.left > screen.left + w * 0.10 && it.text.length in 1..60 }
                .minByOrNull { it.rect.top }?.text
            ?: return
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return

        val top = items.filter { it.text == title }.minOfOrNull { it.rect.bottom } ?: (screen.top + (h * 0.1).toInt())
        val bottom = input.rect.top

        // WhatsApp gives message ids; other apps: any text in the message area
        val waMsgs = items.filter { it.id?.endsWith(":id/message_text") == true }
        val bodies = (if (waMsgs.isNotEmpty()) waMsgs else items.filter {
            !it.editable && it.rect.top >= top && it.rect.bottom <= bottom && !isNoise(it.text, cleanTitle)
        }).sortedBy { it.rect.top }

        val msgs = ArrayList<Msg>()
        for (b in bodies) {
            val cx = b.rect.centerX() - screen.left
            val fromMe = when {
                b.rect.left - screen.left > w * 0.25 && cx > w * 0.5 -> true
                b.rect.right - screen.left < w * 0.75 && cx < w * 0.5 -> false
                else -> cx > w * 0.55
            }
            val text = b.text.trim()
            if (msgs.lastOrNull()?.let { it.text == text && it.fromMe == fromMe } == true) continue
            msgs.add(Msg(fromMe, if (fromMe) "me" else cleanTitle, text, 0))
        }

        val chatId = "$pkg|$cleanTitle"
        val app = GazListener.KNOWN[pkg] ?: appLabel(pkg)
        Db.get(this).upsertChat(chatId, pkg, cleanTitle, app, false,
            System.currentTimeMillis(), msgs.lastOrNull()?.text ?: "")
        if (msgs.isNotEmpty()) Db.get(this).mergeScreen(chatId, msgs)
        Engine.setScreenChat(chatId)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int) {
        if (n == null || depth > 40 || out.size > 400) return
        if (!n.isVisibleToUser) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        val text = n.text?.toString()
        if (n.isEditable) {
            out.add(Item(text ?: "", r, true, n.viewIdResourceName))
        } else if (!text.isNullOrBlank()) {
            out.add(Item(text, r, false, n.viewIdResourceName))
        } else if (n.childCount == 0) {
            // Apps like Telegram describe each message instead of exposing text
            val d = n.contentDescription?.toString()
            if (!d.isNullOrBlank() && d.length > 1) out.add(Item(d, r, false, n.viewIdResourceName))
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun isNoise(t: String, title: String): Boolean {
        val s = t.trim()
        if (s.isEmpty() || s == title) return true
        if (TIME.matches(s) || DATE.matches(s)) return true
        if (s.lowercase() in UI_WORDS) return true
        if (s.length <= 1) return true
        return false
    }

    private fun appLabel(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ------------------------------------------------------------------ typing

    /** Put [text] in the open chat's message box. If [send] is true, also press the app's Send button. */
    fun typeReply(text: String, send: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val box = findEditable(root) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (send) main.postDelayed({ rootInActiveWindow?.let { pressSend(it) } }, 350)
        return true
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun pressSend(root: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val id = n.viewIdResourceName ?: ""
            val d = (n.contentDescription ?: n.text ?: "").toString().trim().lowercase()
            if (n.isVisibleToUser && (id.endsWith(":id/send") || d == "send" || d == "send message")) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    companion object {
        @Volatile var instance: ScreenReader? = null

        val EXTRA_CHAT_APPS = setOf(
            "com.facebook.katana", "com.viber.voip", "com.imo.android.imoim", "jp.naver.line.android",
            "com.discord", "com.badoo.mobile", "com.bumble.app", "co.hinge.app", "com.google.android.gm"
        )
        private val TIME = Regex("""^\d{1,2}[:.]\d{2}(\s?[aApP]\.?[mM]\.?)?$""")
        private val DATE = Regex("""^(today|yesterday|\w+day|\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}|\w+ \d{1,2}, \d{4}|\d{1,2} \w+ \d{4})$""", RegexOption.IGNORE_CASE)
        private val UI_WORDS = setOf(
            "online", "typing…", "typing...", "message", "type a message", "send", "camera", "emoji", "attach",
            "voice message", "video call", "call", "back", "more options", "end-to-end encrypted", "unread messages",
            "seen", "delivered", "read", "sent", "edited", "new messages", "last seen recently"
        )
    }
}
