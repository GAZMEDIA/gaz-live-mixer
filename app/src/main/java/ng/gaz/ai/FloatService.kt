package ng.gaz.ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * The floating GAZ AI bubble. Stays on top of any app; shows a badge when replies are ready.
 * Tap it to see suggested replies and send one with a tap, without leaving the chat you're in.
 */
class FloatService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var badge: TextView? = null
    private var panel: View? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var index = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        addBubble()
        Engine.onPendingChanged = { refreshBadge(); if (panel != null) showPanel() }
        refreshBadge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        Engine.onPendingChanged = null
        hidePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Floating bubble", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 3, Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("GAZ AI bubble is on")
            .setContentText("Tap the floating bubble to see reply suggestions")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide bubble", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(58)
        val f = FrameLayout(this)
        val circle = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0B0D10.toInt()); setStroke(dp(2), 0xFFFF7A1A.toInt()) }
            elevation = dp(6).toFloat()
        }
        f.addView(circle, FrameLayout.LayoutParams(size, size))
        val b = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) }
            visibility = View.GONE; elevation = dp(8).toFloat()
        }
        f.addView(b, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        badge = b

        bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.int(this@FloatService, "bubble_x", resources.displayMetrics.widthPixels - size - dp(8))
            y = Prefs.int(this@FloatService, "bubble_y", dp(220))
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        f.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = bubbleLp.x; startY = bubbleLp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { bubbleLp.x = startX + dx.toInt(); bubbleLp.y = startY + dy.toInt(); wm.updateViewLayout(f, bubbleLp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // snap to the nearest side
                        val w = resources.displayMetrics.widthPixels
                        bubbleLp.x = if (bubbleLp.x + size / 2 < w / 2) dp(4) else w - size - dp(4)
                        wm.updateViewLayout(f, bubbleLp)
                        Prefs.putInt(this, "bubble_x", bubbleLp.x); Prefs.putInt(this, "bubble_y", bubbleLp.y)
                    } else {
                        if (panel == null) showPanel() else hidePanel()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(f, bubbleLp)
        bubble = f
    }

    private fun refreshBadge() {
        val n = Engine.pending.size
        badge?.apply { visibility = if (n > 0) View.VISIBLE else View.GONE; text = if (n > 9) "9+" else n.toString() }
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
        stateListAnimator = null; setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { onClick() }
    }

    private fun showPanel() {
        hidePanel()
        val pend = Engine.pending.keys.toList()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0xF2121418.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0xFF2A303A.toInt()) }
            elevation = dp(10).toFloat()
        }
        fun lp(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
        val header = TextView(this).apply { setTextColor(0xFFFF7A1A.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }
        box.addView(header)

        if (pend.isEmpty()) {
            header.text = "GAZ AI"
            box.addView(TextView(this).apply {
                text = "No replies waiting. When a message arrives, suggestions appear here."
                setTextColor(0xFFB8C0CC.toInt()); textSize = 14f; setPadding(0, dp(6), 0, dp(6))
            })
            box.addView(pill("Open GAZ AI", 0xFF1B1F26.toInt()) { openActivity(Intent(this, MainActivity::class.java)) }, lp(8))
        } else {
            if (index >= pend.size) index = 0
            val chatId = pend[index]
            val chat = Db.get(this).chat(chatId)
            val sugg = Engine.pending[chatId] ?: emptyList()
            header.text = "REPLY ${index + 1} OF ${pend.size} · ${chat?.appLabel?.uppercase() ?: ""}"
            box.addView(TextView(this).apply {
                text = chat?.title ?: "Chat"; setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            })
            val last = Db.get(this).recent(chatId, 1).lastOrNull()?.text ?: chat?.lastText ?: ""
            box.addView(TextView(this).apply {
                text = "“$last”"; setTextColor(0xFFB8C0CC.toInt()); textSize = 14f; maxLines = 3; setPadding(0, dp(4), 0, dp(6))
            })
            for (s in sugg) {
                box.addView(pill(s, 0xFF1B1F26.toInt()) {
                    if (Engine.send(this, chatId, s)) {
                        Toast.makeText(this, "Sent to ${chat?.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        Engine.showCopyFallback(this, chatId, s)
                        Toast.makeText(this, "Can't send to that chat from here", Toast.LENGTH_LONG).show()
                    }
                }, lp(6))
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun rlp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            row.addView(pill("Edit", 0xFFFF7A1A.toInt()) {
                openActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CHAT, chatId))
            }, rlp())
            row.addView(pill("Skip", 0xFF2A303A.toInt()) { Engine.dismissPending(this, chatId) }, rlp())
            if (pend.size > 1) row.addView(pill("Next ›", 0xFF2A303A.toInt()) { index = (index + 1) % pend.size; showPanel() }, rlp())
            box.addView(row, lp(10))
        }
        box.addView(pill("Close", 0xFF000000.toInt()) { hidePanel() }.apply { gravity = Gravity.CENTER }, lp(6))

        val scroll = ScrollView(this).apply { addView(box) }
        val w = resources.displayMetrics.widthPixels
        val plp = WindowManager.LayoutParams(
            (w * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (bubbleLp.y + dp(64)).coerceAtMost(resources.displayMetrics.heightPixels - dp(380)).coerceAtLeast(dp(40))
        }
        scroll.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { hidePanel(); true } else false }
        wm.addView(scroll, plp)
        panel = scroll
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun openActivity(i: Intent) {
        hidePanel()
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    companion object {
        const val CH = "bubble"
        const val NOTIF_ID = 77
        const val ACTION_STOP = "ng.gaz.ai.STOP_BUBBLE"

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            runCatching { ctx.startForegroundService(Intent(ctx, FloatService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, FloatService::class.java)) }
        }
    }
}
