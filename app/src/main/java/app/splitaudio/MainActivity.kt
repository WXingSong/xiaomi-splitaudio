package app.splitaudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.splitaudio.adb.Adb
import app.splitaudio.adb.Reconnect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 极简主界面（纯离线、零无障碍权限、首次一次性授权后只展示核心分流控制与配置）：
 *  - 首次打开自动合并申请通知与蓝牙附近设备权限；
 *  - 权限引导卡（优先 Shizuku，兜底内置无线 ADB）：授权全部完成后自动彻底隐藏；
 *  - 顶部状态与分流控制卡 + 手机喇叭 0%～100% 精准拖动音量条 + 精简分流配置卡（选音乐 App / 指定蓝牙音箱 / 断开自动停服）；
 *  - 底部默认折叠「高级与维护」（底层状态、权限引导与彻底停止底层休眠进程）。 */
class MainActivity : Activity() {
    private lateinit var liveCard: LinearLayout
    private lateinit var tvLive: TextView
    private lateinit var tvLiveSub: TextView
    private lateinit var btnOpenWdLive: Button
    private lateinit var volCard: LinearLayout
    private lateinit var tvSpkVal: TextView
    private lateinit var sbSpk: SeekBar
    private lateinit var btnSpkMute: TextView
    private lateinit var cusCard: LinearLayout
    private lateinit var musicBtn: Button
    private lateinit var btBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var autoStopBtn: Button
    private var previewOverlay: SpeakerOverlayController? = null

    // 高级与维护（默认折叠，保持主界面极简清爽）
    private lateinit var advHeader: TextView
    private lateinit var advBody: LinearLayout
    private lateinit var tvBackend: TextView
    private lateinit var tvEngine: TextView
    private lateinit var tvLast: TextView
    private var advExpanded = false

    private lateinit var setup: SetupSection
    private var spkDragging = false
    private var lastNonZeroSpk = 40
    @Volatile private var pendingSpkPct = -1
    private var spkSendScheduled = false
    private val volIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tickRun = object : Runnable {
        override fun run() { if (ticking) { refreshSoon(); ui.postDelayed(this, 3000) } }
    }
    private val sendSpkRun = Runnable {
        spkSendScheduled = false
        val p = pendingSpkPct
        if (p in 0..100) volIo.execute { Route.speakerVolSet(applicationContext, p) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestInitialPermissions()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(24)) }

        // 1. 一次性权限引导卡（Shizuku 优先 / 内置无线 ADB 四步配对；授权完成后彻底自动隐藏）
        setup = Dist.setupSection(this, root)

