package io.github.codemastervy.hatv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionGateTest {
    private val g = DetectionGate(quietMs = 60_000)

    @Test fun alreadyOnAtStartDoesNotShow() {
        assertFalse(g.onPersonState("on", 0))
    }

    @Test fun offThenOnShowsOnce() {
        assertFalse(g.onPersonState("off", 0))
        assertTrue(g.onPersonState("on", 1_000))
        assertFalse(g.onPersonState("on", 2_000))   // repeated "on" (attribute change) doesn't re-show
    }

    @Test fun flickeringSensorIsOneVisit() {
        // Exactly what the Reolink sensor did during the walk-past test: on/off every few seconds.
        g.onPersonState("off", 0)
        assertTrue(g.onPersonState("on", 32_800))
        var t = 32_800L
        repeat(10) {
            t += 5_000; assertFalse(g.onPersonState("off", t))
            t += 1_000; assertFalse(g.onPersonState("on", t))
        }
    }

    @Test fun newVisitAfterQuietTime() {
        g.onPersonState("off", 0)
        assertTrue(g.onPersonState("on", 1_000))
        g.onPersonState("off", 10_000)
        assertFalse(g.onPersonState("on", 69_999))   // off for 59.999 s: same visit
        g.onPersonState("off", 70_000)
        assertTrue(g.onPersonState("on", 130_000))   // off for exactly 60 s: new visit
    }

    @Test fun firstDetectionAfterServiceStartShowsImmediately() {
        // The sensor has been off for ages; the service just started. First walk-past must show, no waiting.
        g.onPersonState("off", 5_000_000)
        assertTrue(g.onPersonState("on", 5_000_100))
    }

    @Test fun staleOnAtStartThenQuietThenNewVisit() {
        assertFalse(g.onPersonState("on", 0))
        g.onPersonState("off", 1_000)
        assertFalse(g.onPersonState("on", 30_000))
        g.onPersonState("off", 31_000)
        assertTrue(g.onPersonState("on", 91_000))
    }

    @Test fun unavailableDoesNotEndOrStartAVisit() {
        g.onPersonState("off", 0)
        assertTrue(g.onPersonState("on", 1_000))
        assertFalse(g.onPersonState("unavailable", 2_000))
        assertFalse(g.onPersonState("on", 3_000))
    }

    @Test fun zeroQuietMeansEveryOffOnShows() {
        val z = DetectionGate(quietMs = 0)
        z.onPersonState("off", 0)
        assertTrue(z.onPersonState("on", 1))
        z.onPersonState("off", 2)
        assertTrue(z.onPersonState("on", 3))
    }

    @Test fun debugBlipThenResetAllowsRealDetectionImmediately() {
        g.onPersonState("off", 0)
        assertTrue(g.onPersonState("on", 1_000))     // debug blip "on"
        g.onPersonState("off", 1_000)                // debug blip "off"
        assertFalse(g.onPersonState("on", 5_000))    // within quiet time: same visit
        g.reset()
        assertTrue(g.onPersonState("on", 6_000))
    }

    @Test fun resetBeforeAnyStateArms() {
        g.reset()
        assertTrue(g.onPersonState("on", 0))
    }
}

class BackoffTest {
    @Test fun doublesToSixtyAndResets() {
        val b = Backoff()
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 60L, 60L), List(7) { b.next() })
        b.reset(); assertEquals(2L, b.next())
        b.toMax(); assertEquals(60L, b.next())
    }
}

class RedactTest {
    private val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJhYmMifQ.c2lnbmF0dXJlX2hlcmU"

    @Test fun stripsTokenQuery() {
        val s = Redact.redact("GET http://192.0.2.10:8123/api/camera_proxy_stream/camera.door?token=a1b2c3&x=1 failed")
        assertFalse(s.contains("a1b2c3"))
        assertTrue(s.contains("?token=***&x=1"))
    }

    @Test fun stripsJsonTokensBearerAndJwt() {
        val s = Redact.redact("""{"type":"auth","access_token":"secret99"} Authorization: Bearer abc.def-ghi $jwt""")
        assertFalse(s.contains("secret99"))
        assertFalse(s.contains("abc.def-ghi"))
        assertFalse(s.contains(jwt))
    }

    @Test fun leavesNormalTextAlone() {
        val s = "Connecting to ws://192.0.2.10:8123/api/websocket"
        assertEquals(s, Redact.redact(s))
    }

    @Test fun maskNeverShowsCharacters() {
        val m = Redact.mask(jwt)
        assertTrue(m.isNotEmpty())
        assertTrue(m.none { it in jwt })
        assertEquals("", Redact.mask(null))
    }
}

class UrlsAndJsTest {
    @Test fun urlHelpers() {
        assertEquals("http://192.0.2.10:8123", Urls.normalizeBase(" http://192.0.2.10:8123/ "))
        assertEquals("http://h:8123/dashboard-tv/tv?kiosk", Urls.join("http://h:8123", "/dashboard-tv/tv?kiosk"))
        assertEquals("http://h:8123/x", Urls.join("http://h:8123", "x"))
        assertEquals("ws://192.0.2.10:8123/api/websocket", Urls.webSocket("http://192.0.2.10:8123/"))
        assertEquals("wss://ha.example/api/websocket", Urls.webSocket("https://ha.example"))
        assertEquals("192.0.2.10", Urls.host("http://192.0.2.10:8123"))
        assertTrue(Urls.isLoginPage("http://h/auth/authorize?response_type=code"))
        assertFalse(Urls.isLoginPage("http://h/dashboard-tv/tv"))
        assertFalse(Urls.isValidBase("192.0.2.10:8123"))
        assertTrue(Urls.isValidBase("http://192.0.2.10:8123"))
        assertEquals("http://h/api/camera_proxy_stream/camera.door?token=a%2Bb",
            Urls.cameraStream("http://h", "camera.door", "a+b"))
    }

    @Test fun hassTokensShape() {
        val now = 1_700_000_000_000L
        val o = Js.hassTokens("http://h:8123", "TOK", now)
        assertEquals("TOK", o.getString("access_token"))
        assertEquals("Bearer", o.getString("token_type"))
        assertEquals(1800, o.getInt("expires_in"))
        assertEquals("http://h:8123", o.getString("hassUrl"))
        assertEquals("http://h:8123/", o.getString("clientId"))
        assertEquals("", o.getString("refresh_token"))
        assertTrue(o.getLong("expires") >= now + 10L * 365 * 24 * 3600 * 1000)
    }

    @Test fun storeTokensEscapesSafely() {
        val js = Js.storeTokens("http://h", "a'b\"c</script>", 0)
        // the token appears only JSON-escaped inside the object literal
        assertTrue(js.contains(JSONObject.quote("a'b\"c</script>")))
        assertTrue(js.startsWith("(function(){"))
    }
}
