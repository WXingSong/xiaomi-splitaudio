package app.splitaudio.adb

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.splitaudio.MainActivity
import app.splitaudio.MonitorService
import app.splitaudio.R
import app.splitaudio.Route
import app.splitaudio.SetupSection
import app.splitaudio.ShizukuBackend
import app.splitaudio.SplitPrefs

/** Play 版主畫面的「一次性設定」：4 步引導（開發者選項 → 無線偵錯 → 配對 → 連線），每步自動偵測完成就打勾，目前這步展開；
 *  全部完成後收成一行「設定完成」。手機上 Shizuku 有在跑時多一列「授權 Shizuku」捷徑；Shizuku 已授權就整塊隱藏（用不到）。
 *  畫面小工具借用 [MainActivity] 的 helper（card／text／btn…）。 */
class PlaySetup(private val a: MainActivity, parent: LinearLayout) : SetupSection {
    private class StepUi(val box: LinearLayout, val title: TextView, val chip: TextView, val desc: TextView, val actions: LinearLayout)

    private val card: LinearLayout = a.card(Color.WHITE)
    private val title: TextView = a.section("🧭 " + a.getString(R.string.sec_setup), MainActivity.C_TEAL)
    private val doneNote: TextView = a.text(a.getString(R.string.setup_done_d), 13f, MainActivity.C_TEXT_2).apply { setPadding(0, 0, 0, a.dp(6)) }
    private val shzRow = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val steps = ArrayList<StepUi>()
    private val btnAbout: Button
    private val btnDev: Button
    private val btnWd: Button
    private val tvWifi: TextView
    private val btnPair: Button
    private val tvPair: TextView
    private val etCode: EditText
    private val pairRow: LinearLayout
    private val btnConnect: Button
    private val btnWd4: Button
    private val redoBtn: Button
    private var showAll = false
    @Volatile var forceVisible = false