        // 2. 顶部极简分流状态与快捷控制卡（与控制中心快捷磁贴完全同步）
        liveCard = card(C_GREY)
        val liveHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        liveHead.addView(
            text("🎧 " + getString(R.string.app_name), 16f, Color.argb(235, 255, 255, 255), true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        liveCard.addView(liveHead)
        tvLive = text("…", 22f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) }
        tvLiveSub = text("", 13f, Color.argb(235, 255, 255, 255)).apply { setPadding(0, dp(4), 0, dp(8)) }
        liveCard.addView(tvLive); liveCard.addView(tvLiveSub)
        liveCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("▶ " + getString(R.string.btn_start), Color.WHITE, 1f, C_GREEN) {
                if (Backends.current() == null) {
                    Toast.makeText(this@MainActivity, R.string.s_need_pair, Toast.LENGTH_LONG).show()
                } else {
                    MonitorService.start(this@MainActivity)
                }
            })
            addView(btn("■ " + getString(R.string.btn_stop), Color.WHITE, 1f, C_RED) {
                MonitorService.stop(this@MainActivity)
            })
        })
        btnOpenWdLive = btn("📶 " + getString(R.string.btn_open_wd), Color.WHITE, textColor = C_ORANGE) {
            Adb.openWirelessDebug(this@MainActivity)
        }.apply { visibility = View.GONE }
        liveCard.addView(btnOpenWdLive)
        if (Build.VERSION.SDK_INT >= 33) {
            liveCard.addView(btn("📌 " + getString(R.string.btn_add_tile), Color.argb(55, 255, 255, 255)) { requestAddTile() })
        }
        root.addView(liveCard)

        // 3. 手机喇叭 0%～100% 精准拖动音量卡（分流激活时显示，不依赖无障碍）
        volCard = card(Color.WHITE).apply {
            visibility = View.GONE
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(
                text("📱 " + getString(R.string.vol_card_title), 16f, C_TEXT, true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            tvSpkVal = text("0%", 16f, C_TEAL, true).apply { setPadding(dp(8), 0, dp(8), 0) }
            btnSpkMute = text("🔊", 22f, C_TEXT).apply {
                setPadding(dp(8), dp(2), dp(4), dp(2))
                setOnClickListener {
                    val cur = sbSpk.progress
                    val target = if (cur == 0) lastNonZeroSpk.coerceIn(10, 100) else { lastNonZeroSpk = cur; 0 }
                    sbSpk.progress = target
                    tvSpkVal.text = "$target%"
                    text = if (target == 0) "🔇" else "🔊"
                    sendSpeakerPctImmediate(target)
                }
            }
            header.addView(tvSpkVal)
            header.addView(btnSpkMute)
            addView(header)
            sbSpk = SeekBar(this@MainActivity).apply {
                max = 100
                setPadding(dp(4), dp(10), dp(4), dp(4))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        if (progress > 0) lastNonZeroSpk = progress
                        tvSpkVal.text = "$progress%"
                        btnSpkMute.text = if (progress == 0) "🔇" else "🔊"
                        sendSpeakerPctThrottled(progress)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) { spkDragging = true }
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        spkDragging = false
                        sendSpeakerPctImmediate(s?.progress ?: 0)
                    }
                })
            }
            addView(sbSpk, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(text(getString(R.string.vol_card_hint), 12f, C_TEXT_2).apply { setPadding(0, dp(4), 0, 0) })
        }
        root.addView(volCard)

        // 4. 分流配置卡（核心设置）
        cusCard = card(Color.WHITE)
        cusCard.addView(section(getString(R.string.sec_custom), C_PURPLE))
        musicBtn = btn("🎵 " + getString(R.string.btn_music, 0), C_TEAL) { startActivity(Intent(this, MusicActivity::class.java)) }
        cusCard.addView(musicBtn)
        btBtn = btn("📻 " + getString(R.string.btn_bt_all), C_BLUE) { startActivity(Intent(this, BtDevicesActivity::class.java)) }
        cusCard.addView(btBtn)
        overlayBtn = btn("🎚 " + getString(R.string.btn_overlay_right), C_PURPLE) { showOverlayDialog() }
        cusCard.addView(overlayBtn)
        autoStopBtn = btn("⏱️ " + getString(R.string.btn_autostop_min, SplitPrefs.DEFAULT_AUTO_STOP_MIN), C_INDIGO) { showAutoStopDialog() }
        cusCard.addView(autoStopBtn)
        root.addView(cusCard)

        // 5. 底部折叠区「高级与维护」（默认收起，收纳底层状态、重新配对与彻底停服）
        val advCard = card(Color.WHITE)
        advHeader = text("⚙ " + getString(R.string.adv_title_collapsed), 15f, C_TEXT_2, true).apply {
            setPadding(0, dp(2), 0, dp(2))
            setOnClickListener {
                advExpanded = !advExpanded
                advBody.visibility = if (advExpanded) View.VISIBLE else View.GONE
                text = "⚙ " + getString(if (advExpanded) R.string.adv_title_expanded else R.string.adv_title_collapsed)
            }
        }
        advCard.addView(advHeader)
        advBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        advBody.addView(divider())
        tvBackend = text("", 12f, C_TEXT_2).apply { setPadding(0, 0, 0, dp(2)) }
        tvEngine = text("", 12f, C_TEXT_2).apply { setPadding(0, 0, 0, dp(2)) }
        tvLast = text("", 11f, C_TEXT_2).apply { setPadding(0, 0, 0, dp(6)) }
        advBody.addView(tvBackend); advBody.addView(tvEngine); advBody.addView(tvLast)
        advBody.addView(btn("🔧 " + getString(R.string.btn_toggle_setup), C_BLUE) { setup.toggleVisible() })
        advBody.addView(btn("🧹 " + getString(R.string.btn_stop_all), C_RED) {
            MonitorService.stopAll(this@MainActivity)
            Toast.makeText(this@MainActivity, R.string.toast_stopped_all, Toast.LENGTH_SHORT).show()
        })
        advCard.addView(advBody)
        root.addView(advCard)

        val scroll = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_BG_TOP, C_BG_BOTTOM))
            addView(root)
        }
        setContentView(scroll)
        Edge.apply(scroll, root)
    }

    /** 首次启动自动合并申请通知与蓝牙附近设备权限，只需点一次允许 */
    private fun requestInitialPermissions() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            req += Manifest.permission.POST_NOTIFICATIONS
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            req += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (req.isNotEmpty()) requestPermissions(req.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        setup.onResume { refreshSoon() }
        ticking = true; ui.removeCallbacks(tickRun); ui.postDelayed(tickRun, 3000)
        refreshSoon()
    }

    override fun onPause() {
        super.onPause()
        ticking = false; ui.removeCallbacks(tickRun)
        setup.onPause()
    }

    override fun onDestroy() {
        previewOverlay?.detach()
        previewOverlay = null
        ui.removeCallbacksAndMessages(null)
        volIo.shutdownNow()
        super.onDestroy()
    }

    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, SplitTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_stat_route),
                mainExecutor
            ) { code ->
                val msg = when (code) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_add_ok
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_add_exists
                    else -> R.string.tile_add_manual
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.tile_add_manual, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSpeakerPctThrottled(pct: Int) {
        pendingSpkPct = pct.coerceIn(0, 100)
        if (!spkSendScheduled) {
            spkSendScheduled = true
            ui.postDelayed(sendSpkRun, 40)
        }
    }

    private fun sendSpeakerPctImmediate(pct: Int) {
        ui.removeCallbacks(sendSpkRun)
        spkSendScheduled = false
        val p = pct.coerceIn(0, 100)
        pendingSpkPct = p
        volIo.execute { Route.speakerVolSet(applicationContext, p) }
    }

    private fun showOverlayDialog() {
        val granted = Settings.canDrawOverlays(this)
        if (!granted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_perm_title)
                .setMessage(R.string.overlay_perm_msg)
                .setPositiveButton(R.string.overlay_perm_go) { _, _ -> openOverlayPermissionSettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val modes = intArrayOf(SplitPrefs.OVERLAY_RIGHT, SplitPrefs.OVERLAY_LEFT, SplitPrefs.OVERLAY_OFF)
        val labels = arrayOf(
            getString(R.string.overlay_opt_right),
            getString(R.string.overlay_opt_left),
            getString(R.string.overlay_opt_off)
        )
        val cur = SplitPrefs.overlayMode(this)
        val checked = modes.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_dialog_title)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                dlg.dismiss()
                val next = modes[which]
                SplitPrefs.setOverlayMode(this, next)
                if (next == SplitPrefs.OVERLAY_OFF) {
                    MonitorService.dismissOverlay()
                    previewOverlay?.dismissNow()
                } else {
                    triggerOverlayPreview()
                }
                refreshSoon()
            }
            .setNeutralButton(R.string.overlay_btn_preview) { _, _ ->
                if (SplitPrefs.overlayMode(this) == SplitPrefs.OVERLAY_OFF) {
                    SplitPrefs.setOverlayMode(this, SplitPrefs.OVERLAY_RIGHT)
                }
                triggerOverlayPreview()
                refreshSoon()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        }
    }

    private fun triggerOverlayPreview() {
        if (!Settings.canDrawOverlays(this)) return
        if (MonitorService.previewOverlay()) return
        val ctrl = previewOverlay ?: SpeakerOverlayController(this).also { previewOverlay = it }
        ctrl.showPreview()
    }

    private fun showAutoStopDialog() {
        val presets = intArrayOf(0, 1, 2, 5, 10, 30)
        val cur = SplitPrefs.autoStopMinutes(this)
        val labels = presets.map {
            if (it == 0) getString(R.string.autostop_off) else getString(R.string.autostop_min, it)
        }.toMutableList().apply { add(getString(R.string.autostop_custom)) }.toTypedArray()
        val checked = presets.indexOf(cur).let { if (it >= 0) it else labels.lastIndex }
        AlertDialog.Builder(this)
            .setTitle(R.string.autostop_dialog_title)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                dlg.dismiss()
                if (which < presets.size) saveAutoStop(presets[which]) else showCustomAutoStopInput(cur)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomAutoStopInput(cur: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.autostop_custom_hint)
            if (cur > 0) setText(cur.toString())
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.autostop_custom)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < 0 || v > SplitPrefs.MAX_AUTO_STOP_MIN) {
                    Toast.makeText(this, getString(R.string.autostop_invalid, SplitPrefs.MAX_AUTO_STOP_MIN), Toast.LENGTH_SHORT).show()
                } else {
                    saveAutoStop(v)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveAutoStop(minutes: Int) {
        SplitPrefs.setAutoStopMinutes(this, minutes)
        val c = applicationContext
        Thread { Route.syncConfigIfRunning(c) }.start()
        refreshSoon()
    }

    internal fun refreshSoon() {
        Thread {
            Thread.sleep(250)
            val upd = setup.poll()
            val active = SplitPrefs.isActive(this)
            val s = Route.ask(this, "state")
            val routerAlive = Route.isAlive(s)
            val spkVol = if (active && (s == "on" || s == "off")) Route.speakerVolGet(this) else null
            val be = Backends.current()
            val conn = if (be == null) getString(R.string.v_none) else be.name + " " + getString(if (be.alive()) R.string.v_connected else R.string.v_disconnected)
            val nMusic = Music.set(this).count { packageManager.getLaunchIntentForPackage(it) != null }
            val btList = BtFilter.entries(this)
            val btStatus = try { BtFilter.inspect(this, getSystemService(Context.AUDIO_SERVICE) as AudioManager) } catch (_: Throwable) { BtFilter.Status.None }
            val autoStopMin = SplitPrefs.autoStopMinutes(this)
            val overlayMode = SplitPrefs.overlayMode(this)
            val overlayPerm = Settings.canDrawOverlays(this)
            val wifi = Reconnect.wifiUp(this)
            val wdOn = Adb.wirelessDebugOn()
            val needWd = active && !routerAlive && !ShizukuBackend.granted() && Adb.paired() && wifi && !wdOn && !Adb.secureGranted()
            val engineText = when (s) {
                "on" -> getString(R.string.eng_on)
                "off" -> getString(R.string.eng_wait)
                "paused" -> getString(R.string.eng_standby)
                else -> getString(R.string.eng_stopped)
            }
            runOnUiThread {
                upd.invoke()
                val ready = be != null || routerAlive
                liveCard.visibility = if (ready) View.VISIBLE else View.GONE
                cusCard.visibility = if (ready) View.VISIBLE else View.GONE

                val (live, color) = when {
                    !active -> Pair("⚪ " + getString(R.string.live_stopped), C_GREY)
                    s == "on" -> Pair("🟢 " + getString(R.string.live_on), C_GREEN)
                    !routerAlive && !ShizukuBackend.granted() && !wifi -> Pair("🟠 " + getString(R.string.live_need_wifi), C_ORANGE)
                    needWd -> Pair("🟠 " + getString(R.string.live_need_wd), C_ORANGE)
                    btStatus is BtFilter.Status.Other -> Pair("🟠 " + getString(R.string.live_bt_other), C_ORANGE)
                    else -> Pair("🔵 " + getString(R.string.live_wait_bt), C_BLUE)
                }
                tvLive.text = live
                (liveCard.background as GradientDrawable).setColor(color)
                btnOpenWdLive.visibility = if (needWd) View.VISIBLE else View.GONE
                tvLiveSub.text = if (btList.isEmpty()) {
                    getString(R.string.st_bt_all)
                } else {
                    getString(R.string.st_bt_selected, btList.joinToString("、") { it.displayName() })
                }

                volCard.visibility = if (ready && spkVol != null) View.VISIBLE else View.GONE
                if (spkVol != null && !spkDragging) {
                    if (spkVol > 0) lastNonZeroSpk = spkVol
                    sbSpk.progress = spkVol
                    tvSpkVal.text = "$spkVol%"
                    btnSpkMute.text = if (spkVol == 0) "🔇" else "🔊"
                }

                tvBackend.text = getString(R.string.st_conn, conn)
                tvEngine.text = getString(R.string.st_engine, engineText)
                tvLast.text = getString(R.string.st_last, Route.lastStart)

                musicBtn.text = "🎵 " + getString(R.string.btn_music, nMusic)
                btBtn.text = "📻 " + if (btList.isEmpty()) getString(R.string.btn_bt_all) else getString(R.string.btn_bt_count, btList.size)
                overlayBtn.text = "🎚 " + when {
                    overlayMode == SplitPrefs.OVERLAY_OFF -> getString(R.string.btn_overlay_off)
                    !overlayPerm -> getString(R.string.btn_overlay_need_perm)
                    overlayMode == SplitPrefs.OVERLAY_LEFT -> getString(R.string.btn_overlay_left)
                    else -> getString(R.string.btn_overlay_right)
                }
                autoStopBtn.text = "⏱️ " + if (autoStopMin == 0) getString(R.string.btn_autostop_off) else getString(R.string.btn_autostop_min, autoStopMin)
            }
        }.start()
    }

    // ---- 公共小组件（internal：供 PlaySetup 复用） ----
    internal fun card(bg: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(bg) }
        elevation = dp(3).toFloat()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
    }

    internal fun text(t: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    internal fun section(t: String, color: Int): TextView = text(t, 19f, color, true).apply { setPadding(0, 0, 0, dp(10)) }

    internal fun divider() = View(this).apply {
        setBackgroundColor(Color.rgb(236, 239, 241))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(10), 0, dp(10)) }
    }

    internal fun chip(): TextView = text("", 12f, Color.WHITE, true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(C_GREY) }
    }

    internal fun setChip(c: TextView, level: Int, t: String) {
        c.text = t
        (c.background as GradientDrawable).setColor(when (level) { 1 -> C_GREEN; 0 -> C_RED; 3 -> C_GREY; else -> C_ORANGE })
    }

    internal fun btn(label: String, color: Int, weight: Float = 0f, textColor: Int = Color.WHITE, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        val fill = color; val fg = if (color == Color.WHITE) textColor else Color.WHITE
        setTextColor(fg)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(fill) }
        stateListAnimator = null
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(if (weight > 0f) 0 else LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            setMargins(dp(4), dp(6), dp(4), dp(6))
        }
        setOnClickListener { onClick(); refreshSoon() }
    }

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        val C_PURPLE = Color.rgb(123, 31, 162)
        val C_INDIGO = Color.rgb(57, 73, 171)
        val C_BLUE = Color.rgb(30, 136, 229)
        val C_TEAL = Color.rgb(0, 137, 123)
        val C_GREEN = Color.rgb(67, 160, 71)
        val C_RED = Color.rgb(229, 57, 53)
        val C_ORANGE = Color.rgb(245, 124, 0)
        val C_GREY = Color.rgb(120, 144, 156)
        val C_TEXT = Color.rgb(38, 50, 56)
        val C_TEXT_2 = Color.rgb(96, 125, 139)
        val C_BG_TOP = Color.rgb(237, 231, 246)
        val C_BG_BOTTOM = Color.rgb(224, 247, 250)
    }
}
