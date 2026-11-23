package app.splitaudio.adb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import app.splitaudio.AppLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

/** libadb-android 连接管理：密钥与证书保存在 App 私有目录（filesDir，禁止备份）。 */
class AdbManager(ctx: Context) : AbsAdbConnectionManager() {
    private val priv: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        val kf = File(ctx.filesDir, "adbkey.pk8"); val cf = File(ctx.filesDir, "adbkey.crt")
        var p: PrivateKey? = null; var c: Certificate? = null
        try {
            if (kf.exists() && cf.exists()) {
                p = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                c = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
            }
        } catch (_: Exception) { p = null; c = null }
        if (p == null || c == null) {
            val kp: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            p = kp.private; c = CertGen.selfSigned(kp, "SplitAudio")
            kf.writeBytes(p.encoded); cf.writeBytes(c.encoded)
            Adb.prefs(ctx).edit().putBoolean("paired", false).putBoolean("ignited", false).apply()
        }
        priv = p!!; cert = c
    }

    override fun getPrivateKey(): PrivateKey = priv
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "SplitAudio"
}

/** 本 App 对本机 adbd 的无线调试连接（AdbBackend 封装此对象）。 */
object Adb {
    lateinit var app: Context
    @Volatile private var mgr: AdbManager? = null
    private val connLock = Any()
    private val execLock = Any()
    @Volatile var lastError = ""
    /** 本次无线调试是否由本 App 自动打开（若设备已有 WRITE_SECURE_SETTINGS，点火后将自动关回） */
    @Volatile var autoEnabledWirelessDebug = false

    fun init(c: Context) { app = c.applicationContext }
    fun prefs(c: Context) = c.getSharedPreferences("adb", Context.MODE_PRIVATE)

    fun paired(): Boolean = prefs(app).getBoolean("paired", false)
    fun setPaired(v: Boolean) {
        prefs(app).edit().putBoolean("paired", v).apply {
            if (!v) putBoolean("ignited", false)
        }.apply()
    }
    /** 是否已完成过至少一次无线调试连接点火（免「USB 调试（安全设置）」与 pm grant） */
    fun ignited(): Boolean = prefs(app).getBoolean("ignited", false) || secureGranted()
    fun setIgnited(v: Boolean) { prefs(app).edit().putBoolean("ignited", v).apply() }

    /** 构造直达系统「无线调试」设置页的 Intent（不支持直达时可回退开发者选项） */
    fun wirelessDebugIntent(): Intent =
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.SubSettings"))
            .putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")

