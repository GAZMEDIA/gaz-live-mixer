package ng.gaz.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Handles the buttons on GAZ AI's own notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val chatId = intent.getStringExtra(EXTRA_CHAT) ?: return
        when (intent.action) {
            SEND -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                if (!Engine.send(ctx, chatId, text)) {
                    // Android doesn't let a notification button open a screen directly, so offer a tap-to-open notification
                    Engine.showCopyFallback(ctx, chatId, text)
                }
            }
            CANCEL_AUTO -> {
                Engine.cancelAuto(ctx, chatId)
                Toast.makeText(ctx, "Auto-reply cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val SEND = "ng.gaz.ai.SEND"
        const val CANCEL_AUTO = "ng.gaz.ai.CANCEL_AUTO"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_TEXT = "text"
    }
}
