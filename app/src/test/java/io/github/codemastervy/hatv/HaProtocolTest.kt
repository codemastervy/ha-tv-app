package io.github.codemastervy.hatv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaProtocolTest {
    @Test fun authFlowMessages() {
        assertEquals(listOf(HaMessage.AuthRequired), HaProtocol.parse("""{"type":"auth_required","ha_version":"2026.10.1"}"""))
        assertEquals(listOf(HaMessage.AuthOk("2026.10.1")), HaProtocol.parse("""{"type":"auth_ok","ha_version":"2026.10.1"}"""))
        assertEquals(listOf(HaMessage.AuthInvalid("Invalid access token or password")),
            HaProtocol.parse("""{"type":"auth_invalid","message":"Invalid access token or password"}"""))
    }

    @Test fun authMessageShape() {
        val o = JSONObject(HaProtocol.auth("abc"))
        assertEquals("auth", o.getString("type"))
        assertEquals("abc", o.getString("access_token"))
    }

    @Test fun subscribeTriggerShape() {
        val o = JSONObject(HaProtocol.subscribeTrigger(3, listOf("binary_sensor.door_person", "camera.door")))
        assertEquals(3, o.getInt("id"))
        assertEquals("subscribe_trigger", o.getString("type"))
        val trig = o.getJSONObject("trigger")
        assertEquals("state", trig.getString("platform"))
        assertEquals("binary_sensor.door_person", trig.getJSONArray("entity_id").getString(0))
    }

    @Test fun triggerEventGivesToState() {
        val msg = """{"id":1,"type":"event","event":{"variables":{"trigger":{"id":"0","idx":"0","platform":"state",
            "entity_id":"binary_sensor.door_person",
            "from_state":{"entity_id":"binary_sensor.door_person","state":"off","attributes":{}},
            "to_state":{"entity_id":"binary_sensor.door_person","state":"on","attributes":{"device_class":"occupancy"}}}},
            "context":null}}"""
        assertEquals(listOf(HaMessage.Trigger(1, EntityState("binary_sensor.door_person", "on", null, null, "occupancy"))), HaProtocol.parse(msg))
    }

    @Test fun cameraTokenAttribute() {
        val msg = """{"id":1,"type":"event","event":{"variables":{"trigger":{"to_state":
            {"entity_id":"camera.door","state":"streaming","attributes":{"access_token":"tok123","friendly_name":"Entry"}}}}}}"""
        val t = HaProtocol.parse(msg).single() as HaMessage.Trigger
        assertEquals("tok123", t.toState?.accessToken)
        assertEquals("Entry", t.toState?.friendlyName)
    }

    @Test fun getStatesResult() {
        val msg = """{"id":2,"type":"result","success":true,"result":[
            {"entity_id":"light.kitchen","state":"on","attributes":{}},
            {"entity_id":"binary_sensor.door_person","state":"off","attributes":{}},
            {"entity_id":"camera.door","state":"idle","attributes":{"access_token":"xyz"}}]}"""
        val r = HaProtocol.parse(msg).single() as HaMessage.Result
        assertEquals(2, r.id)
        assertTrue(r.success)
        assertEquals(3, r.states!!.size)
        assertEquals("xyz", r.states!!.first { it.entityId == "camera.door" }.accessToken)
    }

    @Test fun subscribeResultHasNoStates() {
        val r = HaProtocol.parse("""{"id":1,"type":"result","success":true,"result":null}""").single() as HaMessage.Result
        assertNull(r.states)
    }

    @Test fun errorResult() {
        val r = HaProtocol.parse("""{"id":5,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""")
            .single() as HaMessage.Result
        assertEquals("Unknown command.", r.error)
    }

    @Test fun coalescedArrayAndGarbage() {
        assertEquals(listOf(HaMessage.Pong, HaMessage.AuthRequired),
            HaProtocol.parse("""[{"id":9,"type":"pong"},{"type":"auth_required"}]"""))
        assertEquals(listOf(HaMessage.Other), HaProtocol.parse("not json"))
        assertEquals(listOf(HaMessage.Other), HaProtocol.parse("""{"type":"something_new"}"""))
    }

    @Test fun doorSensorHeuristic() {
        assertTrue(EntityState("binary_sensor.front_person", "off", null).looksLikeDoorSensor)
        assertTrue(EntityState("binary_sensor.porch", "off", null, "Porch", "occupancy").looksLikeDoorSensor)
        assertTrue(EntityState("binary_sensor.x", "off", null, "Front Doorbell").looksLikeDoorSensor)
        assertFalse(EntityState("binary_sensor.plant_pothos_due", "off", null, "Plant pothos due").looksLikeDoorSensor)
    }
}
