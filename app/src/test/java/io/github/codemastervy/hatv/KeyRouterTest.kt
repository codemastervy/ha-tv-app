package io.github.codemastervy.hatv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRouterTest {
    private val r = KeyRouter(longPressMs = 5_000)

    @Test fun dpadAndOkPassToPage() {
        for (code in listOf(Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT)) {
            assertEquals(KeyAction.PASS, r.route(code, true, 0, 0, 0))
            assertEquals(KeyAction.PASS, r.route(code, false, 0, 0, 0))
        }
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_CENTER, true, 0, 100, 100))
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_CENTER, false, 0, 200, 100))
        assertEquals(KeyAction.PASS, r.route(Keys.ENTER, true, 0, 0, 0))
    }

    @Test fun dpadRepeatsStillPass() {
        // Holding an arrow should keep moving the focus ring.
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_RIGHT, true, 5, 900, 0))
    }

    @Test fun backBecomesBackActionOnceAndNeverReachesPage() {
        assertEquals(KeyAction.BACK, r.route(Keys.BACK, true, 0, 0, 0))
        assertEquals(KeyAction.CONSUME, r.route(Keys.BACK, true, 1, 500, 0))
        assertEquals(KeyAction.CONSUME, r.route(Keys.BACK, false, 0, 600, 0))
    }

    @Test fun holdingOkFiveSecondsOpensSetupOnce() {
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_CENTER, true, 0, 0, 0))
        assertEquals(KeyAction.CONSUME, r.route(Keys.DPAD_CENTER, true, 1, 500, 0))
        assertEquals(KeyAction.CONSUME, r.route(Keys.DPAD_CENTER, true, 40, 4_999, 0))
        assertEquals(KeyAction.OPEN_SETUP, r.route(Keys.DPAD_CENTER, true, 41, 5_000, 0))
        assertEquals(KeyAction.CONSUME, r.route(Keys.DPAD_CENTER, true, 42, 5_100, 0))
        assertEquals(KeyAction.CONSUME, r.route(Keys.DPAD_CENTER, false, 0, 5_200, 0))
        // Next normal press works again.
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_CENTER, true, 0, 9_000, 9_000))
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_CENTER, false, 0, 9_100, 9_000))
    }

    @Test fun timerLongPressWithoutRepeats() {
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_CENTER, true, 0, 0, 0))
        assertTrue(r.onLongPressTimer())
        assertFalse(r.onLongPressTimer())                       // only once per press
        assertEquals(KeyAction.CONSUME, r.route(Keys.DPAD_CENTER, true, 60, 6_000, 0)) // late repeat: no 2nd open
        assertEquals(KeyAction.CONSUME, r.route(Keys.DPAD_CENTER, false, 0, 6_100, 0))  // up swallowed
        assertEquals(KeyAction.PASS, r.route(Keys.DPAD_CENTER, true, 0, 9_000, 9_000))  // next press normal
    }

    @Test fun shortHoldDoesNotOpenSetup() {
        r.route(Keys.ENTER, true, 0, 0, 0)
        assertEquals(KeyAction.CONSUME, r.route(Keys.ENTER, true, 10, 3_000, 0))
        assertEquals(KeyAction.PASS, r.route(Keys.ENTER, false, 0, 3_100, 0))
    }

    @Test fun menuAndSettingsOpenSetup() {
        assertEquals(KeyAction.OPEN_SETUP, r.route(Keys.MENU, true, 0, 0, 0))
        assertEquals(KeyAction.CONSUME, r.route(Keys.MENU, false, 0, 0, 0))
        assertEquals(KeyAction.OPEN_SETUP, r.route(Keys.SETTINGS, true, 0, 0, 0))
    }

    @Test fun keysMatchAndroidValues() {
        // Guard against typos in our copied constants.
        assertEquals(android.view.KeyEvent.KEYCODE_BACK, Keys.BACK)
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_CENTER, Keys.DPAD_CENTER)
        assertEquals(android.view.KeyEvent.KEYCODE_ENTER, Keys.ENTER)
        assertEquals(android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, Keys.NUMPAD_ENTER)
        assertEquals(android.view.KeyEvent.KEYCODE_MENU, Keys.MENU)
        assertEquals(android.view.KeyEvent.KEYCODE_SETTINGS, Keys.SETTINGS)
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_UP, Keys.DPAD_UP)
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, Keys.DPAD_RIGHT)
    }
}

class BackPressTrackerTest {
    @Test fun singlePressDoesNotExit() = assertFalse(BackPressTracker().onBack(1_000))

    @Test fun secondPressWithin1500msExits() {
        val t = BackPressTracker()
        assertFalse(t.onBack(1_000))
        assertTrue(t.onBack(2_500))
    }

    @Test fun secondPressAfter1500msDoesNotExit() {
        val t = BackPressTracker()
        assertFalse(t.onBack(1_000))
        assertFalse(t.onBack(2_501))
        assertTrue(t.onBack(3_000)) // but that press started a new window
    }

    @Test fun afterExitWindowResets() {
        val t = BackPressTracker()
        t.onBack(0); assertTrue(t.onBack(100))
        assertFalse(t.onBack(200))
    }
}
