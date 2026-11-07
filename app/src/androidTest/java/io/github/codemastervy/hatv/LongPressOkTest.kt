package io.github.codemastervy.hatv

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a configured app (token saved). Holds OK with NO auto-repeat events, like many TV remotes. */
@RunWith(AndroidJUnit4::class)
class LongPressOkTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    private fun hold(ms: Long) {
        val down = SystemClock.uptimeMillis()
        instr.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        Thread.sleep(ms)
        val up = SystemClock.uptimeMillis()
        instr.sendKeySync(KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instr.waitForIdleSync(); Thread.sleep(800)
    }

    private fun resumed(): String {
        var name = ""
        instr.runOnMainSync {
            name = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).firstOrNull()?.javaClass?.simpleName ?: ""
        }
        return name
    }

    @Test fun shortHoldStaysOnDashboard_longHoldOpensSetup() {
        assumeTrue(HaSettings(instr.targetContext).hasToken)
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(6000)
            hold(2000)
            assertEquals("MainActivity", resumed())
            instr.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)   // close whatever the short press opened
            Thread.sleep(2000)
            hold(6000)
            assertEquals("SetupActivity", resumed())
        }
    }
}
