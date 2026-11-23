package app.splitaudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.splitaudio.adb.Adb
import app.splitaudio.adb.Reconnect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

/** 下拉控制中心「音频分流」磁贴长按弹出的小米澎湃 OS 风格小窗：
 *  - 顶部标题、实时状态圆点与澎湃蓝胶囊开关（可直接启停分流）；
 *  - 中部宽大圆角胶囊音量条，独立精准调节 0%～100% 手机喇叭音量（支持点图标静音、弹窗内实体音量键微调）；
 *  - 底部经典双胶囊按钮：「更多设置」跳转 App 主界面、「完成」或点空白处无缝回到当前应用。 */
class SpeakerPanelActivity : Activity() {
    private var dark = false
    private var blurEnabled = false
    private var dismissing = false
    @Volatile private var destroyed = false

    private lateinit var cardView: LinearLayout
    private lateinit var cardBg: GradientDrawable
    private lateinit var statusDotBg: GradientDrawable
    private lateinit var tvStatus: TextView
    private lateinit var swSplit: HyperSwitchView
    private lateinit var tvPct: TextView
    private lateinit var slider: HyperVolumeSlider
    private lateinit var tvHint: TextView

    private var volReady = false
    private var routerAlive = false
    private var dragging = false
    private var lastNonZero = 40
    private var lastUserSetAt = 0L
    @Volatile private var pendingVol = -1
    private var volSendScheduled = false
    private var polling = false

    private val ui = Handler(Looper.getMainLooper())
    private val pollIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val volIo: ExecutorService = Executors.newSingleThreadExecutor()

    private var blurListener: Consumer<Boolean>? = null
    private var backCb: Any? = null

    private val pollTick = Runnable { if (polling) pollOnce() }
    private val sendVolRun = Runnable {
        volSendScheduled = false
        val p = pendingVol
        if (p in 0..100) {
            pendingVol = -1
            val app = applicationContext
            try { volIo.execute { Route.speakerVolSet(app, p) } } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        configureWindow()
        buildUi()
        registerBackHandler()
        playEnterAnimation()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!dismissing) pollOnce()
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        polling = true
        ui.removeCallbacks(pollTick)
        pollOnce()
    }

