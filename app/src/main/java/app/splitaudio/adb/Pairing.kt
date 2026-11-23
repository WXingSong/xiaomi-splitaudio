package app.splitaudio.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.splitaudio.MainActivity
import app.splitaudio.MonitorService
import app.splitaudio.R
import app.splitaudio.Route
import app.splitaudio.SplitPrefs
import io.github.muntashirakon.adb.android.AdbMdns

/** 无线调试一次性配对流程：
 *  - 搜寻阶段用静默前台通知（NOTIF_ID=2），找到系统配对弹窗瞬间发独立高优消息通知（NOTIF_INPUT_ID=4，MessagingStyle + RemoteInput 直接回复）；
 *  - 若小米 MIUI 通知样式隐藏了输入框，可临时切原生通知样式或用小窗/分屏在 App 内直接输入 6 位码。 */
object Pairing {
    const val KEY_CODE = "pair_code"
    const val NOTIF_ID = 2
    const val NOTIF_INPUT_ID = 4
    const val CH_PAIR_WAIT = "pair_wait"
    const val CH_PAIR = "pair"
    private const val LEGACY_CH_ALERT = "alert"
    private const val LEGACY_ALERT_ID = 3

    enum class St { IDLE, WAITING, FOUND, BUSY, OK, FAIL }
    @Volatile var st = St.IDLE
    @Volatile var host: String? = null
    @Volatile var port = -1
    @Volatile var failReason = ""
    private val main = Handler(Looper.getMainLooper())
    @Volatile var onChange: (() -> Unit)? = null

    private fun changed() { main.post { onChange?.invoke() } }

    fun set(s: St) { st = s; changed() }

    fun onFound(h: String?, p: Int) {
        if (st == St.BUSY || st == St.OK) return
        val app = Adb.app
        if (h != null && p > 0) {
            val isNew = st != St.FOUND || host != h || port != p
            host = h; port = p
            set(St.FOUND)
            if (isNew) {
                notifyInput(app, alertFresh = true)
            }
        } else {
            host = null; port = -1
            if (st == St.FOUND || st == St.FAIL) {
                set(St.WAITING)
                cancelInput(app)
            }
        }
    }

    /** 送出配对码（任何线程调用；内部开后台线程）。done 在后台线程回调：null＝成功，否则原因。 */
    fun submit(ctx: Context, code: String, done: ((String?) -> Unit)? = null) {
        val c = ctx.applicationContext
        if (!SetupLogic.validCode(code)) { done?.invoke("bad_code"); return }
        val h = host; val p = port
        if (h == null || p <= 0) { done?.invoke("no_dialog"); return }
        set(St.BUSY)
        notifyInput(c, alertFresh = false)
        Thread {
            val err = Adb.pair(h, p, code)
            if (err == null) {
                set(St.OK)
                notifyInput(c, alertFresh = false)
                main.post { Toast.makeText(c, R.string.n_pair_ok, Toast.LENGTH_SHORT).show() }
                // 配对成功 → 直接连接并预点火进入 0 CPU 休眠待命（无需「USB 调试（安全设置）」与 pm grant）
                val ok = Adb.ensure(15_000)
                if (ok) {
                    if (!Route.running(c)) Route.start(c, standby = !SplitPrefs.isActive(c))
                    val alive = Route.running(c)
                    if (alive) Adb.setIgnited(true)
                    if (SplitPrefs.isActive(c)) MonitorService.kick(c)
                    val msg = if (alive && !Adb.secureGranted()) R.string.toast_adb_ok_close_wd else R.string.toast_adb_ok
                    main.post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
                }
                c.stopService(Intent(c, PairService::class.java))
                notifyDone(c)
            } else {
                failReason = err
                set(St.FAIL)
                notifyInput(c, alertFresh = true)
            }
            done?.invoke(err)
        }.start()
    }

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val waitCh = NotificationChannel(CH_PAIR_WAIT, c.getString(R.string.ch_pair_wait), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(waitCh)
        nm.createNotificationChannel(NotificationChannel(CH_PAIR, c.getString(R.string.ch_pair), NotificationManager.IMPORTANCE_HIGH))
    }

    /** 清理旧版残留的弹窗提醒通知与渠道，确保不再向用户弹任何提醒通知。 */
    fun clearAlerts(c: Context) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.cancel(LEGACY_ALERT_ID)
            nm.deleteNotificationChannel(LEGACY_CH_ALERT)
        } catch (_: Throwable) {}
    }

    /** 搜索配对窗口期间的静默前台通知（不占用横幅弹窗时机） */
    fun buildSearchNotif(c: Context): Notification {
        channels(c)
        return Notification.Builder(c, CH_PAIR_WAIT)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(c.getString(R.string.n_pair_title))
            .setContentText(c.getString(R.string.n_pair_wait))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    /** 找到系统配对弹窗后发出的高优直接输入通知（MessagingStyle + RemoteInput，提升在小米通知栏的直接回复兼容性） */
    fun buildInputNotif(c: Context, alertFresh: Boolean): Notification {
        channels(c)
        val sender = Person.Builder().setName(c.getString(R.string.n_pair_title)).build()
        val msg = when (st) {
            St.FOUND -> c.getString(R.string.n_pair_enter)
            St.FAIL -> c.getString(R.string.n_pair_fail, failReason)
            St.BUSY -> c.getString(R.string.n_pair_busy)
            St.OK -> c.getString(R.string.n_pair_ok)
            else -> c.getString(R.string.n_pair_wait)
        }
        val b = Notification.Builder(c, CH_PAIR)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(c.getString(R.string.n_pair_title))
            .setContentText(msg)
            .setStyle(Notification.MessagingStyle(sender).addMessage(msg, System.currentTimeMillis(), sender))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(!alertFresh)
        if (st == St.FOUND || st == St.FAIL) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel(c.getString(R.string.n_pair_label)).build()
            val pi = PendingIntent.getBroadcast(
                c, 0,
                Intent(c, PairReceiver::class.java).setAction(PairReceiver.ACT_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(c, android.R.drawable.ic_menu_edit),
                    c.getString(R.string.n_pair_action),
                    pi
                ).addRemoteInput(ri).build()
            )
        }
        b.setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        return b.build()
    }

    fun notifyInput(c: Context, alertFresh: Boolean) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (alertFresh) nm.cancel(NOTIF_INPUT_ID)
            nm.notify(NOTIF_INPUT_ID, buildInputNotif(c, alertFresh))
        } catch (_: Exception) {}
    }

    fun cancelInput(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).cancel(NOTIF_INPUT_ID) } catch (_: Exception) {}
    }

    private fun notifyDone(c: Context) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIF_INPUT_ID)
            nm.cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    fun mdns(c: Context): AdbMdns = AdbMdns(c, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { h, p -> onFound(h?.hostAddress, p) }
}
