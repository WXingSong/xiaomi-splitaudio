package app.splitaudio

import android.content.Context
import app.splitaudio.adb.Adb
import app.splitaudio.adb.Reconnect
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 路由控制层：
 *  - 优先通过本机控制口 (127.0.0.1:47821) 与休眠待命/运行中的 AppRoute 通信（resume/pause/setbt/setidle/vol/ver），
 *    重启后只需点火一次，日常启停与配置彻底不依赖 Wi-Fi 与无线 ADB；
 *  - 自动校验运行中 AppRoute 与当前 APK 内置 approute.dex/approute.sh 的 SHA-256 指纹：
 *    覆盖安装新版 APK 后，只要当前有 Shizuku 或 Wi-Fi，会自动热替换升级底层引擎（离线时则保留旧进程继续工作，不中断分流）。 */
object Route {
    const val PORT = 47821
    private const val HOST = "127.0.0.1"
    const val DENIED = "denied"
    private const val D = "/data/local/tmp"
    @Volatile var lastStart = "none"
        set(v) { field = v; AppLog.add("route: $v") }
    @Volatile var lastAskError = ""
    @Volatile private var tokenCache: String? = null
    @Volatile private var localVerCache: String? = null
    private val tokenLock = Any()

    // ---- token ----

    fun newToken(rnd: java.util.Random = SecureRandom()): String {
        val b = ByteArray(16); rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun isValidToken(t: String?): Boolean = t != null && t.length == 32 && t.all { it in '0'..'9' || it in 'a'..'f' }

    fun token(ctx: Context): String {
        tokenCache?.let { return it }
        synchronized(tokenLock) {
            tokenCache?.let { return it }
            val sp = ctx.applicationContext.getSharedPreferences("route", Context.MODE_PRIVATE)
            var t = sp.getString("token", null)
            if (!isValidToken(t)) { t = newToken(); sp.edit().putString("token", t).commit() }
            tokenCache = t
            return t!!
        }
    }

    fun cmd(uid: Int, token: String, sub: String): String {
        require(isValidToken(token)) { "bad token" }
        return "SPLITAUDIO_UID=$uid SPLITAUDIO_TOKEN=$token sh $D/approute.sh $sub"
    }

    fun sh(ctx: Context, sub: String) = cmd(android.os.Process.myUid(), token(ctx), sub)

    // ---- 本机控制口查询 ----

    class Answer(val reply: String?, val error: String = "")

    fun answerOf(raw: String?): Answer = when (raw) {
        null -> Answer(null, "no reply")
        DENIED -> Answer(null, DENIED)
        else -> Answer(raw)
    }

    fun exchange(token: String, cmd: String, timeoutMs: Int = 400, port: Int = PORT): String? {
        require(!cmd.contains('\n') && !cmd.contains('\r')) { "one line only" }
        return Socket().use { s ->
            s.connect(InetSocketAddress(HOST, port), timeoutMs); s.soTimeout = timeoutMs * 3
            s.getOutputStream().write(("auth $token\n$cmd\n").toByteArray()); s.getOutputStream().flush()
            BufferedReader(InputStreamReader(s.getInputStream())).readLine()?.trim()
        }
    }

    fun query(ctx: Context, cmd: String, timeoutMs: Int = 400): Answer {
        val a = try { answerOf(exchange(token(ctx), cmd, timeoutMs)) }
                catch (e: Exception) { Answer(null, (e.message ?: e.javaClass.simpleName).take(60)) }
        lastAskError = a.error
        return a
    }

    fun ask(ctx: Context, cmd: String, timeoutMs: Int = 400): String? = query(ctx, cmd, timeoutMs).reply

    /** 路由进程存活判断：on（规则已挂）/ off（激活待命中）/ paused（0 CPU 休眠待命中） */
    fun isAlive(reply: String?) = reply == "on" || reply == "off" || reply == "paused"

    fun controlProblem(a: Answer): String = when {
        isAlive(a.reply) -> ""
        a.reply != null -> " | unexpected answer on the control port: ${a.reply.take(30)}"
        a.error == DENIED -> " | control port: denied (the running router was started with another token or for another uid; tap Stop split to end it)"
        else -> " | no answer on the control port: ${a.error}"
    }

    fun running(ctx: Context): Boolean = isAlive(ask(ctx, "state"))
    fun ruleOn(ctx: Context): Boolean = ask(ctx, "state") == "on"

    /** 唤醒休眠中的 AppRoute（纯本机通信，无需 Wi-Fi 与 ADB） */
    fun resume(ctx: Context): Boolean = ask(ctx, "resume", 800) == "ok"

    /** 让 AppRoute 立刻撤销分流规则并进入 0 CPU 休眠待命（纯本机通信，保留无 Wi-Fi 秒开能力） */
    fun pause(ctx: Context): Boolean = ask(ctx, "pause", 800) == "ok"

    /** 彻底销毁 AppRoute 守护进程（优先走控制口，无回复再兜底 shell stop） */
    fun shutdown(ctx: Context): Boolean {
        if (ask(ctx, "shutdown", 800) == "ok") return true
        return if (Backends.current() != null) { stop(ctx); true } else false
    }

    /** 最近一次主动设置手机喇叭音量的 uptimeMillis（用于防止本 App 调喇叭音量时误触发伴生浮条） */
    @Volatile var lastSpeakerSetAt = 0L
    /** 最近一次读写的手机喇叭音量缓存（0..100），让伴生浮条零延迟起手渲染 */
    @Volatile var lastKnownSpeakerVol: Int? = null

    /** 读取手机喇叭当前媒体音量百分比（0..100） */
    fun speakerVolGet(ctx: Context): Int? = parseOkInt(ask(ctx, "vol get"))?.also { lastKnownSpeakerVol = it }

    /** 将手机喇叭媒体音量精准设为 pct（0..100） */
    fun speakerVolSet(ctx: Context, pct: Int): Int? {
        val clamped = pct.coerceIn(0, 100)
        lastSpeakerSetAt = android.os.SystemClock.uptimeMillis()
        lastKnownSpeakerVol = clamped
        return parseOkInt(ask(ctx, "vol set $clamped"))?.also { lastKnownSpeakerVol = it }
    }

    fun parseOkInt(reply: String?): Int? {
        if (reply == null || !reply.startsWith("ok ")) return null
        return reply.substring(3).trim().toIntOrNull()?.coerceIn(0, 100)
    }

    // ---- 版本指纹校验与部署 ----

    fun fingerprint(files: List<Pair<String, ByteArray>>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((name, data) in files) {
            md.update(name.toByteArray()); md.update(0.toByte())
            md.update(data.size.toString().toByteArray()); md.update(0.toByte())
            md.update(data)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sameVersion(remote: String?, local: String): Boolean = remote != null && remote.trim() == local

    /** 纯逻辑（可单元测试）：运行中底层上报的版本指纹与本 APK 不一致（含旧版回 err/unknown/null）时需自动升级 */
    fun needsUpgrade(runningVer: String?, expectedVer: String): Boolean =
        runningVer == null || !sameVersion(runningVer, expectedVer)

    fun localVersion(ctx: Context): String = localVerCache ?: synchronized(tokenLock) {
        localVerCache ?: fingerprint(
            listOf("approute.dex", "approute.sh").map { n -> n to ctx.assets.open(n).use { it.readBytes() } }
        ).also { localVerCache = it }
    }

    /** 当前是否具备部署新版 approute.dex 的能力（Shizuku 已授权 / ADB 已连 / 有 Wi-Fi 可连无线 ADB） */
    fun canDeployNow(ctx: Context): Boolean =
        ShizukuBackend.granted() || Adb.connected() || (Adb.paired() && Reconnect.wifiUp(ctx))

    /** 运行中的底层进程版本落后于当前 APK，且当前具备在线热升级条件 */
    fun shouldUpgradeRunning(ctx: Context): Boolean =
        canDeployNow(ctx) && needsUpgrade(ask(ctx, "ver", 400), localVersion(ctx))

    fun hasPid(out: String): Boolean = Regex("(started|already) pid=\\d+").containsMatchIn(out)

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun verifyOk(sha256sumOut: String, expected: Map<String, String>): Boolean {
        val seen = HashMap<String, String>()
        for (l in sha256sumOut.lines()) {
            val m = Regex("^([0-9a-f]{64})\\s+\\*?(\\S+)$").find(l.trim()) ?: continue
            seen[m.groupValues[2].substringAfterLast('/')] = m.groupValues[1]
        }
        return expected.isNotEmpty() && expected.all { (n, h) -> seen[n.substringAfterLast('/')] == h }
    }

    @Synchronized fun deploy(ctx: Context, force: Boolean = false): String? {
        val files = listOf("approute.dex", "approute.sh").map { n -> n to ctx.assets.open(n).use { it.readBytes() } }
        val ver = fingerprint(files)
        localVerCache = ver
        if (!force) {
            val have = Backends.run("[ -s $D/approute.dex ] && [ -s $D/approute.sh ] && cat $D/approute.ver", 10)
            if (have.first == 0 && sameVersion(have.second, ver)) return null
        }
        for ((n, data) in files) {
            val a = Backends.writeFile("$D/$n", data)
            if (a.first != 0) return "deploy_fail:${a.first} ${a.second.take(60)}"
        }
        val sums = Backends.run("sha256sum " + files.joinToString(" ") { "$D/${it.first}" }, 15)
        if (sums.first != 0 || !verifyOk(sums.second, files.associate { it.first to sha256(it.second) })) return "deploy_fail:verify ${sums.second.trim().take(60)}"
        val v = Backends.writeFile("$D/approute.ver", ver.toByteArray())
        if (v.first != 0) return "deploy_fail:${v.first} ${v.second.take(60)}"
        Backends.run(sh(ctx, "stop"), 15)
        return null
    }

    /** 通过本机控制口热同步蓝牙白名单与断开自动停服分钟数（无需 Wi-Fi 与 ADB） */
    fun syncConfigViaControl(ctx: Context): Boolean {
        val btBytes = BtFilter.fileText(BtFilter.entries(ctx)).toByteArray(Charsets.UTF_8)
        val b64 = if (btBytes.isEmpty()) "-" else Base64.getEncoder().encodeToString(btBytes)
        val okBt = ask(ctx, "setbt $b64", 800) == "ok"
        val okIdle = ask(ctx, "setidle ${SplitPrefs.autoStopMinutes(ctx)}", 800) == "ok"
        return okBt && okIdle
    }

    @Synchronized fun writeRuntimeConfig(ctx: Context): String? {
        val bt = Backends.writeFile("$D/approute.btfilter", BtFilter.fileText(BtFilter.entries(ctx)).toByteArray())
        if (bt.first != 0) return "btfilter_fail:${bt.first} ${bt.second.take(60)}"
        val idle = Backends.writeFile("$D/approute.idle", SplitPrefs.idleFileText(SplitPrefs.autoStopMinutes(ctx)).toByteArray())
        if (idle.first != 0) return "idle_fail:${idle.first} ${idle.second.take(60)}"
        return null
    }

    /** 修改白名单或断开停服分钟数后同步热配置：优先走本机控制口（零 Wi-Fi 生效），未运行时不强拉后台 */
    fun syncConfigIfRunning(ctx: Context) {
        if (running(ctx)) {
            syncConfigViaControl(ctx)
            if (SplitPrefs.isActive(ctx)) MonitorService.kick(ctx)
            return
        }
        if (SplitPrefs.isActive(ctx) && Backends.current()?.alive() == true) {
            writeRuntimeConfig(ctx)
            MonitorService.kick(ctx)
        }
    }

    /** 启动或唤醒路由守护进程：
     *  - 若底层已运行且版本匹配：10ms 本机直接唤醒/复用（零 Wi-Fi、零 ADB）；
     *  - 若覆盖安装了新版 APK 导致底层版本过期：先确保 Shizuku/ADB 可用，再自动安全替换重启（连不上 ADB 时保留旧版继续运行，绝不中断）；
     *  - standby=true 时以 0 CPU 休眠待命启动，点火成功后自动关回无线调试以隐藏系统调试通知。 */
    @Synchronized fun start(ctx: Context, standby: Boolean = false): String {
        val st = query(ctx, "state")
        var upgrading = false
        if (isAlive(st.reply)) {
            val outdated = needsUpgrade(ask(ctx, "ver", 400), localVersion(ctx))
            val backendReady = outdated && canDeployNow(ctx) && (ShizukuBackend.granted() || Adb.connected() || Adb.ensure(12_000))
            if (!backendReady) {
                if (standby) return "already".also { lastStart = it }
                syncConfigViaControl(ctx)
                if (st.reply == "paused" && resume(ctx)) return "resumed".also { lastStart = it }
                return "already".also { lastStart = it }
            }
            AppLog.add("route: helper version outdated -> auto upgrading")
            upgrading = true
            shutdown(ctx)
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }
        val stale = upgrading || st.reply != null
        deploy(ctx, force = stale)?.let { return it.also { lastStart = it } }
        val w = Backends.writeFile("$D/approute.music", Music.text(Music.set(ctx)).toByteArray())
        if (w.first != 0) return "music_fail:${w.first} ${w.second.take(60)}".also { lastStart = it }
        writeRuntimeConfig(ctx)?.let { return it.also { lastStart = it } }
        val launchCmd = (if (standby) "SPLITAUDIO_STANDBY=1 " else "") + sh(ctx, "start")
        val r = Backends.run(launchCmd, 45)
        var res = (if (upgrading) "upgraded " else "start ") + "rc=${r.first} " + r.second.trim().lines().lastOrNull().orEmpty().take(80)
        if (hasPid(r.second)) {
            val check = query(ctx, "state")
            res += controlProblem(check)
            // 内置无线 ADB 点火成功且控制口已通：立刻断开 ADB 并关回无线调试以隐藏系统调试通知
            if (isAlive(check.reply) && !ShizukuBackend.granted()) {
                Adb.disableWirelessDebugAfterIgnition()
            }
        }
        return res.also { lastStart = it }
    }

    /** 修改音乐 App 列表后生效（需要重算 UID 列表）：仅当服务开启或后端在线时重启路由 */
    fun applyMusicChange(ctx: Context) {
        val active = SplitPrefs.isActive(ctx)
        if (!active && !running(ctx)) return
        shutdown(ctx)
        if (active) MonitorService.kick(ctx)
    }

    fun stop(ctx: Context): String = Backends.run(sh(ctx, "stop"), 15).second.trim()
}