    override fun onPause() {
        isVisible = false
        polling = false
        ui.removeCallbacks(pollTick)
        flushPendingVol()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!dismissing && !isFinishing) {
            flushPendingVol()
            finish()
            noCloseTransition()
        }
    }

    override fun onDestroy() {
        destroyed = true
        polling = false
        ui.removeCallbacksAndMessages(null)
        blurListener?.let {
            try { windowManager.removeCrossWindowBlurEnabledListener(it) } catch (_: Throwable) {}
            blurListener = null
        }
        if (Build.VERSION.SDK_INT >= 33) {
            (backCb as? OnBackInvokedCallback)?.let {
                try { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) } catch (_: Throwable) {}
            }
            backCb = null
        }
        pollIo.shutdownNow()
        // 平滑关闭音量写入线程，确保刚松手即点「完成」时的最后一次音量指令完整送达 127.0.0.1:47821
        volIo.shutdown()
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = if (dark) 0.48f else 0.28f
            blurBehindRadius = dp(32)
        }
        blurEnabled = try { windowManager.isCrossWindowBlurEnabled } catch (_: Throwable) { false }
        val listener = Consumer<Boolean> { enabled ->
            if (destroyed) return@Consumer
            blurEnabled = enabled
            updateCardSurface()
        }
        blurListener = listener
        try { windowManager.addCrossWindowBlurEnabledListener(mainExecutor, listener) } catch (_: Throwable) {}
    }

    private fun buildUi() {
        val textPrimary = if (dark) Color.argb(242, 255, 255, 255) else Color.argb(230, 0, 0, 0)
        val textSecondary = if (dark) Color.argb(145, 255, 255, 255) else Color.argb(128, 0, 0, 0)
        val dividerColor = if (dark) Color.argb(20, 255, 255, 255) else Color.argb(14, 0, 0, 0)
        val accentBlue = if (dark) Color.rgb(43, 133, 255) else Color.rgb(13, 132, 255)
        val secondaryBtnBg = if (dark) Color.rgb(52, 55, 64) else Color.rgb(239, 241, 245)

        val backdrop = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { dismissAnimated() }
        }

        cardBg = GradientDrawable().apply { cornerRadius = dp(28).toFloat() }
        updateCardSurface()

        val cardWidth = minOf(resources.displayMetrics.widthPixels - dp(36), dp(360))
        cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            elevation = dp(18).toFloat()
            isClickable = true
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }

        // 1. 顶部：标题 + 实时状态圆点 + 澎湃 OS 胶囊开关
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tvTitle = TextView(this).apply {
            text = getString(R.string.tile_label)
            textSize = 20f
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(tvTitle)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        statusDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColorInactive())
        }
        val statusDot = View(this).apply {
            background = statusDotBg
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))

        tvStatus = TextView(this).apply {
            text = if (SplitPrefs.isActive(this@SpeakerPanelActivity)) {
                MonitorService.currentStateText.ifBlank { getString(R.string.s_starting) }
            } else {
                getString(R.string.tile_off)
            }
            textSize = 13f
            setTextColor(textSecondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusRow.addView(
            tvStatus,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        )
        titleCol.addView(statusRow)

        headerRow.addView(
            titleCol,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        )

        swSplit = HyperSwitchView(this).apply {
            applyPalette(dark)
            contentDescription = getString(R.string.panel_switch_desc)
            setChecked(SplitPrefs.isActive(this@SpeakerPanelActivity), animate = false)
            onToggle = { wantOn -> onSwitchToggled(wantOn) }
        }
        headerRow.addView(swSplit)
        cardView.addView(headerRow)

        // 2. 细腻半透明分隔线
        val divider = View(this).apply { setBackgroundColor(dividerColor) }
        cardView.addView(
            divider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            }
        )

        // 3. 手机喇叭音量标题 + 百分比读数
        val volLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvVolLabel = TextView(this).apply {
            text = getString(R.string.panel_speaker_label)
            textSize = 14f
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        volLabelRow.addView(tvVolLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        tvPct = TextView(this).apply {
            text = "--"
            textSize = 15f
            setTextColor(textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        volLabelRow.addView(tvPct)
        cardView.addView(
            volLabelRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        )

        // 4. 小米控制中心风格宽大圆角胶囊音量条
        slider = HyperVolumeSlider(this).apply {
            applyPalette(dark)
            isEnabled = false
            onDragStart = { dragging = true }
            onProgressChanged = { pct, fromUser ->
                if (fromUser) {
                    if (pct > 0) lastNonZero = pct
                    lastUserSetAt = SystemClock.uptimeMillis()
                    updatePercentText(pct, enabled = true)
                    sendVolThrottled(pct)
                }
            }
            onDragEnd = { pct ->
                dragging = false
                lastUserSetAt = SystemClock.uptimeMillis()
                updatePercentText(pct, enabled = true)
                sendVolImmediate(pct)
            }
            onMuteClick = {
                val (target, nextLast) = HyperControlMath.toggleMute(progress, lastNonZero)
                lastNonZero = nextLast
                lastUserSetAt = SystemClock.uptimeMillis()
                setProgressValue(target, animate = true)
                updatePercentText(target, enabled = true)
                sendVolImmediate(target)
            }
        }
        cardView.addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))

        // 5. 状态/操作提示语
        tvHint = TextView(this).apply {
            text = getString(R.string.panel_hint_ready)
            textSize = 12f
            setTextColor(textSecondary)
            maxLines = 2
            setLineSpacing(0f, 1.2f)
        }
        cardView.addView(
            tvHint,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )

        // 6. 底部小米控制中心双胶囊按钮：「更多设置」+「完成」
        val footerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnMore = pillButton(
            label = getString(R.string.panel_btn_more),
            bgColor = secondaryBtnBg,
            textColor = textPrimary,
            rippleColor = if (dark) Color.argb(44, 255, 255, 255) else Color.argb(26, 0, 0, 0)
        ) { openMoreSettings() }
        val btnDone = pillButton(
            label = getString(R.string.panel_btn_done),
            bgColor = accentBlue,
            textColor = Color.WHITE,
            rippleColor = Color.argb(56, 255, 255, 255)
        ) { dismissAnimated() }

        footerRow.addView(
            btnMore,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
        )
        footerRow.addView(
            btnDone,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) }
        )
        cardView.addView(
            footerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            }
        )

        backdrop.addView(
            cardView,
            FrameLayout.LayoutParams(cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        setContentView(backdrop)

        ViewCompat.setOnApplyWindowInsetsListener(backdrop) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(backdrop)
    }

    private fun pillButton(label: String, bgColor: Int, textColor: Int, rippleColor: Int, onClick: () -> Unit): Button {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(23).toFloat()
            setColor(bgColor)
        }
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }
    }

    private fun updateCardSurface() {
        val fill = when {
            dark && blurEnabled -> Color.argb(234, 36, 38, 43)
            dark -> Color.rgb(34, 36, 41)
            blurEnabled -> Color.argb(242, 255, 255, 255)
            else -> Color.WHITE
        }
        val stroke = if (dark) Color.argb(28, 255, 255, 255) else Color.argb(20, 0, 0, 0)
        cardBg.setColor(fill)
        cardBg.setStroke(dp(1), stroke)
    }

    private fun updatePercentText(pct: Int?, enabled: Boolean) {
        val textSecondary = if (dark) Color.argb(145, 255, 255, 255) else Color.argb(128, 0, 0, 0)
        val accentBlue = if (dark) Color.rgb(43, 133, 255) else Color.rgb(13, 132, 255)
        if (!enabled || pct == null) {
            tvPct.text = "--"
            tvPct.setTextColor(textSecondary)
        } else if (pct <= 0) {
            tvPct.text = getString(R.string.panel_muted)
            tvPct.setTextColor(textSecondary)
        } else {
            tvPct.text = "$pct%"
            tvPct.setTextColor(accentBlue)
        }
    }

    private fun onSwitchToggled(wantOn: Boolean) {
        if (!wantOn) {
            swSplit.setChecked(false, animate = true)
            statusDotBg.setColor(dotColorInactive())
            tvStatus.text = getString(R.string.tile_off)
            MonitorService.stop(this)
            scheduleNextPoll(350L)
            return
        }
        if (Backends.current() == null && !routerAlive) {
            Toast.makeText(this, R.string.s_need_pair, Toast.LENGTH_LONG).show()
            openMoreSettings()
            return
        }
        swSplit.setChecked(true, animate = true)
        statusDotBg.setColor(dotColorWait())
        tvStatus.text = getString(R.string.s_starting)
        MonitorService.start(this)
        scheduleNextPoll(350L)
    }

    private fun openMoreSettings() {
        if (dismissing) return
        dismissing = true
        flushPendingVol()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
        noCloseTransition()
    }

    private fun playEnterAnimation() {
        cardView.alpha = 0f
        cardView.scaleX = 0.92f
        cardView.scaleY = 0.92f
        cardView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(HyperVolumeSlider.HYPER_INTERPOLATOR)
            .start()
    }

    private fun dismissAnimated() {
        if (dismissing) return
        dismissing = true
        flushPendingVol()
        cardView.animate().cancel()
        cardView.animate()
            .alpha(0f)
            .scaleX(0.93f)
            .scaleY(0.93f)
            .setDuration(140L)
            .setInterpolator(HyperVolumeSlider.HYPER_INTERPOLATOR)
            .withEndAction {
                finish()
                noCloseTransition()
            }
            .start()
    }

    private fun noCloseTransition() {
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { dismissAnimated() }
            backCb = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        dismissAnimated()
    }

    /** 弹窗打开且手机喇叭音量可调时，按实体音量键直接按 5% 步进调节手机喇叭音量，避免弹出系统蓝牙音量条。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volReady && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5
            val next = HyperControlMath.stepVolume(slider.progress, delta)
            if (next > 0) lastNonZero = next
            lastUserSetAt = SystemClock.uptimeMillis()
            slider.setProgressValue(next, animate = true)
            updatePercentText(next, enabled = true)
            sendVolImmediate(next)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun sendVolThrottled(pct: Int) {
        pendingVol = pct.coerceIn(0, 100)
        if (!volSendScheduled) {
            volSendScheduled = true
            ui.postDelayed(sendVolRun, 40L)
        }
    }

    private fun sendVolImmediate(pct: Int) {
        ui.removeCallbacks(sendVolRun)
        volSendScheduled = false
        val p = pct.coerceIn(0, 100)
        pendingVol = -1
        val app = applicationContext
        try { volIo.execute { Route.speakerVolSet(app, p) } } catch (_: Throwable) {}
    }

    private fun flushPendingVol() {
        ui.removeCallbacks(sendVolRun)
        volSendScheduled = false
        val p = pendingVol
        if (p in 0..100) {
            pendingVol = -1
            val app = applicationContext
            try { volIo.execute { Route.speakerVolSet(app, p) } } catch (_: Throwable) {}
        }
    }

    private fun scheduleNextPoll(delayMs: Long) {
        ui.removeCallbacks(pollTick)
        if (polling && !destroyed) ui.postDelayed(pollTick, delayMs)
    }

    private fun pollOnce() {
        if (destroyed || pollIo.isShutdown) return
        val app = applicationContext
        try {
            pollIo.execute {
                if (destroyed) return@execute
                val active = SplitPrefs.isActive(app)
                val state = Route.ask(app, "state")
                val alive = Route.isAlive(state)
                val spkVol = if (alive) Route.speakerVolGet(app) else null
                val hasBackend = Backends.current() != null
                val wifi = Reconnect.wifiUp(app)
                val btList = BtFilter.entries(app)
                val btStatus = try {
                    BtFilter.inspect(app, app.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                } catch (_: Throwable) {
                    BtFilter.Status.None
                }
                ui.post {
                    if (destroyed || isFinishing) return@post
                    applySnapshot(active, state, alive, spkVol, hasBackend, wifi, btList, btStatus)
                    val nextMs = if (active && spkVol == null) 500L else 2000L
                    scheduleNextPoll(nextMs)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun applySnapshot(
        active: Boolean,
        state: String?,
        alive: Boolean,
        spkVol: Int?,
        hasBackend: Boolean,
        wifi: Boolean,
        btList: List<BtFilter.Entry>,
        btStatus: BtFilter.Status
    ) {
        routerAlive = alive
        swSplit.setChecked(active, animate = true)

        val (statusText, dotColor) = when {
            !active && state == "paused" -> Pair(getString(R.string.panel_status_standby), dotColorInactive())
            !active && !hasBackend -> Pair(getString(R.string.s_need_pair), dotColorWarn())
            !active -> Pair(getString(R.string.tile_off), dotColorInactive())
            state == "on" -> {
                val activeName = (btStatus as? BtFilter.Status.Matched)?.label.orEmpty()
                    .ifBlank { if (btList.size == 1) btList.first().displayName() else "" }
                val text = if (activeName.isNotEmpty()) {
                    getString(R.string.live_on) + " · " + activeName
                } else {
                    getString(R.string.live_on)
                }
                Pair(text, dotColorActive())
            }
            !alive && !ShizukuBackend.granted() && !wifi -> Pair(getString(R.string.live_need_wifi), dotColorWarn())
            !alive && !ShizukuBackend.granted() && Adb.paired() && !Adb.wirelessDebugOn() && !Adb.secureGranted() ->
                Pair(getString(R.string.live_need_wd), dotColorWarn())
            btStatus is BtFilter.Status.Other -> Pair(getString(R.string.live_bt_other), dotColorWarn())
            else -> Pair(MonitorService.currentStateText.ifBlank { getString(R.string.live_wait_bt) }, dotColorWait())
        }
        tvStatus.text = statusText
        statusDotBg.setColor(dotColor)

        val ready = spkVol != null
        volReady = ready
        slider.isEnabled = ready
        val recentlyAdjusted = (SystemClock.uptimeMillis() - lastUserSetAt) < 500L
        if (ready && spkVol != null) {
            if (!dragging && !recentlyAdjusted) {
                if (spkVol > 0) lastNonZero = spkVol
                slider.setProgressValue(spkVol, animate = false)
                updatePercentText(spkVol, enabled = true)
            }
        } else if (!dragging) {
            slider.setProgressValue(0, animate = false)
            updatePercentText(null, enabled = false)
        }

        tvHint.text = when {
            ready && active -> getString(R.string.panel_hint_ready)
            ready -> getString(R.string.panel_hint_standby)
            !hasBackend -> getString(R.string.panel_hint_need_setup)
            else -> getString(R.string.panel_hint_inactive)
        }
    }

    private fun dotColorActive(): Int = if (dark) Color.rgb(48, 209, 88) else Color.rgb(34, 197, 94)
    private fun dotColorWait(): Int = if (dark) Color.rgb(43, 133, 255) else Color.rgb(13, 132, 255)
    private fun dotColorWarn(): Int = if (dark) Color.rgb(255, 159, 10) else Color.rgb(255, 149, 0)
    private fun dotColorInactive(): Int = if (dark) Color.rgb(104, 110, 122) else Color.rgb(176, 182, 192)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile var isVisible = false
    }
}
