package io.github.codemastervy.hatv

import java.net.URI
import java.net.URLEncoder

object Urls {
    fun normalizeBase(url: String): String = url.trim().trimEnd('/')

    fun join(base: String, path: String): String {
        val p = path.trim()
        return when {
            p.isEmpty() -> base
            p.startsWith("/") -> base + p
            else -> "$base/$p"
        }
    }

    fun webSocket(base: String): String {
        val b = normalizeBase(base)
        val ws = when {
            b.startsWith("https://", ignoreCase = true) -> "wss://" + b.substring(8)
            b.startsWith("http://", ignoreCase = true) -> "ws://" + b.substring(7)
            else -> b
        }
        return "$ws/api/websocket"
    }

    fun cameraStream(base: String, entityId: String, accessToken: String): String =
        "$base/api/camera_proxy_stream/$entityId?token=" + URLEncoder.encode(accessToken, "UTF-8")

    fun isLoginPage(url: String?): Boolean = url?.contains("/auth/authorize") == true

    fun host(url: String): String? = try { URI(url.trim()).host } catch (e: Exception) { null }

    fun isValidBase(url: String): Boolean {
        val u = url.trim().lowercase()
        return (u.startsWith("http://") || u.startsWith("https://")) && host(url) != null
    }

    fun isCleartext(url: String): Boolean = url.trim().lowercase().startsWith("http://")
}
