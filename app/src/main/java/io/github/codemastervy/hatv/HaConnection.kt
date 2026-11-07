package io.github.codemastervy.hatv

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)      // long-lived socket; liveness comes from pings
            .pingInterval(30, TimeUnit.SECONDS)    // WebSocket ping every 30 s; a missed pong fails the socket
            .build()
    }
}

/**
 * Keeps one authenticated HA WebSocket open, reconnecting with backoff (2 s -> 60 s).
 * All callbacks run on the main thread.
 */
class HaConnection(
    private val baseUrl: String,
    private val token: String,
    private val personEntity: String,
    private val cameraEntity: String,   // may be blank: pop-up without a picture
    private val listener: Listener,
) {
    private val entities = listOf(personEntity, cameraEntity).filter { it.isNotBlank() }

    interface Listener {
        fun onStatus(text: String)
        fun onPersonState(state: String)
        fun onCameraToken(accessToken: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val backoff = Backoff()
    private val reconnect = Runnable { connect() }
    private var socket: WebSocket? = null
    private var generation = 0
    private var running = false
    private var authed = false
    private var nextId = 1
    private var triggerId = -1
    private var statesId = -1

    fun start() { running = true; connect() }

    fun stop() {
        running = false
        generation++
        main.removeCallbacks(reconnect)
        socket?.close(1000, null)
        socket = null
    }

    /** Network came back: don't wait for the backoff timer. */
    fun onNetworkAvailable() {
        // Skip if connected or a connect is already in flight (the callback also fires right after registering).
        if (!running || socket != null) return
        main.removeCallbacks(reconnect)
        backoff.reset()
        connect()
    }

    /** TV woke from standby: the old socket may be silently dead, so open a fresh one now. */
    fun onScreenOn() {
        if (!running) return
        L.i("Screen on; reconnecting now")
        main.removeCallbacks(reconnect)
        backoff.reset()
        connect()
    }

    private fun connect() {
        socket?.cancel()
        val gen = ++generation
        authed = false
        nextId = 1
        L.i("Connecting to ${Urls.webSocket(baseUrl)}")
        val request = try {
            Request.Builder().url(Urls.webSocket(baseUrl)).build()
        } catch (e: IllegalArgumentException) {
            listener.onStatus("Bad Home Assistant URL"); return
        }
        socket = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (gen == generation) handle(webSocket, text) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (gen == generation) dropped("closed ($code)") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (gen == generation) dropped("${t.javaClass.simpleName}: ${t.message}") }
            }
        })
    }

    private fun dropped(why: String) {
        authed = false
        socket = null
        if (!running) return
        val delay = backoff.next()
        L.w("WebSocket dropped: $why; retrying in ${delay}s")
        listener.onStatus("Reconnecting to Home Assistant…")
        main.postDelayed(reconnect, delay * 1000)
    }

    private fun handle(ws: WebSocket, text: String) {
        for (msg in HaProtocol.parse(text)) when (msg) {
            HaMessage.AuthRequired -> ws.send(HaProtocol.auth(token))
            is HaMessage.AuthOk -> {
                authed = true
                backoff.reset()
                L.i("Authenticated (HA ${msg.haVersion})")
                listener.onStatus("Connected to Home Assistant")
                // Subscribe first, then snapshot, so no change can fall between the two.
                triggerId = nextId++
                ws.send(HaProtocol.subscribeTrigger(triggerId, entities))
                statesId = nextId++
                ws.send(HaProtocol.getStates(statesId))
            }
            is HaMessage.AuthInvalid -> {
                L.w("Token rejected by Home Assistant: ${msg.message}")
                listener.onStatus("Token rejected - open HA TV setup")
                backoff.toMax()
                ws.close(1000, null)
            }
            is HaMessage.Result -> when {
                msg.id == statesId && msg.states != null ->
                    msg.states.filter { it.entityId in entities }.forEach(::apply)
                !msg.success -> L.w("HA error for request ${msg.id}: ${msg.error}")
            }
            is HaMessage.Trigger -> if (msg.id == triggerId) msg.toState?.let(::apply)
            else -> Unit
        }
    }

    private fun apply(s: EntityState) = when (s.entityId) {
        personEntity -> listener.onPersonState(s.state)
        cameraEntity -> listener.onCameraToken(s.accessToken)
        else -> Unit
    }
}

/** One-shot connection used by the setup screen: "Test connection" and the entity pickers. */
object HaOneShot {
    /** Authenticates; if [wantStates], also returns all entity states. [done] runs on the main thread. */
    fun run(baseUrl: String, token: String, wantStates: Boolean, done: (ok: Boolean, message: String, states: List<EntityState>?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(ok: Boolean, message: String, states: List<EntityState>? = null) = main.post {
            if (!finished) { finished = true; done(ok, Redact.redact(message), states) }
        }
        val request = try {
            Request.Builder().url(Urls.webSocket(baseUrl)).build()
        } catch (e: IllegalArgumentException) {
            finish(false, "That URL doesn't look right"); return
        }
        val ws = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                for (msg in HaProtocol.parse(text)) when (msg) {
                    HaMessage.AuthRequired -> webSocket.send(HaProtocol.auth(token))
                    is HaMessage.AuthOk -> {
                        val okText = "OK - connected to Home Assistant ${msg.haVersion ?: ""}".trim()
                        if (wantStates) webSocket.send(HaProtocol.getStates(1))
                        else { finish(true, okText); webSocket.close(1000, null) }
                    }
                    is HaMessage.AuthInvalid -> {
                        finish(false, "Home Assistant rejected the token (${msg.message})")
                        webSocket.close(1000, null)
                    }
                    is HaMessage.Result -> if (msg.id == 1) {
                        if (msg.states != null) finish(true, "OK", msg.states)
                        else finish(false, "Couldn't read entities: ${msg.error}")
                        webSocket.close(1000, null)
                    }
                    else -> Unit
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(false, "Can't reach Home Assistant: ${t.message ?: t.javaClass.simpleName}")
            }
        })
        main.postDelayed({ if (!finished) { ws.cancel(); finish(false, "Timed out after 10 s") } }, 10_000)
    }
}
