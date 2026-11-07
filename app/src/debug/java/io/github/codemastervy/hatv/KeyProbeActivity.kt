package io.github.codemastervy.hatv

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch

/** DEBUG BUILDS ONLY - used by WebViewKeysTest. */
/** Hosts a WebView with a page that records keydowns the same way tv-home-card listens (window, capture). */
class KeyProbeActivity : Activity() {
    lateinit var web: WebView
    val loaded = CountDownLatch(1)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            isFocusable = true; isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
        }
        setContentView(web)
        web.requestFocus()
        web.loadDataWithBaseURL("http://localhost/", """
            <html><body><script>
            window.keys=[];
            window.addEventListener('keydown',function(e){window.keys.push(e.key+':'+e.keyCode)},true);
            </script></body></html>""".trimIndent(), "text/html", "utf-8", null)
    }
}

