package app.splitaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import app.splitaudio.adb.Adb
import app.splitaudio.adb.Reconnect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 按需启停的分流监控前台服务：
 *  - 开启分流时：优先 10ms 唤醒休眠中的 AppRoute（无 Wi-Fi 秒开）；若检测到 APK 内置 approute.dex 已更新且当前有 Wi-Fi/Shizuku，自动热替换升级；
 *  - 日常停止或目标音箱断开超过设定分钟数：发送 pause 让 AppRoute 撤销规则进入 0 CPU 休眠待命，同时退出本前台服务并熄灭快捷磁贴；
 *  - 高级区「彻底停止底层服务」：发送 shutdown 彻底结束 AppRoute 进程，回到 0 进程占用。 */
class MonitorService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastTry = 0L
    private var disconnectedSince = 0L
    @Volatile private var state = ""
    private lateinit var am: AudioManager
    private var binderHandle: Any? = null
    private var overlayCtrl: SpeakerOverlayController? = null
    private var up = false

    private val btRun = Runnable { check() }
    private val startRun = Runnable { check() }
    private val shizukuRun = Runnable { check() }
    private val tickRun = object : Runnable {
        override fun run() {
            if (!SplitPrefs.isActive(this@MonitorService)) { stopSelf(); return }
            check()
            h.postDelayed(this, TICK_MS)
        }
    }
    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>?) { rearm(btRun, 1500) }
        override fun onAudioDevicesRemoved(r: Array<out AudioDeviceInfo>?) { rearm(btRun, 1500) }
    }

    private fun rearm(r: Runnable, delayMs: Long) { h.removeCallbacks(r); h.postDelayed(r, delayMs) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!SplitPrefs.isActive(this)) { stopSelf(); return }
        state = getString(R.string.s_starting)
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!goForeground()) {
            SplitPrefs.setActive(this, false)
            SplitTileService.requestUpdate(this)
            stopSelf()
            return
        }
        isServiceRunning = true
        isRouteOn = false
        currentStateText = state
        disconnectedSince = System.currentTimeMillis()
        am.registerAudioDeviceCallback(deviceCb, h)
        binderHandle = ShizukuBackend.addBinderListener { rearm(shizukuRun, 0) }
        overlayCtrl = SpeakerOverlayController(this).also {
            it.attach()
            activeOverlay = it
        }
        Dist.onServiceCreate(this)
        rearm(tickRun, TICK_MS)
        up = true
        SplitTileService.requestUpdate(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayCtrl?.onConfigurationChanged()
    }

    private fun goForeground(): Boolean = try {
        startForeground(1, notif(), Dist.fgsType)
        true
    } catch (e: Exception) {
        AppLog.add("monitor: foreground start refused (${e.javaClass.simpleName})")
        false
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (::am.isInitialized) {
            try { am.unregisterAudioDeviceCallback(deviceCb) } catch (_: Throwable) {}
        }
        ShizukuBackend.removeBinderListener(binderHandle); binderHandle = null
        if (activeOverlay === overlayCtrl) activeOverlay = null
        overlayCtrl?.detach(); overlayCtrl = null
        Dist.onServiceDestroy(this)
        worker.shutdownNow()
        isServiceRunning = false
        isRouteOn = false
        if (!SplitPrefs.isActive(this)) {
            currentStateText = ""
        }
        SplitTileService.requestUpdate(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(applicationContext)
            return START_NOT_STICKY
        }
        if (!SplitPrefs.isActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!up) return START_NOT_STICKY
        if (intent?.action == ACTION_WD_ENABLED) {
            lastTry = 0L
            rearm(startRun, 1800)
            return START_NOT_STICKY
        }
        rearm(startRun, 0)
        return START_NOT_STICKY
    }

    private fun submit(block: () -> Unit) {
        try {
            if (!worker.isShutdown) worker.execute(block)
        } catch (_: Throwable) {}
    }

    private fun check() {
        if (!SplitPrefs.isActive(this)) { stopSelf(); return }
        submit {
            if (!SplitPrefs.isActive(this)) return@submit
            val status = BtFilter.inspect(this, am)
            val now = System.currentTimeMillis()
            when (status) {
                is BtFilter.Status.None -> {
                    val on = Route.ruleOn(this)
                    if (on) {
                        disconnectedSince = 0L
                        markRouteActive(true)
                        setState(getString(R.string.s_split))
                    } else {
                        markRouteActive(false)
                        handleDisconnected(now, getString(R.string.s_wait_target))
                    }
                }
                is BtFilter.Status.Other -> {
                    val on = Route.ruleOn(this)
                    markRouteActive(false)
                    if (on) {
                        setState(getString(R.string.s_bt_other))
                        h.postDelayed(btRun, 2500)
                    } else {
                        handleDisconnected(now, getString(R.string.s_bt_other))
                    }
                }
                is BtFilter.Status.Matched, is BtFilter.Status.NeedsRouterCheck -> {
                    if (status is BtFilter.Status.Matched) disconnectedSince = 0L
                    ensureRouterActive(status is BtFilter.Status.NeedsRouterCheck, now)
                }
            }
        }
    }

    private fun markRouteActive(on: Boolean) {
        val prev = isRouteOn
        isRouteOn = on
        if (on && (!prev || Route.lastKnownSpeakerVol == null)) {
            Route.speakerVolGet(this)
        } else if (!on && prev) {
            overlayCtrl?.dismissNow()
        }
    }

    private fun handleDisconnected(now: Long, idleText: String) {
        if (disconnectedSince == 0L) disconnectedSince = now
        val limit = SplitPrefs.autoStopMinutes(this)
        if (SplitPrefs.shouldAutoStop(limit, now - disconnectedSince)) {
            AppLog.add("monitor: target bt gone ${limit}min -> auto pause & stop service")
            stopInternal(applicationContext)
            return
        }
        setState(idleText)
    }

    private fun ensureRouterActive(needsRouterCheck: Boolean, now: Long) {
        var st = Route.ask(this, "state")
        // 底层运行中/休眠中且版本与当前 APK 一致（或离线不可升级）：走 10ms 本机快速唤醒/复用
        if (st == "paused" && !Route.shouldUpgradeRunning(this)) {
            Route.syncConfigViaControl(this)
            if (Route.resume(this)) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
                st = Route.ask(this, "state")
            }
        }
        val needHotUpgrade = Route.isAlive(st) && Route.shouldUpgradeRunning(this) && (now - lastTry >= RETRY_COOLDOWN_MS)
        if (!needHotUpgrade) {
            if (st == "on") {
                disconnectedSince = 0L
                markRouteActive(true)
                setState(getString(R.string.s_split))
                return
            }
            if (st == "off") {
                markRouteActive(false)
                if (needsRouterCheck) handleDisconnected(now, getString(R.string.s_bt_other))
                else setState(getString(R.string.s_router_wait))
                return
            }
        }
        markRouteActive(false)
        if (Backends.current() == null) {
            setState(Dist.needSetupText(this))
            return
        }
        // 底层未运行或需要热升级新版 approute.dex：内置无线 ADB 仅需普通无线调试（免「USB 调试（安全设置）」）
        var usedWirelessAdb = false
        if (!ShizukuBackend.granted() && Adb.paired() && !Adb.connected()) {
            if (!Reconnect.wifiUp(this)) {
                setState(getString(R.string.s_need_wifi_init))
                return
            }
            if (!Adb.wirelessDebugOn() && !Adb.secureGranted()) {
                setState(getString(R.string.s_need_wd_init))
                return
            }
            if (now - lastTry < RETRY_COOLDOWN_MS) return
            lastTry = now
            setState(getString(R.string.s_connecting_adb))
            val adbOk = Adb.ensure(12_000)
            if (!adbOk) {
                lastTry = System.currentTimeMillis()
                val errRes = when (Adb.lastError) {
                    "no_wifi" -> R.string.s_need_wifi_init
                    "wd_off" -> R.string.s_need_wd_init
                    else -> R.string.s_no_adb
                }
                setState(getString(errRes))
                return
            }
            usedWirelessAdb = true
        } else {
            if (now - lastTry < RETRY_COOLDOWN_MS) return
            lastTry = now
            usedWirelessAdb = !ShizukuBackend.granted() && Adb.connected()
        }
        val r = Route.start(this)
        lastTry = System.currentTimeMillis()
        try { Thread.sleep(2200) } catch (_: InterruptedException) { return }
        if (!SplitPrefs.isActive(this)) return
        val after = Route.ask(this, "state")
        if (after == "on" || after == "off") {
            if (usedWirelessAdb) {
                Adb.setIgnited(true)
                if (!Adb.secureGranted() && Adb.wirelessDebugOn()) {
                    h.post {
                        Toast.makeText(applicationContext, R.string.toast_adb_ok_close_wd, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        if (after == "on") {
            disconnectedSince = 0L
            markRouteActive(true)
            setState(getString(R.string.s_split))
        } else if (after == "off") {
            markRouteActive(false)
            if (needsRouterCheck) handleDisconnected(System.currentTimeMillis(), getString(R.string.s_bt_other))
            else setState(getString(R.string.s_router_wait))
        } else {
            markRouteActive(false)
            setState(Dist.startResultText(this, r))
        }
    }

    private fun setState(s: String) {
        if (s == state) return
        state = s
        currentStateText = s
        AppLog.add("state: $s")
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notif()) } catch (_: Exception) {}
        SplitTileService.requestUpdate(applicationContext)
    }

    private fun notif(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("route", getString(R.string.ch_route), NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "route")
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(getString(R.string.notif_title, state))
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, getString(R.string.btn_stop_short), stopPi).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.splitaudio.ACTION_STOP"
        const val ACTION_WD_ENABLED = "app.splitaudio.ACTION_WD_ENABLED"
        private const val TICK_MS = 15_000L
        private const val RETRY_COOLDOWN_MS = 30_000L

        @Volatile var isServiceRunning = false
        @Volatile var isRouteOn = false
        @Volatile var currentStateText = ""
        @Volatile private var activeOverlay: SpeakerOverlayController? = null

        fun previewOverlay(): Boolean {
            val ctrl = activeOverlay ?: return false
            ctrl.showPreview()
            return true
        }

        fun dismissOverlay() {
            activeOverlay?.dismissNow()
        }

        /** 系统「无线调试」刚打开时立即重置冷却并唤醒点火（重启后免安全设置自动激活） */
        fun onWirelessDebugEnabled(c: Context) {
            val app = c.applicationContext
            if (!SplitPrefs.isActive(app) || !isServiceRunning) return
            try {
                app.startService(Intent(app, MonitorService::class.java).setAction(ACTION_WD_ENABLED))
            } catch (_: Exception) {}
        }

        /** 开启分流服务（下拉磁贴与主界面统一入口） */
        fun start(c: Context) {
            val app = c.applicationContext
            SplitPrefs.setActive(app, true)
            currentStateText = app.getString(R.string.s_starting)
            SplitTileService.requestUpdate(app)
            try {
                app.startForegroundService(Intent(app, MonitorService::class.java))
            } catch (e: Exception) {
                AppLog.add("monitor start fail: ${e.javaClass.simpleName}")
                SplitPrefs.setActive(app, false)
                isRouteOn = false
                currentStateText = ""
                SplitTileService.requestUpdate(app)
            }
        }

        /** 日常停止：立即撤销分流规则并让 AppRoute 进入 0 CPU 休眠待命，停止前台服务与通知，保留无 Wi-Fi 秒开能力 */
        fun stop(c: Context) {
            val app = c.applicationContext
            SplitPrefs.setActive(app, false)
            isRouteOn = false
            currentStateText = ""
            SplitTileService.requestUpdate(app)
            try { app.stopService(Intent(app, MonitorService::class.java)) } catch (_: Exception) {}
            Thread { pauseRouteAndCleanupAdb(app) }.start()
        }

        /** 彻底停止底层服务（含销毁休眠中的 AppRoute 进程，回到绝对 0 进程占用；下次开启需重新点火） */
        fun stopAll(c: Context) {
            val app = c.applicationContext
            SplitPrefs.setActive(app, false)
            isRouteOn = false
            currentStateText = ""
            SplitTileService.requestUpdate(app)
            try { app.stopService(Intent(app, MonitorService::class.java)) } catch (_: Exception) {}
            Thread {
                try { Route.shutdown(app) } catch (_: Throwable) {}
                try { Adb.disableWirelessDebugIfAuto() } catch (_: Throwable) {}
            }.start()
        }

        private fun stopInternal(app: Context) {
            SplitPrefs.setActive(app, false)
            isRouteOn = false
            currentStateText = ""
            SplitTileService.requestUpdate(app)
            pauseRouteAndCleanupAdb(app)
            try { app.stopService(Intent(app, MonitorService::class.java)) } catch (_: Exception) {}
        }

        private fun pauseRouteAndCleanupAdb(app: Context) {
            try {
                if (!Route.pause(app) && Backends.current()?.alive() == true) {
                    Route.stop(app)
                }
            } catch (_: Throwable) {}
            try { Adb.disableWirelessDebugIfAuto() } catch (_: Throwable) {}
        }

        fun kick(c: Context) {
            val app = c.applicationContext
            if (!SplitPrefs.isActive(app)) return
            val i = Intent(app, MonitorService::class.java)
            try {
                if (isServiceRunning) app.startService(i) else app.startForegroundService(i)
            } catch (_: Exception) {
                try { app.startForegroundService(i) } catch (_: Exception) {}
            }
        }
    }
}