    fun openWirelessDebug(ctx: Context) {
        val candidates = arrayOf(
            wirelessDebugIntent(),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in candidates) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {}
        }
    }
    fun manager(): AdbManager = mgr ?: synchronized(connLock) { mgr ?: AdbManager(app).also { mgr = it } }
    fun connected(): Boolean = try { mgr?.isConnected == true } catch (_: Throwable) { false }
    fun secureGranted(): Boolean = app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    fun wirelessDebugOn(): Boolean = try { Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (_: Throwable) { false }
    fun devOptionsOn(): Boolean = try { Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 } catch (_: Throwable) { false }

    /** 持有 WRITE_SECURE_SETTINGS 时自动打开本机「无线调试」 */
    fun enableWirelessDebug(): Boolean = try {
        if (!secureGranted()) false else Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
    } catch (_: Throwable) { false }

    /** 无线调试处于开启但 mDNS 广播休眠（常见于断开重连后）时，通过关闭再打开一次刷新本机 adbd 端口广播 */
    fun refreshWirelessDebug(): Boolean = try {
        if (!secureGranted()) false
        else {
            Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 0)
            Thread.sleep(600)
            val ok = Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
            if (ok) Thread.sleep(2000)
            ok
        }
    } catch (_: Throwable) { false }

    /** 确保已连接本机 adbd（无 Wi-Fi 时 0ms 快速返回；必要时自动打开无线调试；连接失败自动关回本轮打开的无线调试）。后台线程调用。 */
    fun ensure(timeoutMs: Long = 10_000): Boolean = synchronized(connLock) {
        val m = manager()
        if (m.isConnected) return true
        try { m.disconnect() } catch (_: Throwable) {}

        // 无线调试依赖 Wi-Fi：无 Wi-Fi 时 0ms 直接返回，不改动系统开关也不空等 mDNS 超时
        if (!Reconnect.wifiUp(app)) {
            lastError = "no_wifi"
            return false
        }

        val wasOn = wirelessDebugOn()
        var firstTimeout = timeoutMs
        if (!wasOn) {
            if (enableWirelessDebug()) {
                autoEnabledWirelessDebug = true
                AppLog.add("adb: auto-enabled wireless debugging")
                try { Thread.sleep(2000) } catch (_: InterruptedException) {
                    disableWirelessDebugIfAuto()
                    return false
                }
                firstTimeout = timeoutMs + 4000
            } else {
                lastError = "wd_off"
                return false
            }
        } else if (secureGranted()) {
            // 无线调试本就开着且能自动刷新：先快速直连 5 秒，搜不到 mDNS 再自动软刷新一次
            firstTimeout = minOf(timeoutMs, 5000L)
        }

        if (tryConnect(m, firstTimeout)) return true
        if (lastError == "pairing_required") {
            disableWirelessDebugIfAuto()
            return false
        }

        // 首次连接失败且持有 WRITE_SECURE_SETTINGS：自动开关一次无线调试刷新 mDNS 后重试
        if (secureGranted() && Reconnect.wifiUp(app) && refreshWirelessDebug()) {
            AppLog.add("adb: refreshed wireless debugging for mDNS")
            if (tryConnect(m, timeoutMs + 2000)) return true
        }
        // 本轮失败时若由本 App 自动打开过无线调试，立刻关回避免遗留系统调试通知
        disableWirelessDebugIfAuto()
        return false
    }

    private fun tryConnect(m: AdbManager, timeoutMs: Long): Boolean {
        return try {
            m.connectTls(app, timeoutMs)
            if (m.isConnected) { lastError = ""; true } else { lastError = "not_connected"; false }
        } catch (e: AdbPairingRequiredException) {
            lastError = "pairing_required"; setPaired(false); false
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ":" + (e.message ?: "").take(80); false
        }
    }

    fun disconnect() { try { mgr?.disconnect() } catch (_: Throwable) {} }

    /** 断开 ADB；若无线调试原本关闭且由本 App 自动打开，则顺手自动关回以隐藏系统调试通知 */
    fun disableWirelessDebugIfAuto() = synchronized(connLock) {
        disconnect()
        if (!autoEnabledWirelessDebug || !secureGranted()) return@synchronized
        autoEnabledWirelessDebug = false
        try {
            Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 0)
            AppLog.add("adb: auto-disabled wireless debugging")
        } catch (_: Throwable) {}
    }

    /** 底层引擎点火成功后断开 ADB，并直接关回无线调试以隐藏系统调试通知（后续走 127.0.0.1 控制口，不再需要无线调试）。 */
    fun disableWirelessDebugAfterIgnition() = synchronized(connLock) {
        disconnect()
        autoEnabledWirelessDebug = false
        if (!secureGranted()) return@synchronized
        try {
            if (wirelessDebugOn()) {
                Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 0)
                AppLog.add("adb: disabled wireless debugging after ignition")
            }
        } catch (_: Throwable) {}
    }

    /** 配对（后台线程调用）。成功返回 null，失败返回原因。 */
    fun pair(host: String, port: Int, code: String): String? = synchronized(connLock) {
        try {
            val m = manager()
            if (m.pair(host, port, code)) { setPaired(true); null } else "rejected"
        } catch (e: Throwable) { e.javaClass.simpleName + ":" + (e.message ?: "").take(80) }
    }

    /** 执行一条 shell 命令，返回 (rc, 输出)。连接断开时自动重试一次。 */
    fun run(cmd: String, timeoutSec: Int = 30): Pair<Int, String> {
        if (!paired()) return Pair(-2, "not_paired")
        var last = Pair(-4, "no_adb")
        for (attempt in 0..1) {
            if (!connected() && !ensure()) return Pair(-4, "no_adb:$lastError")
            try { return exec(cmd, timeoutSec) }
            catch (e: IOException) { last = Pair(-3, "io:" + (e.message ?: "").take(60)); disconnect() }
            catch (e: Throwable) { return Pair(-3, e.javaClass.simpleName + ":" + (e.message ?: "").take(100)) }
        }
        return last
    }

    private fun exec(cmd: String, timeoutSec: Int): Pair<Int, String> = synchronized(execLock) {
        val st = manager().openStream("shell:" + ShellCmd.withRc(cmd))
        val bo = ByteArrayOutputStream()
        val t = Thread {
            try {
                val b = ByteArray(4096)
                while (bo.size() < 16384) {
                    val n = st.read(b, 0, b.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val cur = synchronized(bo) { bo.write(b, 0, n); bo.toString("UTF-8") }
                    // Android 16 / HyperOS 3 无线 adbd 在 echo "__rc=..." 后流可能不立刻返回 EOF，收到完整标记直接跳出避免误判超时
                    if (ShellCmd.hasCompleteRc(cur)) break
                }
            } catch (_: IOException) {}
        }
        t.isDaemon = true; t.start(); t.join(timeoutSec * 1000L)
        val done = !t.isAlive
        try { st.close() } catch (_: Throwable) {}
        if (!done) {
            val partial = synchronized(bo) { bo.toString("UTF-8") }
            val parsed = ShellCmd.parseRc(partial)
            return if (parsed.first >= 0) parsed else Pair(-1, partial.take(200))
        }
        t.join(500)
        return ShellCmd.parseRc(synchronized(bo) { bo.toString("UTF-8") })
    }

    /** 分段 base64 写文件到手机。 */
    fun writeFile(path: String, data: ByteArray, mode: String = "644"): Pair<Int, String> {
        for (c in ShellCmd.writeFileCmds(path, data, mode)) {
            val r = run(c, 20)
            if (r.first != 0) return Pair(r.first, "write_fail ${r.second.trim().take(60)}")
        }
        return Pair(0, "")
    }
}
