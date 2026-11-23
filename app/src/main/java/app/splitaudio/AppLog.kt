package app.splitaudio

/** 轻量内存运行日志（仅存最近 [MAX] 行，进程结束即清空，仅用于本地状态查看与 logcat）。 */
object AppLog {
    const val MAX = 100
    const val DEFAULT_TAIL = 20
    private val lines = ArrayDeque<String>()

    /** 纯逻辑：按本地时区格式化为 HH:MM:SS 前缀的单行日志 */
    fun format(nowMs: Long, msg: String): String {
        val s = (nowMs / 1000 + java.util.TimeZone.getDefault().getOffset(nowMs) / 1000).mod(86400L)
        return "%02d:%02d:%02d %s".format(s / 3600, s / 60 % 60, s % 60, msg.replace('\n', ' ').take(200))
    }

    @Synchronized fun add(msg: String, nowMs: Long = System.currentTimeMillis()) {
        lines.addLast(format(nowMs, msg))
        while (lines.size > MAX) lines.removeFirst()
        try { android.util.Log.i("SplitAudio", msg.take(200)) } catch (_: Throwable) {}
    }

    @Synchronized fun tail(n: Int = DEFAULT_TAIL): List<String> = lines.toList().takeLast(n)
    @Synchronized fun clear() = lines.clear()
}
