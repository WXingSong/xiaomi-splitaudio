package app.splitaudio.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.splitaudio.AppLog
import app.splitaudio.MonitorService
import app.splitaudio.R
import app.splitaudio.Route
import app.splitaudio.SplitPrefs

/** Wi-Fi 恢复、打开无线调试或首次开启分流时静默自动接回：已配对且底层未运行时按需点火（全程无需「USB 调试（安全设置）」）。 */
object Reconnect {
    @Volatile private var busy = false
    private val main = Handler(Looper.getMainLooper())

    fun wifiUp(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Throwable) { false }

    fun inputs(c: Context) = SetupLogic.Inputs(
        devOptions = Adb.devOptionsOn(), wifi = wifiUp(c), wirelessDebug = Adb.wirelessDebugOn(),
        paired = Adb.paired(), ignited = Adb.ignited(), secureGranted = Adb.secureGranted(), connected = Adb.connected())

    /** 背景线程调用 */
    fun run(c: Context, why: String) {
        if (busy) return
        busy = true
        try {
            val wasAlive = Route.running(c)
            val routeOk = wasAlive && !Route.shouldUpgradeRunning(c)
            val act = SetupLogic.reconnect(inputs(c), routeOk)
            when (act) {
                SetupLogic.Action.NONE -> {}
                SetupLogic.Action.CONNECT, SetupLogic.Action.ENABLE_WD_THEN_CONNECT -> {
                    val ok = Adb.ensure(12_000)
                    if (ok) {
                        if (!Route.running(c) || Route.shouldUpgradeRunning(c)) {
                            Route.start(c, standby = !SplitPrefs.isActive(c))
                        }
                        val nowAlive = Route.running(c)
                        if (nowAlive) {
                            Adb.setIgnited(true)
                            if (!wasAlive && !Adb.secureGranted() && why == "wd_on") {
                                main.post {
                                    Toast.makeText(c.applicationContext, R.string.toast_adb_ok_close_wd, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        if (SplitPrefs.isActive(c)) MonitorService.kick(c)
                    } else {
                        AppLog.add("reconnect($why): adb unavailable (${Adb.lastError})")
                    }
                }
                SetupLogic.Action.NOTIFY_ENABLE_WD ->
                    AppLog.add("reconnect($why): wireless debugging off")
            }
        } finally { busy = false }
    }
}
