package app.splitaudio

import org.junit.Assert.*
import org.junit.Test

/** 断开目标音箱自动停服配置（SplitPrefs）的纯逻辑单元测试 */
class SplitPrefsTest {
    @Test fun sanitizeMinutes_clampsToValidRange() {
        assertEquals(0, SplitPrefs.sanitizeMinutes(-10))
        assertEquals(0, SplitPrefs.sanitizeMinutes(0))
        assertEquals(2, SplitPrefs.sanitizeMinutes(2))
        assertEquals(1440, SplitPrefs.sanitizeMinutes(1440))
        assertEquals(1440, SplitPrefs.sanitizeMinutes(9999))
    }

    @Test fun shouldAutoStop_respectsDisabledAndThreshold() {
        assertFalse(SplitPrefs.shouldAutoStop(0, 999_999_999L))
        assertFalse(SplitPrefs.shouldAutoStop(2, 119_999L))
        assertTrue(SplitPrefs.shouldAutoStop(2, 120_000L))
        assertTrue(SplitPrefs.shouldAutoStop(5, 300_001L))
    }

    @Test fun idleFileText_writesSingleIntegerLine() {
        assertEquals("0\n", SplitPrefs.idleFileText(0))
        assertEquals("10\n", SplitPrefs.idleFileText(10))
        assertEquals("0\n", SplitPrefs.idleFileText(-1))
    }

    @Test fun sanitizeOverlayMode_fallsBackToRightOnUnknown() {
        assertEquals(SplitPrefs.OVERLAY_OFF, SplitPrefs.sanitizeOverlayMode(SplitPrefs.OVERLAY_OFF))
        assertEquals(SplitPrefs.OVERLAY_RIGHT, SplitPrefs.sanitizeOverlayMode(SplitPrefs.OVERLAY_RIGHT))
        assertEquals(SplitPrefs.OVERLAY_LEFT, SplitPrefs.sanitizeOverlayMode(SplitPrefs.OVERLAY_LEFT))
        assertEquals(SplitPrefs.OVERLAY_RIGHT, SplitPrefs.sanitizeOverlayMode(-1))
        assertEquals(SplitPrefs.OVERLAY_RIGHT, SplitPrefs.sanitizeOverlayMode(99))
    }
}
