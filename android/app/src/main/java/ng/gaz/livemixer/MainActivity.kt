package ng.gaz.livemixer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Gaz Live Mixer for Android.
 * Finds the OBS PC running the Gaz Live Mixer plugin/script on the same Wi-Fi
 * (UDP broadcast on port 4467), then opens the mixer panel full screen.
 * Volume Down = AUTO, Volume Up = CUT.
 */
class MainActivity : Activity() {

    private data class Mixer(val name: String, val host: String, val port: Int) {
        val url get() = "http://$host:$port/"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var picker: View
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var addressIn: EditText
    private var web: WebView? = null
    private var scanning = false

    private val prefs by lazy { getSharedPreferences("gazmixer", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        picker = buildPicker()
        root.addView(picker)
        setContentView(root)
        scan(autoConnect = true)
    }

    /* ------------------------------------------------------------ picker UI */

    private fun buildPicker(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(22))
        }
        col.addView(TextView(this).apply {
            text = "GAZ LIVE MIXER"
            setTextColor(Color.WHITE); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        })
        col.addView(TextView(this).apply {
            text = "Control OBS like a video mixer"
            setTextColor(DIM); textSize = 14f; setPadding(0, dp(2), 0, dp(22))
        })
        status = TextView(this).apply { setTextColor(DIM); textSize = 14f; setPadding(0, 0, 0, dp(10)) }
        col.addView(status)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)

        col.addView(button("Search again", PANEL2) { scan(autoConnect = false) }, lp(top = 12))

        col.addView(TextView(this).apply {
            text = "Or type the address shown in OBS"
            setTextColor(DIM); textSize = 13f; setPadding(0, dp(28), 0, dp(6))
        })
        addressIn = EditText(this).apply {
            hint = "192.168.1.20:4466"
            setText(prefs.getString("manual", ""))
            setTextColor(Color.WHITE); setHintTextColor(0xFF5A6270.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(PANEL2, 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, _, _ -> connectManual(); true }
        }
        col.addView(addressIn)
        col.addView(button("Connect", RED) { connectManual() }, lp(top = 10))

        col.addView(TextView(this).apply {
            text = "Tip: Volume Down = AUTO, Volume Up = CUT while the mixer is open."
            setTextColor(DIM); textSize = 12f; setPadding(0, dp(28), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "Gaz Media Production · Everything Multimedia"
            setTextColor(0xFF5A6270.toInt()); textSize = 12f; setPadding(0, dp(6), 0, 0)
        })
        return ScrollView(this).apply { addView(col); isFillViewport = true }
    }

    private fun showMixers(found: List<Mixer>) {
        listBox.removeAllViews()
        for (m in found) {
            listBox.addView(button("${m.name}\n${m.host}:${m.port}", PANEL) { open(m) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }, lp(bottom = 8))
        }
    }

    private fun connectManual() {
        var a = addressIn.text.toString().trim().removePrefix("http://").removeSuffix("/")
        if (a.isEmpty()) return
        prefs.edit().putString("manual", a).apply()
        val port = a.substringAfter(":", "4466").toIntOrNull() ?: 4466
        a = a.substringBefore(":")
        open(Mixer(a, a, port))
    }

    /* ------------------------------------------------------------ discovery */

    private fun scan(autoConnect: Boolean) {
        if (scanning) return
        scanning = true
        status.text = "Looking for OBS on this Wi-Fi…"
        listBox.removeAllViews()
        thread(name = "gaz-discovery") {
            val found = discover()
            ui.post {
                scanning = false
                if (isFinishing) return@post
                if (found.isEmpty()) {
                    status.text = "No OBS mixer found. Check that OBS is open with the Gaz Live Mixer plugin, " +
                        "and that this phone is on the same Wi-Fi as the PC."
                } else {
                    status.text = if (found.size == 1) "Found 1 OBS mixer:" else "Found ${found.size} OBS mixers:"
                    showMixers(found)
                    val last = prefs.getString("last", null)
                    val again = found.firstOrNull { it.url == last } ?: found.singleOrNull()
                    if (autoConnect && again != null && web == null) open(again)
                }
            }
        }
    }

    private fun discover(): List<Mixer> {
        val results = LinkedHashMap<String, Mixer>()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("gazmixer").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 400
                val msg = "GAZMIXER_DISCOVER".toByteArray()
                val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))
                subnetBroadcast(wifi)?.let { targets.add(it) }
                val until = System.currentTimeMillis() + 1800
                var nextSend = 0L
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < until) {
                    if (System.currentTimeMillis() >= nextSend) {
                        for (t in targets) runCatching { sock.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT)) }
                        nextSend = System.currentTimeMillis() + 600
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val j = JSONObject(String(p.data, 0, p.length))
                        if (j.optString("app") == "gaz-live-mixer") {
                            val host = p.address.hostAddress ?: continue
                            val m = Mixer(j.optString("name", host), host, j.optInt("port", 4466))
                            results[m.url] = m
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock.release() }
        }
        return results.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(wifi: WifiManager): InetAddress? = runCatching {
        val d = wifi.dhcpInfo ?: return null
        if (d.ipAddress == 0) return null
        val mask = if (d.netmask != 0) d.netmask else 0x00FFFFFF
        val b = (d.ipAddress and mask) or mask.inv()
        InetAddress.getByAddress(ByteArray(4) { i -> (b shr (i * 8) and 0xFF).toByte() })
    }.getOrNull()

    /* ------------------------------------------------------------ mixer view */

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(m: Mixer) {
        prefs.edit().putString("last", m.url).apply()
        web?.let { root.removeView(it); it.destroy() }
        val w = WebView(this).apply {
            setBackgroundColor(BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "GazAndroid")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) ui.post {
                        closeMixer()
                        status.text = "Could not open ${m.host}:${m.port} (${err.description}). Is OBS running?"
                    }
                }
            }
            loadUrl(m.url)
        }
        web = w
        root.addView(w, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        picker.visibility = View.GONE
        immersive(true)
    }

    private fun closeMixer() {
        web?.let { root.removeView(it); it.destroy() }
        web = null
        picker.visibility = View.VISIBLE
        immersive(false)
    }

    inner class Bridge {
        @JavascriptInterface
        fun vibrate(ms: Int) {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(10, 200).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val w = web
        if (w != null && event.repeatCount == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { w.evaluateJavascript("window.gazMixer&&gazMixer.auto()", null); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { w.evaluateJavascript("window.gazMixer&&gazMixer.cut()", null); return true }
            }
        }
        if (w != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web != null) {
            closeMixer()
            scan(autoConnect = false)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        if (web != null) immersive(true)
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun immersive(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            else View.SYSTEM_UI_FLAG_VISIBLE
            return
        }
        val c = window.insetsController ?: return
        if (on) {
            c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    /* ------------------------------------------------------------ helpers */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color, 12)
        stateListAnimator = null
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    companion object {
        const val DISCOVERY_PORT = 4467
        val BG = 0xFF0B0D10.toInt()
        val PANEL = 0xFF14171C.toInt()
        val PANEL2 = 0xFF1B1F26.toInt()
        val RED = 0xFFFF3B30.toInt()
        val DIM = 0xFF8B94A3.toInt()
    }
}