    init {
        card.addView(title)
        card.addView(doneNote)
        // 手机上已经有 Shizuku 的人：在这里授权就好，不用做下面的步骤
        shzRow.addView(a.text(a.getString(R.string.shz_hint), 13f, MainActivity.C_TEXT_2))
        shzRow.addView(a.btn(a.getString(R.string.btn_grant_shizuku), MainActivity.C_ORANGE) { ShizukuBackend.request() })
        shzRow.addView(a.divider())
        card.addView(shzRow)
        // 1 开发者选项
        btnAbout = a.btn(a.getString(R.string.btn_open_about), MainActivity.C_BLUE) { openFirst(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        btnDev = a.btn(a.getString(R.string.btn_open_dev), MainActivity.C_BLUE) { openFirst(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        steps += step("1", R.string.step1_title, R.string.step1_d, btnAbout, btnDev)
        // 2 无线调试（仅需普通无线调试，无需开启「USB 调试（安全设置）」）
        btnWd = a.btn(a.getString(R.string.btn_open_wd), MainActivity.C_BLUE) { Adb.openWirelessDebug(a) }
        tvWifi = a.text(a.getString(R.string.need_wifi), 13f, MainActivity.C_RED, true)
        steps += step("2", R.string.step2_title, R.string.step2_d, tvWifi, btnWd)
        // 3 配对
        btnPair = a.btn(a.getString(R.string.btn_pair), MainActivity.C_ORANGE) { startPairing() }
        tvPair = a.text("", 13f, MainActivity.C_PURPLE, true).apply { setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(2)) }
        etCode = EditText(a).apply {
            hint = a.getString(R.string.pair_code_hint); inputType = InputType.TYPE_CLASS_NUMBER; textSize = 18f
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        pairRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(etCode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(a.btn(a.getString(R.string.btn_pair_send), MainActivity.C_GREEN) { sendCode() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        steps += step("3", R.string.step3_title, R.string.step3_d, btnPair, tvPair, pairRow)
        // 4 连接并激活底层引擎（免「USB 调试（安全设置）」；激活后即可手动关回无线调试）
        btnWd4 = a.btn(a.getString(R.string.btn_open_wd), MainActivity.C_BLUE) { Adb.openWirelessDebug(a) }
        btnConnect = a.btn(a.getString(R.string.btn_connect), MainActivity.C_GREEN) { connectNow() }
        steps += step("4", R.string.step4_title, R.string.step4_d, btnWd4, btnConnect)
        redoBtn = a.btn(a.getString(R.string.setup_redo), MainActivity.C_GREY) { showAll = !showAll }
        card.addView(redoBtn)
        parent.addView(card)
    }

    override fun onResume(refresh: () -> Unit) { Pairing.onChange = refresh }
    override fun onPause() { Pairing.onChange = null }
    override fun toggleVisible() { forceVisible = !forceVisible }

    /** 背景线程：读取每步完成状态，授权全部完成后彻底自动隐藏引导卡 */
    override fun poll(): () -> Unit {
        val inp = Reconnect.inputs(a)
        val stepDone = SetupLogic.steps(inp)
        val cur = SetupLogic.current(inp)
        // 仅在尚未完成一次性授权（或显式展开引导卡）且连着 Wi-Fi 时自动尝试连接完成第 4 步授权
        if (inp.paired && !inp.connected && inp.wifi && (cur >= 0 || forceVisible)) {
            Thread { Reconnect.run(a.applicationContext, "ui") }.start()
        }
        val ps = Pairing.st
        val shzAlive = ShizukuBackend.alive(); val shzGranted = ShizukuBackend.granted()
        return {
            val allDone = cur < 0
            card.visibility = if (!forceVisible && (shzGranted || allDone)) View.GONE else View.VISIBLE
            shzRow.visibility = if (shzAlive && !shzGranted) View.VISIBLE else View.GONE
            title.text = if (allDone) "✅ " + a.getString(R.string.setup_done_title) else "🧭 " + a.getString(R.string.sec_setup)
            doneNote.visibility = if (allDone) View.VISIBLE else View.GONE
            redoBtn.visibility = if (allDone) View.VISIBLE else View.GONE
            for ((i, st) in steps.withIndex()) {
                val done = stepDone[i]; val expanded = showAll || (!allDone && i == cur)
                st.box.visibility = if (allDone && !showAll) View.GONE else View.VISIBLE
                a.setChip(st.chip, if (done) 1 else if (i == cur) 2 else 0, a.getString(if (done) R.string.chip_done else if (i == cur) R.string.chip_next else R.string.chip_todo))
                st.desc.visibility = if (expanded) View.VISIBLE else View.GONE
                st.actions.visibility = if (expanded) View.VISIBLE else View.GONE
            }
            btnAbout.visibility = if (inp.devOptions) View.GONE else View.VISIBLE
            btnDev.visibility = if (inp.devOptions) View.VISIBLE else View.GONE
            tvWifi.visibility = if (inp.wifi) View.GONE else View.VISIBLE
            tvPair.text = when (ps) {
                Pairing.St.WAITING -> a.getString(R.string.pair_wait)
                Pairing.St.FOUND -> a.getString(R.string.pair_found)
                Pairing.St.BUSY -> a.getString(R.string.pair_busy)
                Pairing.St.OK -> a.getString(R.string.pair_ok)
                Pairing.St.FAIL -> a.getString(R.string.pair_fail, Pairing.failReason)
                else -> ""
            }
            pairRow.visibility = if (ps == Pairing.St.IDLE || inp.paired) View.GONE else View.VISIBLE
            val needConnect = inp.paired && !stepDone[3]
            btnConnect.visibility = if (needConnect) View.VISIBLE else View.GONE
            btnWd4.visibility = if (needConnect && !inp.wirelessDebug) View.VISIBLE else View.GONE
        }
    }

    // ---- 引导动作 ----
    private fun openFirst(vararg it: Intent) {
        for (i in it) { try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {} }
        try { a.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }

    private fun startPairing() {
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        try { a.startForegroundService(Intent(a, PairService::class.java)) } catch (e: Exception) { Toast.makeText(a, e.javaClass.simpleName, Toast.LENGTH_SHORT).show() }
        Adb.openWirelessDebug(a)
    }

    private fun sendCode() {
        val code = etCode.text.toString().trim()
        if (!SetupLogic.validCode(code)) { Toast.makeText(a, R.string.pair_bad_code, Toast.LENGTH_SHORT).show(); return }
        Pairing.submit(a, code) { err ->
            a.runOnUiThread {
                if (err == null) etCode.setText("")
                else Toast.makeText(a, if (err == "no_dialog") a.getString(R.string.pair_no_dialog) else a.getString(R.string.pair_fail, err), Toast.LENGTH_LONG).show()
            }
            a.refreshSoon()
        }
    }

    private fun connectNow() {
        if (!btnConnect.isEnabled) return
        btnConnect.isEnabled = false
        Thread {
            try {
                val preErr = when {
                    Adb.connected() -> null
                    !Reconnect.wifiUp(a) -> a.getString(R.string.need_wifi)
                    !Adb.wirelessDebugOn() && !Adb.secureGranted() -> a.getString(R.string.s_no_adb)
                    else -> null
                }
                if (preErr != null) {
                    a.runOnUiThread { Toast.makeText(a, a.getString(R.string.toast_adb_fail, preErr), Toast.LENGTH_LONG).show() }
                } else {
                    a.runOnUiThread {
                        btnConnect.text = a.getString(R.string.toast_adb_connecting)
                        Toast.makeText(a, R.string.toast_adb_connecting, Toast.LENGTH_SHORT).show()
                    }
                    val ok = Adb.ensure(15_000)
                    if (ok) {
                        val ctx = a.applicationContext
                        if (!Route.running(ctx) || Route.shouldUpgradeRunning(ctx)) {
                            Route.start(ctx, standby = !SplitPrefs.isActive(ctx))
                        }
                        val alive = Route.running(ctx)
                        if (alive) Adb.setIgnited(true)
                        if (SplitPrefs.isActive(ctx)) MonitorService.kick(ctx)
                        val msg = if (alive && !Adb.secureGranted()) R.string.toast_adb_ok_close_wd else R.string.toast_adb_ok
                        a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_LONG).show() }
                    } else {
                        val reason = if (!Reconnect.wifiUp(a)) a.getString(R.string.need_wifi)
                            else if (!Adb.wirelessDebugOn()) a.getString(R.string.s_no_adb)
                            else Adb.lastError.ifBlank { "timeout" }
                        a.runOnUiThread { Toast.makeText(a, a.getString(R.string.toast_adb_fail, reason), Toast.LENGTH_LONG).show() }
                    }
                }
            } finally {
                a.runOnUiThread {
                    btnConnect.isEnabled = true
                    btnConnect.text = a.getString(R.string.btn_connect)
                }
                a.refreshSoon()
            }
        }.start()
    }

    /** 引導的一步：編號＋標題＋狀態膠囊；說明和按鈕只在「目前這步」展開 */
    private fun step(num: String, titleRes: Int, descRes: Int, vararg extra: View): StepUi {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(6), 0, a.dp(6)) }
        val chip = a.chip()
        val t = a.text("$num  " + a.getString(titleRes), 17f, MainActivity.C_TEXT, true)
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(chip)
        })
        val desc = a.text(a.getString(descRes), 13f, MainActivity.C_TEXT_2).apply { setPadding(0, a.dp(4), 0, a.dp(2)) }
        box.addView(desc)
        val actions = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (v in extra) actions.addView(v)
        box.addView(actions)
        box.addView(a.divider())
        card.addView(box)
        return StepUi(box, t, chip, desc, actions)
    }
}
