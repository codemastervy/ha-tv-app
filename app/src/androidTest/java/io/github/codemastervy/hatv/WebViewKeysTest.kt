package io.github.codemastervy.hatv

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewKeysTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    private fun ActivityScenario<KeyProbeActivity>.js(script: String): String {
        val latch = CountDownLatch(1); var out = ""
        onActivity { a -> a.web.evaluateJavascript(script) { out = it; latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    @Test fun dpadAndOkReachThePageAsArrowAndEnter() {
        ActivityScenario.launch(KeyProbeActivity::class.java).use { s ->
            var act: KeyProbeActivity? = null
            s.onActivity { act = it }
            act!!.loaded.await(10, TimeUnit.SECONDS)
            Thread.sleep(500)
            for (k in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)) {
                instr.sendKeyDownUpSync(k)
            }
            instr.waitForIdleSync(); Thread.sleep(300)
            assertEquals("\"ArrowUp:38,ArrowDown:40,ArrowLeft:37,ArrowRight:39,Enter:13\"", s.js("window.keys.join(',')"))
        }
    }

    @Test fun escapeSnippetFiresOnceWithKeyCode27() {
        ActivityScenario.launch(KeyProbeActivity::class.java).use { s ->
            var act: KeyProbeActivity? = null
            s.onActivity { act = it }
            act!!.loaded.await(10, TimeUnit.SECONDS)
            s.js(Js.ESCAPE)
            assertEquals("\"Escape:27\"", s.js("window.keys.join(',')"))
        }
    }
}
