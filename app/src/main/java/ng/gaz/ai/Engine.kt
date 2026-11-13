package ng.gaz.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The heart of GAZ AI: remembers each chat's "Reply" button, asks the AI for replies,
 * shows suggestions, and sends approved or automatic replies.
 */
object Engine {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** The chat app's own inline Reply action for each chat (lives while the notification exists). */
    val replyActions = ConcurrentHashMap<String, Notification.Action>()
    private val pendingGen = ConcurrentHashMap<String, Runnable>()
    private val pendingAuto = ConcurrentHashMap<String, Runnable>()
    private val pendingAutoText = ConcurrentHashMap<String, String>()

    /** Chats with replies waiting for you (shown on the floating bubble). Newest last. */
    val pending = java.util.Collections.synchronizedMap(LinkedHashMap<String, List<String>>())
    var onPendingChanged: (() -> Unit)? = null

    private fun pendingChanged() = main.post { onPendingChanged?.invoke() }

    fun dismissPending(ctx: Context, chatId: String) {
        pending.remove(chatId)
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(chatId))
        pendingChanged()
    }

    const val CH_SUGGEST = "suggestions"
    const val CH_AUTO = "auto"
    const val CH_STATUS = "status"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SUGGEST, "Reply suggestions", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Suggested replies you can send with one tap"
        })
        nm.createNotificationChannel(NotificationChannel(CH_AUTO, "Auto-replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Auto-replies about to be sent, with a Cancel button"
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Problems", NotificationManager.IMPORTANCE_LOW))
    }

    fun notifId(chatId: String) = chatId.hashCode()

    /** Called when new messages arrive in a chat. Waits a few seconds for bursts of messages. */
    fun onIncoming(ctx: Context, chatId: String) {
        val app = ctx.applicationContext
        pendingGen.remove(chatId)?.let { main.removeCallbacks(it) }
        // A new message cancels an auto-reply that hasn't gone out yet; a fresh one is prepared.
        cancelAuto(app, chatId, silent = true)
        val r = Runnable {
            pendingGen.remove(chatId)
            generate(app, chatId, fromUser = false)
        }
        pendingGen[chatId] = r
        main.postDelayed(r, 4000)
    }

    fun effectiveMode(ctx: Context, chat: Chat): String {
        chat.mode?.let { return it }
        val def = Prefs.str(ctx, Prefs.DEFAULT_MODE, "suggest")
        // Groups never auto-reply unless switched on per chat, or allowed in settings
        if (def == "auto" && chat.isGroup && !Prefs.bool(ctx, Prefs.AUTO_GROUPS, false)) return "suggest"
        return def
    }

    fun effectiveTone(ctx: Context, chat: Chat) = chat.tone ?: Prefs.str(ctx, Prefs.DEFAULT_TONE, "Friendly")

    /** Ask the AI for replies. [onDone] runs on the main thread with the replies or an error. */
    fun generate(ctx: Context, chatId: String, fromUser: Boolean, tone: String? = null,
                 onDone: ((List<String>?, String?) -> Unit)? = null) {
        val app = ctx.applicationContext
        worker.execute {
            val chat = Db.get(app).chat(chatId) ?: return@execute
            val mode = effectiveMode(app, chat)
            if (!fromUser && (mode == "off" || !Prefs.bool(app, Prefs.ENABLED, true))) return@execute
            val history = Db.get(app).recent(chatId, 20)
            if (history.isEmpty()) return@execute
            try {
                val replies = Brain.suggest(app, chat, history, tone ?: effectiveTone(app, chat))
                Db.get(app).setSuggestions(chatId, replies)
                main.post {
                    onDone?.invoke(replies, null)
                    if (!fromUser) {
                        if (mode == "auto") scheduleAuto(app, chat, replies.first()) else showSuggestions(app, chat, replies)
                    }
                }
            } catch (e: Exception) {
                val msg = (e as? Brain.BrainError)?.message ?: ("Could not get replies: " + (e.message ?: e.javaClass.simpleName))
                main.post {
                    onDone?.invoke(null, msg)
                    if (!fromUser) showProblem(app, msg)
                }
            }
        }
    }

    private fun pi(ctx: Context, req: Int, intent: Intent, activity: Boolean = false): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (activity) PendingIntent.getActivity(ctx, req, intent, flags)
        else PendingIntent.getBroadcast(ctx, req, intent, flags)
    }

    private fun openChatIntent(ctx: Context, chatId: String) =
        Intent(ctx, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CHAT, chatId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun showSuggestions(ctx: Context, chat: Chat, replies: List<String>) {
        if (replies.isEmpty()) return
        pending.remove(chat.id); pending[chat.id] = replies; pendingChanged()
        val id = notifId(chat.id)
        val last = Db.get(ctx).recent(chat.id, 1).lastOrNull()?.text ?: chat.lastText
        val text = StringBuilder("${chat.title}: $last\n")
        replies.forEachIndexed { i, r -> text.append("\n${i + 1}. $r") }
        val b = Notification.Builder(ctx, CH_SUGGEST)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Reply to ${chat.title} · ${chat.appLabel}")
            .setContentText(replies.first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi(ctx, id, openChatIntent(ctx, chat.id), activity = true))
            .setAutoCancel(true)
            .setColor(0xFFFF7A1A.toInt())
        // Two one-tap replies + "Edit" (Android allows 3 buttons)
        replies.take(2).forEachIndexed { i, r ->
            val intent = Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.SEND)
                .putExtra(ActionReceiver.EXTRA_CHAT, chat.id).putExtra(ActionReceiver.EXTRA_TEXT, r)
            val label = "${i + 1}. " + (if (r.length > 28) r.take(26) + "…" else r)
            b.addAction(Notification.Action.Builder(null, label, pi(ctx, id * 10 + i, intent)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Edit / more", pi(ctx, id * 10 + 5, openChatIntent(ctx, chat.id), activity = true)).build())
        ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    fun scheduleAuto(ctx: Context, chat: Chat, reply: String) {
        // Safety: at most 6 auto-replies per chat per hour
        if (Db.get(ctx).autoRepliesSince(chat.id, System.currentTimeMillis() - 3_600_000) >= 6) {
            showSuggestions(ctx, chat, Db.get(ctx).suggestions(chat.id))
            return
        }
        val base = Prefs.int(ctx, Prefs.AUTO_DELAY, 20).coerceIn(5, 600)
        val delay = base + Random.nextInt(0, base + 1)
        val id = notifId(chat.id)
        val r = Runnable {
            pendingAuto.remove(chat.id)
            val t = pendingAutoText.remove(chat.id) ?: return@Runnable
            if (send(ctx, chat.id, t, auto = true)) {
                ctx.getSystemService(NotificationManager::class.java).cancel(id)
            } else {
                showProblem(ctx, "Couldn't auto-reply to ${chat.title}: ${chat.appLabel} no longer offers a Reply button for that chat.")
            }
        }
        pendingAuto[chat.id] = r
        pendingAutoText[chat.id] = reply
        main.postDelayed(r, delay * 1000L)

        val cancel = Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.CANCEL_AUTO).putExtra(ActionReceiver.EXTRA_CHAT, chat.id)
        val now = Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.SEND)
            .putExtra(ActionReceiver.EXTRA_CHAT, chat.id).putExtra(ActionReceiver.EXTRA_TEXT, reply)
        val n = Notification.Builder(ctx, CH_AUTO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Auto-reply to ${chat.title} in ~${delay}s")
            .setContentText(reply)
            .setStyle(Notification.BigTextStyle().bigText(reply))
            .setContentIntent(pi(ctx, id, openChatIntent(ctx, chat.id), activity = true))
            .setColor(0xFFFF7A1A.toInt())
            .addAction(Notification.Action.Builder(null, "Cancel", pi(ctx, id * 10 + 7, cancel)).build())
            .addAction(Notification.Action.Builder(null, "Send now", pi(ctx, id * 10 + 8, now)).build())
            .addAction(Notification.Action.Builder(null, "Edit", pi(ctx, id * 10 + 9, openChatIntent(ctx, chat.id), activity = true)).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancelAuto(ctx: Context, chatId: String, silent: Boolean = false) {
        pendingAuto.remove(chatId)?.let { main.removeCallbacks(it) }
        val had = pendingAutoText.remove(chatId) != null
        if (had && !silent) ctx.getSystemService(NotificationManager::class.java).cancel(notifId(chatId))
    }

    /** Send [text] into the chat app through its own Reply button. Returns false if not possible. */
    fun send(ctx: Context, chatId: String, text: String, auto: Boolean = false): Boolean {
        val action = replyActions[chatId] ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(ctx, 0, intent)
            pendingAuto.remove(chatId)?.let { main.removeCallbacks(it) }
            pendingAutoText.remove(chatId)
            Db.get(ctx).addMsg(chatId, Msg(true, if (auto) "GAZ AI" else "me", text, System.currentTimeMillis()))
            Db.get(ctx).setSuggestions(chatId, emptyList())
            ctx.getSystemService(NotificationManager::class.java).cancel(notifId(chatId))
            pending.remove(chatId); pendingChanged()
            true
        } catch (e: Exception) {
            replyActions.remove(chatId)
            false
        }
    }

    fun showCopyFallback(ctx: Context, chatId: String, text: String) {
        val chat = Db.get(ctx).chat(chatId)
        val id = notifId(chatId)
        val open = openChatIntent(ctx, chatId).putExtra(ChatActivity.EXTRA_PREFILL, text)
        val n = Notification.Builder(ctx, CH_SUGGEST)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Couldn't send to ${chat?.title ?: "this chat"}")
            .setContentText("Tap to open, copy the reply and paste it in ${chat?.appLabel ?: "the app"}.")
            .setContentIntent(pi(ctx, id * 10 + 6, open, activity = true))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun showProblem(ctx: Context, msg: String) {
        val n = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("GAZ AI")
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setContentIntent(pi(ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), activity = true))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1, n)
    }
}
