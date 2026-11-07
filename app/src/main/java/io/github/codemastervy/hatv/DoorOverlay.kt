package io.github.codemastervy.hatv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The top-right "someone at the door" window drawn over whatever is playing. */
class DoorOverlay(private val context: Context, private val onOpen: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { L.i("Pop-up auto-closed"); dismiss() }
    private var root: View? = null
    private var web: WebView? = null
    private var pulse: ValueAnimator? = null

    val isShowing get() = root != null

    fun show(baseUrl: String, titleText: String, autoCloseSeconds: Int, streamUrl: String?, placeholder: String) {
        if (isShowing) return
        val shownAt = SystemClock.elapsedRealtime()
        val dp = context.resources.displayMetrics.density
        val screenW = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
                      else context.resources.displayMetrics.widthPixels
        val width = (screenW * 0.30f).toInt()
        val border = (4 * dp).toInt()
        val videoH = (width - 2 * border) * 9 / 16
        val red = Color.parseColor("#E53935")

        val dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(red) }
        }
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        val title = TextView(context).apply {
            text = titleText
            setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        }
        val hint = TextView(context).apply {
            text = "OK = open HA TV   ·   Back = close"; setTextColor(Color.parseColor("#B0B0B0")); textSize = 12f; maxLines = 1
        }
        val pad = (10 * dp).toInt()
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt()).apply { marginEnd = pad })
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        hint.setPadding((22 * dp).toInt(), (2 * dp).toInt(), 0, 0)   // line up under the title text
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C1C"))
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
            addView(titleRow)
            addView(hint)
        }
        val webView = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false              // keys must stay with the frame, not the WebView
            isFocusableInTouchMode = false
            settings.javaScriptEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            if (BuildConfig.DEBUG) webChromeClient = object : WebChromeClient() {
                // Debug builds log how long the first camera frame took (latency check).
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().startsWith("hatv:"))
                        L.i("Pop-up ${m.message().removePrefix("hatv:")} after ${SystemClock.elapsedRealtime() - shownAt} ms")
                    return true
                }
            }
            loadDataWithBaseURL("$baseUrl/", Js.cameraPage(streamUrl, placeholder), "text/html", "utf-8", null)
        }
        val innerRadius = 12 * dp
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, innerRadius)
            }
            clipToOutline = true
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, videoH))
        }
        val frame = KeyFrame(context, ::onKey).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = 16 * dp; setStroke(border, red)
            }
            setPadding(border, border, border, border)
            addView(column)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // No FLAG_NOT_FOCUSABLE: the pop-up takes key focus so OK/Back from the remote reach it.
        // The app underneath only loses window focus; video keeps playing.
        val lp = WindowManager.LayoutParams(
            width, WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (32 * dp).toInt(); y = (32 * dp).toInt()
            setTitle("HA TV front door")
        }
        try {
            wm.addView(frame, lp)
        } catch (e: Exception) {
            L.w("Could not add overlay window", e)
            pulse?.cancel(); webView.destroy(); return
        }
        frame.requestFocus()
        root = frame
        web = webView
        main.postDelayed(autoClose, autoCloseSeconds * 1000L)
    }

    fun updateStream(streamUrl: String) {
        web?.evaluateJavascript(Js.setCameraSrc(streamUrl), null)
    }

    fun dismiss() {
        main.removeCallbacks(autoClose)
        pulse?.cancel(); pulse = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        web?.apply { stopLoading(); destroy() }   // stops the MJPEG stream
        web = null
    }

    private fun onKey(code: Int) {
        when {
            code == Keys.BACK -> { L.i("Pop-up closed with Back"); dismiss() }
            Keys.isOk(code) -> { L.i("Pop-up OK -> opening HA TV"); dismiss(); onOpen() }
        }
    }

    /**
     * Swallows Back/OK/D-pad (acting on key UP, and only if we also saw the DOWN, so an OK pressed
     * in YouTube just before the pop-up appeared can't open the app). Everything else, e.g. volume,
     * falls through to the system.
     */
    private class KeyFrame(ctx: Context, private val onKeyUp: (Int) -> Unit) : FrameLayout(ctx) {
        private val downs = mutableSetOf<Int>()
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val code = event.keyCode
            val ours = code == Keys.BACK || Keys.isOk(code) ||
                code in Keys.DPAD_UP..Keys.DPAD_RIGHT
            if (!ours) return super.dispatchKeyEvent(event)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> downs += code
                KeyEvent.ACTION_UP -> if (downs.remove(code) && !event.isCanceled) onKeyUp(code)
            }
            return true
        }
    }
}
