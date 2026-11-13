package ng.gaz.ai

import android.app.Notification
import android.app.Person
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads message notifications from any chat app that offers a Reply button
 * (WhatsApp, WhatsApp Business, Messenger, Instagram, Telegram, SMS, Signal, ...).
 */
class GazListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Engine.channels(this)
        // Pick up chats that already have notifications showing
        runCatching { activeNotifications?.forEach { handle(it, triggerAi = false) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.bool(this, Prefs.ENABLED, true)) return
        runCatching { handle(sbn, triggerAi = true) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep the reply action: some apps re-post; a stale one simply fails and is dropped.
    }

    private fun handle(sbn: StatusBarNotification, triggerAi: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val reply = findReplyAction(n) ?: return            // not a chat message

        val ex = n.extras ?: return
        val isGroup = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        var title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim() ?: return
        title = cleanTitle(title)
        if (title.isEmpty()) return

        val pkg = sbn.packageName
        val chatId = "$pkg|$title"
        val app = appLabel(pkg)
        val msgs = readMessages(ex, title)
        if (msgs.isEmpty()) return

        Engine.replyActions[chatId] = reply
        val db = Db.get(this)
        db.upsertChat(chatId, pkg, title, app, isGroup, System.currentTimeMillis(), msgs.last().text)
        var newIncoming = false
        for (m in msgs) if (db.addMsg(chatId, m) && !m.fromMe) newIncoming = true
        if (newIncoming && triggerAi) Engine.onIncoming(this, chatId)
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val all = ArrayList<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        runCatching { all.addAll(Notification.WearableExtender(n).actions) }
        return all.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
    }

    /** WhatsApp shows "Lily (3 messages)" etc. */
    private fun cleanTitle(t: String) = t
        .replace(Regex("""\s*\(\d+\s+\w+\)$"""), "")
        .replace(Regex("""^\d+ new messages? from\s+""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun readMessages(ex: Bundle, title: String): List<Msg> {
        val out = ArrayList<Msg>()
        val arr: Array<Parcelable>? = runCatching {
            @Suppress("DEPRECATION") ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull()
        if (arr != null) {
            for (p in arr) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                var sender = b.getCharSequence("sender")?.toString()
                if (sender == null && Build.VERSION.SDK_INT >= 28) {
                    @Suppress("DEPRECATION")
                    sender = (b.getParcelable<Parcelable>("sender_person") as? Person)?.name?.toString()
                }
                val time = b.getLong("time", System.currentTimeMillis())
                // A message with no sender is one *you* sent
                out.add(Msg(sender == null, sender ?: "me", text, time))
            }
        }
        if (out.isEmpty()) {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && !Regex("""^\d+ new messages?$""").matches(text)) {
                out.add(Msg(false, title, text, System.currentTimeMillis()))
            }
        }
        return out
    }

    private fun appLabel(pkg: String): String = KNOWN[pkg] ?: runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    }.getOrDefault(pkg)

    companion object {
        val KNOWN = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Samsung Messages",
            "com.tinder" to "Tinder",
            "com.snapchat.android" to "Snapchat",
            "com.linkedin.android" to "LinkedIn",
            "com.twitter.android" to "X"
        )
    }
}
