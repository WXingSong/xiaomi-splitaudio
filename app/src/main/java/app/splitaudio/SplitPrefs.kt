package app.splitaudio

import android.content.Context

/** 分流总开关、断开自动停服与音量键伴生浮条偏好（SharedPreferences "split_prefs"）。 */
object SplitPrefs {
    const val DEFAULT_AUTO_STOP_MIN = 2
    const val MAX_AUTO_STOP_MIN = 1440

    /** 音量键伴生浮条位置模式：0=关闭，1=右侧并排（默认，紧邻系统音量条），2=左侧边缘 */
    const val OVERLAY_OFF = 0
    const val OVERLAY_RIGHT = 1
    const val OVERLAY_LEFT = 2

    private const val KEY_ACTIVE = "active"
    private const val KEY_AUTO_STOP = "auto_stop_min"
    private const val KEY_OVERLAY_MODE = "companion_overlay_mode"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("split_prefs", Context.MODE_PRIVATE)

    fun isActive(c: Context): Boolean = sp(c).getBoolean(KEY_ACTIVE, false)

    fun setActive(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(KEY_ACTIVE, on).commit()
    }

    fun autoStopMinutes(c: Context): Int = sanitizeMinutes(sp(c).getInt(KEY_AUTO_STOP, DEFAULT_AUTO_STOP_MIN))

    fun setAutoStopMinutes(c: Context, minutes: Int) {
        sp(c).edit().putInt(KEY_AUTO_STOP, sanitizeMinutes(minutes)).apply()
    }

    fun overlayMode(c: Context): Int = sanitizeOverlayMode(sp(c).getInt(KEY_OVERLAY_MODE, OVERLAY_RIGHT))

    fun setOverlayMode(c: Context, mode: Int) {
        sp(c).edit().putInt(KEY_OVERLAY_MODE, sanitizeOverlayMode(mode)).apply()
    }

    fun sanitizeOverlayMode(mode: Int): Int = when (mode) {
        OVERLAY_OFF, OVERLAY_RIGHT, OVERLAY_LEFT -> mode
        else -> OVERLAY_RIGHT
    }

    fun sanitizeMinutes(minutes: Int): Int = minutes.coerceIn(0, MAX_AUTO_STOP_MIN)

    fun shouldAutoStop(autoStopMinutes: Int, disconnectedMs: Long): Boolean =
        autoStopMinutes > 0 && disconnectedMs >= autoStopMinutes * 60_000L

    fun idleFileText(minutes: Int): String = "${sanitizeMinutes(minutes)}\n"
}
