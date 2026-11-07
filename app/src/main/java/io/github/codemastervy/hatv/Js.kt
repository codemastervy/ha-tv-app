package io.github.codemastervy.hatv

import org.json.JSONObject

/** JavaScript snippets run inside WebViews. */
object Js {
    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

    /** The object HA's frontend stores after a normal login. Writing it ourselves skips the login page. */
    fun hassTokens(baseUrl: String, token: String, nowMs: Long): JSONObject = JSONObject()
        .put("access_token", token)
        .put("token_type", "Bearer")
        .put("expires_in", 1800)
        .put("hassUrl", baseUrl)
        .put("clientId", "$baseUrl/")
        .put("expires", nowMs + TEN_YEARS_MS)
        .put("refresh_token", "")

    /** Returns 'ok' on success. The JSON is embedded as a JS object literal (valid JSON is valid JS). */
    fun storeTokens(baseUrl: String, token: String, nowMs: Long): String =
        "(function(){try{localStorage.setItem('hassTokens',JSON.stringify(" +
            hassTokens(baseUrl, token, nowMs) + "));return 'ok';}catch(e){return 'err';}})()"

    /** Hide browser focus outlines and any cursor; the card draws its own focus ring. */
    const val HIDE_FOCUS_CSS =
        "(function(){if(document.getElementById('hatv-css'))return;" +
            "var s=document.createElement('style');s.id='hatv-css';" +
            "s.textContent='*:focus{outline:none!important}*{cursor:none!important}';" +
            "(document.head||document.documentElement).appendChild(s);})()"

    /**
     * Back key -> Escape keydown. Dispatched ONCE on the document: the card's window capture listener
     * still sees it (window is on the event path), dispatching on window too would fire it twice.
     * keyCode/which are forced with defineProperty because KeyboardEvent's init dict ignores them.
     */
    const val ESCAPE =
        "(function(){var e=new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27," +
            "bubbles:true,cancelable:true,composed:true});" +
            "try{Object.defineProperty(e,'keyCode',{get:function(){return 27}});" +
            "Object.defineProperty(e,'which',{get:function(){return 27}});}catch(x){}" +
            "document.dispatchEvent(e);})()"

    const val BLANK_PAGE = "<!doctype html><html><body style=\"background:#000\"></body></html>"

    /** Pop-up page: the MJPEG stream as an <img>; retries with the latest URL if the stream breaks. */
    fun cameraPage(streamUrl: String?, placeholder: String = "Connecting to camera…"): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
        #m{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#888;font:16px sans-serif}
        img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}</style></head>
        <body><div id="m">${placeholder.replace("<", "&lt;")}</div><img id="cam" alt="">
        <script>
        console.log('hatv:page-ready');
        var latest=null,first=true,img=document.getElementById('cam');
        img.onload=function(){if(first){first=false;console.log('hatv:first-frame');}};
        img.onerror=function(){setTimeout(function(){if(latest)img.src=latest+'&r='+Date.now();},2000);};
        function setSrc(u){latest=u;img.src=u;}
        ${if (streamUrl != null) "setSrc(" + JSONObject.quote(streamUrl) + ");" else ""}
        </script></body></html>
    """.trimIndent()

    fun setCameraSrc(streamUrl: String): String = "setSrc(" + JSONObject.quote(streamUrl) + ")"
}
