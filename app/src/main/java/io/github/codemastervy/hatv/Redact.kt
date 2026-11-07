package io.github.codemastervy.hatv

import android.util.Log

/** Removes secrets (tokens, ?token= query values, JWTs) from any text before it is logged. */
object Redact {
    private val rules = listOf(
        Regex("""([?&](?:token|access_token|authSig)=)[^&\s"'#]+""", RegexOption.IGNORE_CASE) to "$1***",
        Regex("""("(?:access_token|refresh_token|token)"\s*:\s*")[^"]*(")""") to "$1***$2",
        Regex("""(Bearer\s+)[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE) to "$1***",
        Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""") to "***",
    )

    fun redact(text: String): String = rules.fold(text) { acc, (re, rep) -> re.replace(acc, rep) }

    /** What the setup screen shows for a saved token: never any of its characters. */
    fun mask(token: String?): String = if (token.isNullOrEmpty()) "" else "••••••••••••"
}

/** App logger. Everything goes through [Redact] so tokens never reach logcat. */
object L {
    private const val TAG = "HaTv"
    fun i(msg: String) { Log.i(TAG, Redact.redact(msg)) }
    fun w(msg: String, t: Throwable? = null) {
        // Throwables are flattened to class + message (also redacted) instead of passing the object,
        // because exception messages from OkHttp/WebView can contain full URLs.
        val extra = t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""
        Log.w(TAG, Redact.redact(msg + extra))
    }
}
