package io.github.codemastervy.hatv

/** Key codes we care about (same values as android.view.KeyEvent, copied so this stays JVM-testable). */
object Keys {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val ENTER = 66
    const val MENU = 82
    const val NUMPAD_ENTER = 160
    const val SETTINGS = 176

    fun isOk(code: Int) = code == DPAD_CENTER || code == ENTER || code == NUMPAD_ENTER
}

enum class KeyAction { PASS, CONSUME, BACK, OPEN_SETUP }

/**
 * Decides what the dashboard does with each remote key.
 * - D-pad and OK go to the WebView (Chromium maps DPAD_* to Arrow* and DPAD_CENTER to Enter).
 * - Back never reaches the page as a raw key; the Activity turns it into an Escape keydown instead,
 *   otherwise the card could see both keyCode 4 and our Escape and go back twice.
 * - Holding OK for [longPressMs] opens setup. Auto-repeat OK events are swallowed so a held OK
 *   doesn't press the focused tile 20 times on the way there.
 * - Menu / Settings key opens setup.
 */
class KeyRouter(private val longPressMs: Long = 5_000) {
    private var setupFiredForPress = false

    fun route(code: Int, isDown: Boolean, repeatCount: Int, eventTimeMs: Long, downTimeMs: Long): KeyAction {
        return when {
            code == Keys.BACK -> if (isDown && repeatCount == 0) KeyAction.BACK else KeyAction.CONSUME
            code == Keys.MENU || code == Keys.SETTINGS ->
                if (isDown && repeatCount == 0) KeyAction.OPEN_SETUP else KeyAction.CONSUME
            Keys.isOk(code) -> routeOk(isDown, repeatCount, eventTimeMs - downTimeMs)
            else -> KeyAction.PASS
        }
    }

    /**
     * Called by the Activity's 5 s timer (started on OK down, cancelled on OK up). Many remotes and
     * injected events send no auto-repeat while held, so the timer is the main long-press path.
     * @return true if setup should open now (not already opened for this press).
     */
    fun onLongPressTimer(): Boolean {
        if (setupFiredForPress) return false
        setupFiredForPress = true
        return true
    }

    private fun routeOk(isDown: Boolean, repeatCount: Int, heldMs: Long): KeyAction {
        if (isDown && repeatCount == 0) {
            setupFiredForPress = false
            return KeyAction.PASS
        }
        if (isDown) {
            if (!setupFiredForPress && heldMs >= longPressMs) {
                setupFiredForPress = true
                return KeyAction.OPEN_SETUP
            }
            return KeyAction.CONSUME
        }
        // Key up: pass it so the page sees a normal keydown/keyup pair, unless it ended a long press.
        val fired = setupFiredForPress
        setupFiredForPress = false
        return if (fired) KeyAction.CONSUME else KeyAction.PASS
    }
}

/** "Press Back again to exit": true when this press is the second within [windowMs]. */
class BackPressTracker(private val windowMs: Long = 1_500) {
    private var lastPress: Long? = null

    fun onBack(nowMs: Long): Boolean {
        val prev = lastPress
        val exit = prev != null && nowMs - prev <= windowMs
        lastPress = if (exit) null else nowMs
        return exit
    }
}
