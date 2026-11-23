package app.splitaudio

import org.junit.Assert.assertEquals
import org.junit.Test

/** 小米控制中心风格音量条与开关纯数学逻辑单元测试 */
class HyperControlMathTest {
    @Test fun xToPercent_mapsBoundsAndMidpointCleanly() {
        assertEquals(0, HyperControlMath.xToPercent(0f, 320, 10f))
        assertEquals(0, HyperControlMath.xToPercent(10f, 320, 10f))
        assertEquals(50, HyperControlMath.xToPercent(160f, 320, 10f))
        assertEquals(100, HyperControlMath.xToPercent(310f, 320, 10f))
        assertEquals(100, HyperControlMath.xToPercent(400f, 320, 10f))
        assertEquals(0, HyperControlMath.xToPercent(50f, 0, 10f))
    }

    @Test fun yToPercent_mapsTopToHundredAndBottomToZero() {
        assertEquals(100, HyperControlMath.yToPercent(0f, 220, 10f))
        assertEquals(100, HyperControlMath.yToPercent(10f, 220, 10f))
        assertEquals(50, HyperControlMath.yToPercent(110f, 220, 10f))
        assertEquals(0, HyperControlMath.yToPercent(210f, 220, 10f))
        assertEquals(0, HyperControlMath.yToPercent(260f, 220, 10f))
        assertEquals(0, HyperControlMath.yToPercent(50f, 0, 10f))
    }

    @Test fun toggleMute_remembersAndRestoresNonZeroVolume() {
        assertEquals(Pair(0, 65), HyperControlMath.toggleMute(65, 40))
        assertEquals(Pair(65, 65), HyperControlMath.toggleMute(0, 65))
        // 上次音量过低时恢复至少 10%
        assertEquals(Pair(10, 10), HyperControlMath.toggleMute(0, 3))
        assertEquals(Pair(100, 100), HyperControlMath.toggleMute(0, 150))
    }

    @Test fun stepVolume_clampsZeroToHundred() {
        assertEquals(55, HyperControlMath.stepVolume(50, 5))
        assertEquals(100, HyperControlMath.stepVolume(98, 5))
        assertEquals(0, HyperControlMath.stepVolume(2, -5))
    }

    @Test fun lerpColor_interpolatesEndpointsAndHalfway() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, HyperControlMath.lerpColor(black, white, -0.5f))
        assertEquals(white, HyperControlMath.lerpColor(black, white, 1.5f))
        assertEquals(0xFF808080.toInt(), HyperControlMath.lerpColor(black, white, 0.5f))
    }
}
