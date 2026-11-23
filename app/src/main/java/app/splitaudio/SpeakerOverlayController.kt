package app.splitaudio

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** 小米澎湃 OS (HyperOS) 原生侧边栏风格的竖向胶囊手机喇叭音量条：
 *  - 纯粹的竖向圆角胶囊，无多余悬浮操作按键与遮罩，自动适配系统深浅色模式；
 *  - 顶部精致等宽数字百分比、底部极简矢量手机图标（随澎湃蓝填充液位双层裁剪无缝反色）；
 *  - 支持全高度上下顺滑拖曳 0%～100%，轻点底部图标一键静音/恢复，触控微胀反馈与极值震动。 */
class HyperVerticalVolumeSlider(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * d

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeInset = dp(10f)
    private val iconHitHeight = dp(44f)

    private val boundsRect = RectF()
    private val strokeRect = RectF()
    private val phoneRect = RectF()
    private val earpieceRect = RectF()
    private val arcRect = RectF()
    private val clipPath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val glyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.55f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var iconOnTrackColor = Color.rgb(86, 94, 108)
    private var iconOnFillColor = Color.WHITE
    private var textOnTrackColor = Color.argb(175, 72, 80, 94)
    private var textOnFillColor = Color.argb(242, 255, 255, 255)
    private var showInnerRim = true

    var progress: Int = 40
        private set
    private var visualProgress: Float = 40f
    private var anim: ValueAnimator? = null
    private var downY = 0f
    private var startedOnIcon = false
    private var dragging = false

    var onDragStart: (() -> Unit)? = null
    var onProgressChanged: ((pct: Int, fromUser: Boolean) -> Unit)? = null
    var onDragEnd: ((pct: Int) -> Unit)? = null
    var onMuteClick: (() -> Unit)? = null

    init {
        isClickable = true
        elevation = dp(10f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                if (w > 0 && h > 0) {
                    outline.setRoundRect(0, 0, w, h, w / 2f)
                }
            }
        }
        applyPalette(dark = false)
    }

    fun applyPalette(dark: Boolean) {
        if (dark) {
            trackPaint.color = Color.argb(232, 38, 41, 48)
            fillPaint.color = Color.rgb(43, 133, 255)
            borderPaint.color = Color.argb(38, 255, 255, 255)
            innerRimPaint.color = Color.argb(18, 255, 255, 255)
            iconOnTrackColor = Color.argb(215, 232, 236, 244)
            iconOnFillColor = Color.WHITE
            textOnTrackColor = Color.argb(185, 224, 229, 238)
            textOnFillColor = Color.argb(245, 255, 255, 255)
            showInnerRim = false
        } else {
            trackPaint.color = Color.argb(238, 244, 246, 250)
            fillPaint.color = Color.rgb(13, 132, 255)
            borderPaint.color = Color.argb(24, 0, 0, 0)
            innerRimPaint.color = Color.argb(140, 255, 255, 255)
            iconOnTrackColor = Color.rgb(86, 94, 108)
            iconOnFillColor = Color.WHITE
            textOnTrackColor = Color.argb(175, 72, 80, 94)
            textOnFillColor = Color.argb(245, 255, 255, 255)
            showInnerRim = true
        }
        invalidate()
    }

    fun setProgressValue(pct: Int, animate: Boolean = false) {
        val clamped = pct.coerceIn(0, 100)
        progress = clamped
        anim?.cancel()
        if (!animate || height <= 0) {
            visualProgress = clamped.toFloat()
            invalidate()
            return
        }
        val start = visualProgress
        val end = clamped.toFloat()
        if (abs(end - start) < 0.5f) {
            visualProgress = end
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(start, end).apply {
            duration = 160L
            interpolator = HyperVolumeSlider.HYPER_INTERPOLATOR
            addUpdateListener {
                visualProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = w / 2f

        boundsRect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        // 1. 磨砂质感胶囊底轨
        canvas.drawRect(0f, 0f, w, h, trackPaint)

        // 2. 自底向上的澎湃蓝液位填充
        val fillTop = (h * (1f - visualProgress / 100f)).coerceIn(0f, h)
        val hasFill = fillTop < h - 0.5f
        if (hasFill) {
            canvas.drawRect(0f, fillTop, w, h, fillPaint)
        }

        // 3. 顶部极简等宽数字 + 底部手机喇叭矢量图标（底轨层 + 填充裁剪反色层）
        val level = visualProgress.roundToInt().coerceIn(0, 100)
        drawOverlayForeground(canvas, w, h, level, textOnTrackColor, iconOnTrackColor)
        if (hasFill) {
            canvas.save()
            canvas.clipRect(0f, fillTop, w, h)
            drawOverlayForeground(canvas, w, h, level, textOnFillColor, iconOnFillColor)
            canvas.restore()
        }

        canvas.restore()

        // 4. 细腻系统级胶囊描边（浅色模式下叠加一层柔和内高光）
        if (showInnerRim) {
            val innerInset = dp(1.2f)
            strokeRect.set(innerInset, innerInset, w - innerInset, h - innerInset)
            canvas.drawRoundRect(strokeRect, radius - innerInset, radius - innerInset, innerRimPaint)
        }
        val halfStroke = borderPaint.strokeWidth / 2f
        strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        canvas.drawRoundRect(strokeRect, radius - halfStroke, radius - halfStroke, borderPaint)
    }

    private fun drawOverlayForeground(
        canvas: Canvas,
        w: Float,
        h: Float,
        level: Int,
        numColor: Int,
        iconColor: Int
    ) {
        val cx = w / 2f

        // 顶部简洁数值（如 45 / 0）
        textPaint.color = numColor
        val fm = textPaint.fontMetrics
        val textCenterY = dp(20f)
        val baseline = textCenterY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(level.toString(), cx, baseline, textPaint)

        // 底部极简手机图标（一眼区分手机喇叭与旁边的蓝牙音箱原生条）
        drawPhoneSpeakerGlyph(canvas, cx, h - dp(23f), level, iconColor)
    }

    private fun drawPhoneSpeakerGlyph(canvas: Canvas, cx: Float, cy: Float, level: Int, color: Int) {
        glyphStrokePaint.color = color
        glyphFillPaint.color = color

        // 略向左偏以留出右侧声波/静音符空间，保持整体视觉居中
        val phoneCx = cx - dp(2.2f)
        val pw = dp(10.6f)
        val ph = dp(16.2f)
        phoneRect.set(phoneCx - pw / 2f, cy - ph / 2f, phoneCx + pw / 2f, cy + ph / 2f)
        canvas.drawRoundRect(phoneRect, dp(2.4f), dp(2.4f), glyphStrokePaint)

        // 顶部听筒微点与底部指示横线
        earpieceRect.set(phoneCx - dp(1.9f), phoneRect.top + dp(2.2f), phoneCx + dp(1.9f), phoneRect.top + dp(3.3f))
        canvas.drawRoundRect(earpieceRect, dp(0.6f), dp(0.6f), glyphFillPaint)

        val homeY = phoneRect.bottom - dp(2.5f)
        canvas.drawLine(phoneCx - dp(1.8f), homeY, phoneCx + dp(1.8f), homeY, glyphStrokePaint)

        if (level <= 0) {
            // 静音小叉号
            val mx = phoneRect.right + dp(4.6f)
            val mr = dp(2.3f)
            canvas.drawLine(mx - mr, cy - mr, mx + mr, cy + mr, glyphStrokePaint)
            canvas.drawLine(mx + mr, cy - mr, mx - mr, cy + mr, glyphStrokePaint)
        } else {
            // 右侧小声波弧线（随音量区分单弧/双弧）
            val acx = phoneRect.right + dp(0.5f)
            val r1 = dp(3.6f)
            arcRect.set(acx - r1, cy - r1, acx + r1, cy + r1)
            canvas.drawArc(arcRect, -42f, 84f, false, glyphStrokePaint)
            if (level > 50) {
                val r2 = dp(6.4f)
                arcRect.set(acx - r2, cy - r2, acx + r2, cy + r2)
                canvas.drawArc(arcRect, -44f, 88f, false, glyphStrokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                downY = event.y
                startedOnIcon = event.y >= height - iconHitHeight
                dragging = false
                animateTouchScale(active = true)
                if (!startedOnIcon) {
                    dragging = true
                    onDragStart?.invoke()
                    updateFromTouch(event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                animateTouchScale(active = false)
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke(progress)
                } else if (startedOnIcon) {
                    performClick()
                } else {
                    onDragEnd?.invoke(progress)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animateTouchScale(active = false)
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke(progress)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onMuteClick?.invoke()
        return true
    }

    private fun animateTouchScale(active: Boolean) {
        val target = if (active) 1.035f else 1f
        animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(if (active) 110L else 150L)
            .setInterpolator(HyperVolumeSlider.HYPER_INTERPOLATOR)
            .start()
    }

    private fun updateFromTouch(y: Float) {
        val next = HyperControlMath.yToPercent(y, height, edgeInset)
        if (next != progress) {
            if (next == 0 || next == 100) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            progress = next
            visualProgress = next.toFloat()
            invalidate()
            onProgressChanged?.invoke(next, true)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        animate().cancel()
        super.onDetachedFromWindow()
    }
}

/** 分流期间按音量键自动伴生的「澎湃系统风格手机喇叭悬浮拉动条」控制器：
 *  - 零无障碍权限，仅由运行中的 MonitorService 挂载（关闭分流即彻底解绑，0 后台残留）；
 *  - 使用 FLAG_NOT_FOCUSABLE 悬浮窗，弹出与拖动全程不触发底层视频 App 的 onPause()，绝不暂停视频；
 *  - 按实体音量键调整蓝牙音箱时，在侧边自动同步滑出手机喇叭竖向胶囊条，松手或 2.6 秒无操作柔和淡出。 */
class SpeakerOverlayController(context: Context) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var attached = false
    private var overlayRoot: FrameLayout? = null
    private var slider: HyperVerticalVolumeSlider? = null
    private var shownMode = SplitPrefs.OVERLAY_RIGHT
    private var hiding = false
    private var dragging = false
    private var lastNonZero = 40
    private var lastUserSetAt = 0L
    private var lastTriggerAt = 0L

    @Volatile private var pendingVol = -1
    private var volSendScheduled = false

    private val hideRun = Runnable { hide(animated = true) }
    private val sendVolRun = Runnable {
        volSendScheduled = false
        val p = pendingVol
        if (p in 0..100) {
            pendingVol = -1
            submitIo { Route.speakerVolSet(app, p) }
        }
    }

    private val volReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VOLUME_CHANGED) return
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream != AudioManager.STREAM_MUSIC) return
            onSystemMusicVolumeEvent()
        }
    }

    private val volObserver = object : ContentObserver(ui) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val key = uri?.lastPathSegment.orEmpty()
            // 仅响应媒体音量设置变更，且主动排除手机扬声器自身设置项，避免自触发回环
            if (!key.startsWith("volume_music") || key.startsWith("volume_music_speaker")) return
            onSystemMusicVolumeEvent()
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        try {
            val filter = IntentFilter(ACTION_VOLUME_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(volReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(volReceiver, filter)
            }
        } catch (_: Throwable) {}
        try {
            app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volObserver)
        } catch (_: Throwable) {}
    }

    fun detach() {
        if (attached) {
            attached = false
            try { app.unregisterReceiver(volReceiver) } catch (_: Throwable) {}
            try { app.contentResolver.unregisterContentObserver(volObserver) } catch (_: Throwable) {}
        }
        ui.removeCallbacksAndMessages(null)
        flushPendingVol()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeOverlayImmediately()
        } else {
            ui.post { removeOverlayImmediately() }
        }
        io.shutdown()
    }

    fun onConfigurationChanged() {
        ui.post {
            if (overlayRoot != null && !hiding) {
                val mode = SplitPrefs.overlayMode(app)
                if (mode == SplitPrefs.OVERLAY_OFF) {
                    hide(animated = false)
                } else {
                    updateOverlayAppearanceAndLayout(mode)
                }
            }
        }
    }

    /** 供设置页调用：立即预览当前选定位置的系统风格悬浮音量条（不要求此刻正在分流）。 */
    fun showPreview() {
        ui.post {
            val mode = SplitPrefs.overlayMode(app)
            if (mode == SplitPrefs.OVERLAY_OFF || !Settings.canDrawOverlays(app)) return@post
            showOrRefresh(mode, fetchFromRouter = true)
        }
    }

    /** 偏好切为关闭时立刻收起可能显示中的浮条。 */
    fun dismissNow() {
        ui.post { hide(animated = true) }
    }

    private fun onSystemMusicVolumeEvent() {
        val now = SystemClock.uptimeMillis()
        // 忽略本 App 刚刚主动写入手机喇叭音量所引发的系统广播回声
        if (now - Route.lastSpeakerSetAt < INTERNAL_SET_GUARD_MS) return
        // 双通道去抖（BroadcastReceiver 与 ContentObserver 可能在几十毫秒内先后触发）
        if (now - lastTriggerAt < TRIGGER_DEBOUNCE_MS) return
        if (!canAutoShowNow()) return
        val mode = SplitPrefs.overlayMode(app)
        if (mode == SplitPrefs.OVERLAY_OFF) return
        lastTriggerAt = now
        ui.post { showOrRefresh(mode, fetchFromRouter = true) }
    }

    private fun canAutoShowNow(): Boolean {
        if (!SplitPrefs.isActive(app)) return false
        if (!MonitorService.isRouteOn) return false
        if (SpeakerPanelActivity.isVisible) return false
        if (!Settings.canDrawOverlays(app)) return false
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return false
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null && km.isKeyguardLocked) return false
        return true
    }

    private fun showOrRefresh(mode: Int, fetchFromRouter: Boolean) {
        if (!Settings.canDrawOverlays(app)) return
        ui.removeCallbacks(hideRun)

        val existingRoot = overlayRoot
        val existingSlider = slider
        if (existingRoot != null && existingSlider != null && !hiding && shownMode == mode) {
            updateOverlayAppearanceAndLayout(mode)
            if (fetchFromRouter) syncSpeakerVolumeAsync()
            scheduleAutoHide()
            return
        }

        // 若正在退场或切换了左右位置，先瞬间清理旧视图再无缝入场
        removeOverlayImmediately()
        shownMode = mode
        hiding = false

        val dark = isDarkMode()
        val initialVol = (Route.lastKnownSpeakerVol ?: lastNonZero).coerceIn(0, 100)
        if (initialVol > 0) lastNonZero = initialVol

        val pill = HyperVerticalVolumeSlider(app).apply {
            applyPalette(dark)
            setProgressValue(initialVol, animate = false)
            onDragStart = {
                dragging = true
                ui.removeCallbacks(hideRun)
            }
            onProgressChanged = { pct, fromUser ->
                if (fromUser) {
                    if (pct > 0) lastNonZero = pct
                    lastUserSetAt = SystemClock.uptimeMillis()
                    sendVolThrottled(pct)
                }
            }
            onDragEnd = { pct ->
                dragging = false
                lastUserSetAt = SystemClock.uptimeMillis()
                sendVolImmediate(pct)
                scheduleAutoHide()
            }
            onMuteClick = {
                val (target, nextLast) = HyperControlMath.toggleMute(progress, lastNonZero)
                lastNonZero = nextLast
                lastUserSetAt = SystemClock.uptimeMillis()
                setProgressValue(target, animate = true)
                sendVolImmediate(target)
                scheduleAutoHide()
            }
        }

        val padH = dp(SHADOW_PAD_H_DP)
        val padV = dp(SHADOW_PAD_V_DP)
        val pillW = dp(PILL_WIDTH_DP)
        val pillH = dp(if (isLandscape()) PILL_HEIGHT_LAND_DP else PILL_HEIGHT_PORT_DP)

        val container = FrameLayout(app).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(padH, padV, padH, padV)
            addView(pill, FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER))
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE && !dragging) {
                    hide(animated = true)
                }
                false
            }
        }

        val lp = buildLayoutParams(mode)
        try {
            wm.addView(container, lp)
            overlayRoot = container
            slider = pill
        } catch (e: Throwable) {
            AppLog.add("overlay add fail: ${e.javaClass.simpleName}")
            return
        }

        // HyperOS 风格入场：从边缘轻滑 + 微缩放淡入
        val startDx = if (mode == SplitPrefs.OVERLAY_LEFT) -dp(16).toFloat() else dp(16).toFloat()
        pill.alpha = 0f
        pill.scaleX = 0.94f
        pill.scaleY = 0.94f
        pill.translationX = startDx
        pill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .setDuration(200L)
            .setInterpolator(HyperVolumeSlider.HYPER_INTERPOLATOR)
            .start()

        if (fetchFromRouter) syncSpeakerVolumeAsync()
        scheduleAutoHide()
    }

    private fun updateOverlayAppearanceAndLayout(mode: Int) {
        val root = overlayRoot ?: return
        val pill = slider ?: return
        pill.applyPalette(isDarkMode())
        val pillH = dp(if (isLandscape()) PILL_HEIGHT_LAND_DP else PILL_HEIGHT_PORT_DP)
        val plp = pill.layoutParams
        if (plp != null && plp.height != pillH) {
            plp.height = pillH
            pill.layoutParams = plp
        }
        val lp = buildLayoutParams(mode)
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Throwable) {}
    }

    private fun buildLayoutParams(mode: Int): WindowManager.LayoutParams {
        val isLeft = mode == SplitPrefs.OVERLAY_LEFT
        // 使用物理左/右对齐，与机身右侧实体音量键及澎湃原生侧边音量条精确对应
        val gravity = (if (isLeft) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
        // 靠右并排时：可视胶囊右边距 = lp.x + padH(14dp) = 68dp，恰与系统右侧音量条（宽~46dp，右边距~12dp）留出约 10dp 柔和间距
        val offsetX = if (isLeft) dp(0) else dp(54)
        val offsetY = if (isLandscape()) 0 else -dp(32)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = offsetX
            y = offsetY
        }
    }

    private fun syncSpeakerVolumeAsync() {
        submitIo {
            val vol = Route.speakerVolGet(app) ?: return@submitIo
            ui.post {
                val pill = slider ?: return@post
                val recentlyDragged = dragging || (SystemClock.uptimeMillis() - lastUserSetAt) < 450L
                if (!recentlyDragged) {
                    if (vol > 0) lastNonZero = vol
                    pill.setProgressValue(vol, animate = true)
                }
            }
        }
    }

    private fun scheduleAutoHide() {
        ui.removeCallbacks(hideRun)
        if (!dragging && overlayRoot != null) {
            ui.postDelayed(hideRun, AUTO_HIDE_MS)
        }
    }

    private fun hide(animated: Boolean) {
        val root = overlayRoot ?: return
        val pill = slider
        ui.removeCallbacks(hideRun)
        flushPendingVol()
        if (!animated || pill == null) {
            removeOverlayImmediately()
            return
        }
        if (hiding) return
        hiding = true
        val endDx = if (shownMode == SplitPrefs.OVERLAY_LEFT) -dp(12).toFloat() else dp(12).toFloat()
        pill.animate().cancel()
        pill.animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .translationX(endDx)
            .setDuration(160L)
            .setInterpolator(HyperVolumeSlider.HYPER_INTERPOLATOR)
            .withEndAction {
                if (overlayRoot === root) removeOverlayImmediately()
            }
            .start()
    }

    private fun removeOverlayImmediately() {
        ui.removeCallbacks(hideRun)
        dragging = false
        hiding = false
        slider?.animate()?.cancel()
        val root = overlayRoot
        overlayRoot = null
        slider = null
        if (root != null) {
            try { wm.removeViewImmediate(root) } catch (_: Throwable) {
                try { wm.removeView(root) } catch (_: Throwable) {}
            }
        }
    }

    private fun sendVolThrottled(pct: Int) {
        pendingVol = pct.coerceIn(0, 100)
        if (!volSendScheduled) {
            volSendScheduled = true
            ui.postDelayed(sendVolRun, 35L)
        }
    }

    private fun sendVolImmediate(pct: Int) {
        ui.removeCallbacks(sendVolRun)
        volSendScheduled = false
        val p = pct.coerceIn(0, 100)
        pendingVol = -1
        submitIo { Route.speakerVolSet(app, p) }
    }

    private fun flushPendingVol() {
        ui.removeCallbacks(sendVolRun)
        volSendScheduled = false
        val p = pendingVol
        if (p in 0..100) {
            pendingVol = -1
            submitIo { Route.speakerVolSet(app, p) }
        }
    }

    private fun submitIo(block: () -> Unit) {
        try {
            if (!io.isShutdown) io.execute(block)
        } catch (_: Throwable) {}
    }

    private fun isDarkMode(): Boolean =
        (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isLandscape(): Boolean =
        app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun dp(v: Int): Int = (v * app.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val AUTO_HIDE_MS = 2600L
        private const val INTERNAL_SET_GUARD_MS = 450L
        private const val TRIGGER_DEBOUNCE_MS = 120L

        private const val PILL_WIDTH_DP = 46
        private const val PILL_HEIGHT_PORT_DP = 188
        private const val PILL_HEIGHT_LAND_DP = 160
        private const val SHADOW_PAD_H_DP = 14
        private const val SHADOW_PAD_V_DP = 16
    }
}
