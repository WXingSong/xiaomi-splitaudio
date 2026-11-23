package app.splitaudio.adb

/** 纯逻辑：引导页每一步的完成状态、开机/切网/打开无线调试后的重连策略（有 JVM 单元测试）。
 *  全程不依赖「USB 调试（安全设置）」与 WRITE_SECURE_SETTINGS，仅需普通「无线调试」一次性配对并完成首次连接激活。 */
object SetupLogic {
    data class Inputs(
        val devOptions: Boolean,     // 开发者选项已开
        val wifi: Boolean,           // 目前连着 Wi-Fi
        val wirelessDebug: Boolean,  // 无线调试目前是开的
        val paired: Boolean,         // 已配对（本机记录）
        val ignited: Boolean,        // 已成功连接并激活底层引擎（免安全设置）
        val secureGranted: Boolean,  // 可选：本机若已持有 WRITE_SECURE_SETTINGS 则支持自动开关无线调试
        val connected: Boolean,      // ADB 目前连着
    )

    /** 四步各自是否完成。第 2 步：配对后无线调试重启或手动关闭不能倒退，所以 paired 也算完成。 */
    fun steps(i: Inputs): List<Boolean> {
        val s1 = i.devOptions || i.paired
        val s2 = i.wirelessDebug || i.paired
        val s3 = i.paired
        val s4 = i.paired && (i.ignited || i.secureGranted || i.connected)
        return listOf(s1, s2, s3, s4)
    }

    /** 目前该做的步骤（0 起算）；全完成＝-1 */
    fun current(i: Inputs): Int = steps(i).indexOfFirst { !it }

    enum class Action { NONE, CONNECT, ENABLE_WD_THEN_CONNECT, NOTIFY_ENABLE_WD }

    /** 启动/Wi-Fi 恢复/打开无线调试时要做何种操作。routeRunning＝路由守护进程已在跑（走本机控制口，无需 ADB）。 */
    fun reconnect(i: Inputs, routeRunning: Boolean): Action = when {
        !i.paired || !i.wifi -> Action.NONE
        routeRunning || i.connected -> Action.NONE
        i.wirelessDebug -> Action.CONNECT
        i.secureGranted -> Action.ENABLE_WD_THEN_CONNECT
        else -> Action.NOTIFY_ENABLE_WD
    }

    /** 配对码：只收 6 位数字 */
    fun validCode(s: String): Boolean = s.length == 6 && s.all { it in '0'..'9' }
}
