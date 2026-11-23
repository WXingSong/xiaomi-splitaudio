package app.splitaudio

import android.content.Context
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import app.splitaudio.adb.Adb
import app.splitaudio.adb.AdbBackend
import app.splitaudio.adb.PlaySetup
import app.splitaudio.adb.Reconnect

/** 统一融合版配置：Shizuku 优先，内置无线 ADB 兜底；全部功能永久开放。 */
object Dist {
    /** 后端优先级：优先 Shizuku，未授权时回退内置无线 ADB */
    fun backends(): List<Backend> = listOf(ShizukuBackend, AdbBackend)

    /** MonitorService / PairService 前台服务类型（与 AndroidManifest 保持一致） */
    const val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    /** 主界面的一次性无线 ADB / Shizuku 引导区块 */
    fun setupSection(a: MainActivity, parent: LinearLayout): SetupSection = PlaySetup(a, parent)

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var wdObs: ContentObserver? = null

    /** MonitorService 开启运行时监听 Wi-Fi 恢复与系统「无线调试」开关变化，按需自动点火（关闭分流时立即解绑，零常驻） */
    fun onServiceCreate(s: MonitorService) {
        onServiceDestroy(s)
        val app = s.applicationContext
        try {
            val cm = s.getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!SplitPrefs.isActive(app)) return
                    Thread {
                        Thread.sleep(3000)
                        if (SplitPrefs.isActive(app)) Reconnect.run(app, "wifi")
                    }.start()
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb
            )
            netCb = cb
        } catch (_: Throwable) {}
        try {
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    if (!SplitPrefs.isActive(app)) return
                    if (Adb.wirelessDebugOn()) {
                        MonitorService.onWirelessDebugEnabled(app)
                    }
                }
            }
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, obs)
            wdObs = obs
        } catch (_: Throwable) {}
        Thread {
            if (SplitPrefs.isActive(app)) Reconnect.run(app, "start")
        }.start()
    }

    fun onServiceDestroy(s: MonitorService) {
        val cb = netCb
        netCb = null
        if (cb != null) {
            try { s.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
        val obs = wdObs
        wdObs = null
        if (obs != null) {
            try { s.applicationContext.contentResolver.unregisterContentObserver(obs) } catch (_: Throwable) {}
        }
    }

    fun needSetupText(c: Context): String = c.getString(R.string.s_need_pair)

    fun startResultText(c: Context, r: String): String = when {
        r.contains("no_wifi") -> c.getString(R.string.s_need_wifi_init)
        r.contains("wd_off") -> c.getString(R.string.s_need_wd_init)
        r.contains("no_adb") -> c.getString(R.string.s_no_adb)
        else -> c.getString(R.string.s_result, r)
    }
}
