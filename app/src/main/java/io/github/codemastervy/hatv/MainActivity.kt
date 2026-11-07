package io.github.codemastervy.hatv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Full-screen WebView around the HA TV dashboard, driven entirely by the remote. */
class MainActivity : ComponentActivity() {
    private lateinit var settings: HaSettings
    private lateinit var web: WebView
    private lateinit var message: TextView
    private val main = Handler(Looper.getMainLooper())
    private val keys = KeyRouter()
    private val backPresses = BackPressTracker()
    private var backToast: Toast? = null

    private var loadedVersion = -1
    private var primeUrl = ""
    private var priming = false      // the blank same-origin page used to write localStorage is loading
    private var loadFailed = false   // current dashboard load hit a main-frame error
    private var errored = false      // "Reconnecting…" is showing and the 10 s retry is armed
    private var loginBlocked = false // HA showed its login page: token is bad, stop retrying

    private val longPressOk = Runnable { if (keys.onLongPressTimer()) openSetup() }
    private val retry = Runnable { if (errored && !loginBlocked) startLoad() }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            main.post { if (errored && !loginBlocked) { L.i("Network back, reloading"); startLoad() } }
        }
        override fun onLost(n: Network) {
            main.post { L.w("Network lost"); showReconnecting() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = HaSettings(this)
        if (!settings.isConfigured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish(); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        web = createWebView()
        message = TextView(this).apply {
            setBackgroundColor(Color.BLACK)   // opaque: hides Chromium's error page underneath
            setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER
            isFocusable = false; visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        web.requestFocus()

        // Fallback only: on TV, Back normally arrives as a key event and is handled in dispatchKeyEvent.
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        DoorbellService.start(this)
        startLoad()
    }

    override fun onStart() {
        super.onStart()
        if (!::web.isInitialized) return
        AppState.dashboardVisible = true
        web.onResume()
        // Keep the page alive across Home/return; only reload if settings changed or it errored.
        if (settings.version != loadedVersion) startLoad()
    }

    override fun onStop() {
        AppState.dashboardVisible = false
        if (::web.isInitialized) web.onPause()   // pauses camera streams etc. while Netflix is on
        super.onStop()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            AppState.dashboardVisible = false
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
            main.removeCallbacksAndMessages(null)
            web.destroy()
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::web.isInitialized) return super.dispatchKeyEvent(event)
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (Keys.isOk(event.keyCode)) {
            if (isDown && event.repeatCount == 0) main.postDelayed(longPressOk, 5_000)
            else if (!isDown) main.removeCallbacks(longPressOk)
        }
        return when (keys.route(event.keyCode, isDown, event.repeatCount, event.eventTime, event.downTime)) {
            KeyAction.PASS -> {
                if (!web.hasFocus()) web.requestFocus()
                super.dispatchKeyEvent(event)
            }
            KeyAction.CONSUME -> true
            KeyAction.BACK -> { handleBack(); true }
            KeyAction.OPEN_SETUP -> { openSetup(); true }
        }
    }

    private fun handleBack() {
        if (backPresses.onBack(SystemClock.uptimeMillis())) {
            backToast?.cancel()
            finish(); return
        }
        web.evaluateJavascript(Js.ESCAPE, null)
        backToast?.cancel()
        backToast = Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    // ---- Loading -------------------------------------------------------------------------------

    /**
     * Step 1: load a blank page *with the HA origin* so we can write HA's localStorage.
     * Step 2 (onPageFinished): write "hassTokens", then load the real dashboard.
     * Done on every start in case HA cleared the stored tokens.
     */
    private fun startLoad() {
        main.removeCallbacks(retry)
        loadedVersion = settings.version
        loginBlocked = false
        loadFailed = false
        priming = true
        primeUrl = settings.baseUrl + "/"
        web.loadDataWithBaseURL(primeUrl, Js.BLANK_PAGE, "text/html", "utf-8", null)
    }

    private fun injectTokensThenLoad() {
        val token = settings.token ?: return openSetup()
        web.evaluateJavascript(Js.storeTokens(settings.baseUrl, token, System.currentTimeMillis())) { result ->
            if (result?.contains("ok") == true) {
                L.i("Stored hassTokens, loading dashboard")
                web.loadUrl(settings.dashboardUrl)
            } else {
                L.w("Could not write hassTokens to localStorage")
                showReconnecting()
            }
        }
    }

    private fun showReconnecting() {
        if (loginBlocked) return
        errored = true
        message.text = "Reconnecting to Home Assistant…"
        message.visibility = View.VISIBLE
        main.removeCallbacks(retry)
        main.postDelayed(retry, 10_000)
    }

    private fun hideMessage() {
        errored = false
        main.removeCallbacks(retry)
        message.visibility = View.GONE
    }

    private fun onLoginPage() {
        if (loginBlocked) return
        L.w("Home Assistant showed its login page; token rejected")
        loginBlocked = true
        errored = false
        main.removeCallbacks(retry)
        web.stopLoading()
        web.loadDataWithBaseURL(null, Js.BLANK_PAGE, "text/html", "utf-8", null)
        message.text = "Home Assistant didn't accept the saved token.\n\nOpening setup…"
        message.visibility = View.VISIBLE
        main.postDelayed({ openSetup() }, 4_000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)          // no white flash while loading
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false     // hide Android's own focus highlight around the WebView
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // Note: no cursor/pointer emulation anywhere. Plain WebView never draws a mouse cursor;
        // CSS cursor:none is injected too, in case a TV forwards a stray pointer.
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (Urls.isLoginPage(url)) onLoginPage()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (Urls.isLoginPage(url)) onLoginPage()   // HA is a SPA; catches client-side redirects
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (priming) {
                    if (url == primeUrl) { priming = false; injectTokensThenLoad() }
                    return
                }
                if (loginBlocked) return
                view.evaluateJavascript(Js.HIDE_FOCUS_CSS, null)
                if (!loadFailed) hideMessage()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                L.w("Dashboard load failed: ${error.errorCode} ${error.description}")
                loadFailed = true
                showReconnecting()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.isForMainFrame || response.statusCode < 400) return
                L.w("Dashboard HTTP ${response.statusCode}")
                loadFailed = true
                showReconnecting()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Stay on Home Assistant; ignore links that would leave it (no browser on the TV path).
                return request.url.host != Urls.host(this@MainActivity.settings.baseUrl)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                L.w("WebView renderer gone (crash=${detail.didCrash()}); recreating")
                recreate()
                return true
            }
        }
    }
}
