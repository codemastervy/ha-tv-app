package io.github.codemastervy.hatv

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class EntityState(
    val entityId: String,
    val state: String,
    val accessToken: String?,          // camera.* entities: rotating token for camera_proxy URLs
    val friendlyName: String? = null,
    val deviceClass: String? = null,
) {
    /** Likely a person / doorbell / motion sensor: listed first in the setup picker. */
    val looksLikeDoorSensor: Boolean get() {
        val text = (entityId + " " + (friendlyName ?: "")).lowercase()
        return deviceClass in setOf("occupancy", "motion", "presence") ||
            listOf("person", "doorbell", "visitor", "motion", "people").any { it in text }
    }
}

sealed interface HaMessage {
    data object AuthRequired : HaMessage
    data class AuthOk(val haVersion: String?) : HaMessage
    data class AuthInvalid(val message: String) : HaMessage
    data class Result(val id: Int, val success: Boolean, val states: List<EntityState>?, val error: String?) : HaMessage
    data class Trigger(val id: Int, val toState: EntityState?) : HaMessage
    data object Pong : HaMessage
    data object Other : HaMessage
}

/** Home Assistant WebSocket API messages (https://developers.home-assistant.io/docs/api/websocket). */
object HaProtocol {
    fun auth(token: String): String =
        JSONObject().put("type", "auth").put("access_token", token).toString()

    /** State trigger with no "to": fires on every state AND attribute change of these entities. */
    fun subscribeTrigger(id: Int, entityIds: List<String>): String = JSONObject()
        .put("id", id)
        .put("type", "subscribe_trigger")
        .put("trigger", JSONObject().put("platform", "state").put("entity_id", JSONArray(entityIds)))
        .toString()

    fun getStates(id: Int): String = JSONObject().put("id", id).put("type", "get_states").toString()

    fun ping(id: Int): String = JSONObject().put("id", id).put("type", "ping").toString()

    /** HA may send a single object or (with coalescing) an array of them. */
    fun parse(text: String): List<HaMessage> = try {
        val t = text.trimStart()
        if (t.startsWith("[")) {
            val arr = JSONArray(t)
            (0 until arr.length()).map { parseOne(arr.optJSONObject(it)) }
        } else listOf(parseOne(JSONObject(t)))
    } catch (e: JSONException) {
        listOf(HaMessage.Other)
    }

    private fun parseOne(o: JSONObject?): HaMessage {
        if (o == null) return HaMessage.Other
        return when (o.optString("type")) {
            "auth_required" -> HaMessage.AuthRequired
            "auth_ok" -> HaMessage.AuthOk(o.optString("ha_version").ifEmpty { null })
            "auth_invalid" -> HaMessage.AuthInvalid(o.optString("message", "Invalid token"))
            "pong" -> HaMessage.Pong
            "result" -> {
                val states = (o.opt("result") as? JSONArray)?.let { arr ->
                    (0 until arr.length()).mapNotNull { parseState(arr.optJSONObject(it)) }
                }
                val err = o.optJSONObject("error")?.optString("message")
                HaMessage.Result(o.optInt("id", -1), o.optBoolean("success", false), states, err)
            }
            "event" -> {
                val trigger = o.optJSONObject("event")?.optJSONObject("variables")?.optJSONObject("trigger")
                if (trigger == null) HaMessage.Other
                else HaMessage.Trigger(o.optInt("id", -1), parseState(trigger.optJSONObject("to_state")))
            }
            else -> HaMessage.Other
        }
    }

    fun parseState(o: JSONObject?): EntityState? {
        if (o == null) return null
        val id = o.optString("entity_id").ifEmpty { return null }
        val attrs = o.optJSONObject("attributes")
        val token = attrs?.optString("access_token")?.ifEmpty { null }
        val name = attrs?.optString("friendly_name")?.ifEmpty { null }
        val deviceClass = attrs?.optString("device_class")?.ifEmpty { null }
        return EntityState(id, o.optString("state"), token, name, deviceClass)
    }
}
