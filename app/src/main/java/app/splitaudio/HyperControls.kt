package app.splitaudio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/** 音量面板与控件的纯数学逻辑（无 Android 依赖，便于单元测试）。 */
object HyperControlMath {
    /** 将触摸横坐标 x 映射到 [0, 100]，左右预留 insetPx 让手指更容易贴边拉到 0% 与 100%。 */
    fun xToPercent(x: Float, width: Int, insetPx: Float): Int {
        if (width <= 0) return 0
        val usable = width - insetPx * 2f
        if (usable <= 1f) return if (x >= width / 2f) 100 else 0
        val frac = ((x - insetPx) / usable).coerceIn(0f, 1f)
        return (frac * 100f).roundToInt().coerceIn(0, 100)
    }

    /** 将竖向音量条触摸纵坐标 y 映射到 [0, 100]（顶部=100%，底部=0%，上下预留 insetPx 贴边易达极值）。 */
    fun yToPercent(y: Float, height: Int, insetPx: Float): Int {
        if (height <= 0) return 0
        val usable = height - insetPx * 2f
        if (usable <= 1f) return if (y <= height / 2f) 100 else 0
        val frac = ((height - insetPx - y) / usable).coerceIn(0f, 1f)
        return (frac * 100f).roundToInt().coerceIn(0, 100)
    }

    /** 静音键切换：当前为 0 时恢复上次非零音量（至少 10%），否则记录当前音量并静音。 */
    fun toggleMute(current: Int, lastNonZero: Int): Pair<Int, Int> {
        val cur = current.coerceIn(0, 100)
        return if (cur == 0) {
            val restored = lastNonZero.coerceIn(10, 100)
            Pair(restored, restored)
        } else {
            Pair(0, cur)
        }
    }

    /** 实体音量键在弹窗打开时的步进调整（0..100 截断）。 */
    fun stepVolume(current: Int, delta: Int): Int = (current + delta).coerceIn(0, 100)

    /** 线性 ARGB 颜色插值（零对象分配）。 */
    fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a0 = (from ushr 24) and 0xFF; val a1 = (to ushr 24) and 0xFF
        val r0 = (from ushr 16) and 0xFF; val r1 = (to ushr 16) and 0xFF
        val g0 = (from ushr 8) and 0xFF; val g1 = (to ushr 8) and 0xFF
        val b0 = from and 0xFF; val b1 = to and 0xFF
        val a = (a0 + (a1 - a0) * f).roundToInt().coerceIn(0, 255)
        val r = (r0 + (r1 - r0) * f).roundToInt().coerceIn(0, 255)
        val g = (g0 + (g1 - g0) * f).roundToInt().coerceIn(0, 255)
        val b = (b0 + (b1 - b0) * f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** 小米澎湃 OS 控制中心风格的圆角胶囊音量条：
 *  - 整条宽大圆角胶囊，支持全宽度任意位置点击或拖动 0%～100% 精准调节；
 *  - 左侧内置矢量喇叭图标（随填充进度双层裁剪无缝反色），轻点即可一键静音 / 恢复音量；
 *  - 拖动到 0% / 100% 边界及静音切换提供细腻震动反馈。 */
class HyperVolumeSlider(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * d

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cornerRadius = dp(18f)
    private val edgeInset = dp(12f)
    private val iconHitWidth = dp(48f)
    private val iconCx = dp(27f)

    private val boundsRect = RectF()
    private val gripRect = RectF()
    private val arcRect = RectF()
    private val clipPath = Path()
    private val speakerPath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 255, 255, 255)
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(dp(1.2f))
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.85f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var iconOnTrackColor = Color.rgb(92, 99, 112)
    private var iconOnFillColor = Color.WHITE
    private var activeFillColor = Color.rgb(13, 132, 255)
    private var disabledFillColor = Color.rgb(192, 198, 208)

    var progress: Int = 0
        private set
    private var visualProgress: Float = 0f
    private var anim: ValueAnimator? = null
    private var downX = 0f
    private var startedOnIcon = false
    private var dragging = false

    var onDragStart: (() -> Unit)? = null
    var onProgressChanged: ((pct: Int, fromUser: Boolean) -> Unit)? = null
    var onDragEnd: ((pct: Int) -> Unit)? = null
    var onMuteClick: (() -> Unit)? = null

    init {
        isClickable = true
        applyPalette(dark = false)
    }

    fun applyPalette(dark: Boolean) {
        trackPaint.color = if (dark) Color.rgb(52, 55, 64) else Color.rgb(239, 241, 245)
        activeFillColor = if (dark) Color.rgb(43, 133, 255) else Color.rgb(13, 132, 255)
        disabledFillColor = if (dark) Color.rgb(74, 78, 89) else Color.rgb(203, 210, 220)
        iconOnTrackColor = if (dark) Color.rgb(200, 205, 214) else Color.rgb(92, 99, 112)
        iconOnFillColor = Color.WHITE
        fillPaint.color = if (isEnabled) activeFillColor else disabledFillColor
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        fillPaint.color = if (enabled) activeFillColor else disabledFillColor
        alpha = if (enabled) 1f else 0.45f
        invalidate()
    }

    fun setProgressValue(pct: Int, animate: Boolean = false) {
        val clamped = pct.coerceIn(0, 100)
        progress = clamped
        anim?.cancel()
        if (!animate || width <= 0) {
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
            interpolator = HYPER_INTERPOLATOR
            addUpdateListener {
                visualProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = dp(54f).roundToInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        boundsRect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        // 1. 胶囊底轨
        canvas.drawRect(0f, 0f, w, h, trackPaint)

        // 2. 活跃填充条 + 右侧柔和白色握持指示条
        val fillRight = (w * (visualProgress / 100f)).coerceIn(0f, w)
        if (fillRight > 0.5f) {
            canvas.drawRect(0f, 0f, fillRight, h, fillPaint)
            if (isEnabled && fillRight >= dp(54f)) {
                val gripH = dp(18f)
                val gripW = dp(3.2f)
                val gx = fillRight - dp(9.5f) - gripW
                gripRect.set(gx, (h - gripH) / 2f, gx + gripW, (h + gripH) / 2f)
                canvas.drawRoundRect(gripRect, gripW / 2f, gripW / 2f, gripPaint)
            }
        }

        // 3. 左侧喇叭矢量图标（底轨色 + 填充区裁剪白色，滑动覆盖时像素级无缝反色）
        val cy = h / 2f
        val level = visualProgress.roundToInt()
        drawSpeakerIcon(canvas, iconCx, cy, level, iconOnTrackColor)
        if (fillRight > 0.5f) {
            canvas.save()
            canvas.clipRect(0f, 0f, fillRight, h)
            drawSpeakerIcon(canvas, iconCx, cy, level, iconOnFillColor)
            canvas.restore()
        }

        canvas.restore()
    }

    private fun drawSpeakerIcon(canvas: Canvas, cx: Float, cy: Float, level: Int, color: Int) {
        iconFillPaint.color = color
        iconStrokePaint.color = color

        val x0 = cx - dp(8.5f)
        val x1 = cx - dp(4.5f)
        val x2 = cx + dp(0.5f)
        val yBox = dp(3.4f)
        val yFlare = dp(7.2f)
        speakerPath.reset()
        speakerPath.moveTo(x0, cy - yBox)
        speakerPath.lineTo(x1, cy - yBox)
        speakerPath.lineTo(x2, cy - yFlare)
        speakerPath.lineTo(x2, cy + yFlare)
        speakerPath.lineTo(x1, cy + yBox)
        speakerPath.lineTo(x0, cy + yBox)
        speakerPath.close()
        canvas.drawPath(speakerPath, iconFillPaint)

        if (level <= 0) {
            val mx0 = cx + dp(3.4f)
            val mx1 = cx + dp(8.8f)
            val my = dp(2.7f)
            canvas.drawLine(mx0, cy - my, mx1, cy + my, iconStrokePaint)
            canvas.drawLine(mx1, cy - my, mx0, cy + my, iconStrokePaint)
        } else {
            val acx = cx + dp(0.6f)
            val r1 = dp(4.6f)
            arcRect.set(acx - r1, cy - r1, acx + r1, cy + r1)
            canvas.drawArc(arcRect, -44f, 88f, false, iconStrokePaint)
            if (level > 50) {
                val r2 = dp(8.0f)
                arcRect.set(acx - r2, cy - r2, acx + r2, cy + r2)
                canvas.drawArc(arcRect, -46f, 92f, false, iconStrokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                startedOnIcon = event.x <= iconHitWidth
                dragging = false
                if (!startedOnIcon) {
                    dragging = true
                    onDragStart?.invoke()
                    updateFromTouch(event.x)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke(progress)
                } else if (startedOnIcon) {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
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

    private fun updateFromTouch(x: Float) {
        val next = HyperControlMath.xToPercent(x, width, edgeInset)
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
        super.onDetachedFromWindow()
    }

    companion object {
        val HYPER_INTERPOLATOR = PathInterpolator(0.2f, 0.9f, 0.2f, 1.0f)
    }
}

/** 小米澎湃 OS 控制中心风格的圆润胶囊开关（自带舒适点击热区与平滑过渡动画）。 */
class HyperSwitchView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * d

    private val pad = dp(6f)
    private val trackW = dp(50f)
    private val trackH = dp(28f)
    private val thumbInset = dp(3f)

    private val trackRect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(dp(2f), 0f, dp(1f), Color.argb(42, 0, 0, 0))
    }

    private var trackOffColor = Color.rgb(220, 223, 230)
    private var trackOnColor = Color.rgb(13, 132, 255)

    var checked: Boolean = false
        private set
    private var thumbPos: Float = 0f
    private var anim: ValueAnimator? = null

    var onToggle: ((wantChecked: Boolean) -> Unit)? = null

    init {
        isClickable = true
        applyPalette(dark = false)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onToggle?.invoke(!checked)
        }
    }

    fun applyPalette(dark: Boolean) {
        trackOffColor = if (dark) Color.rgb(64, 67, 76) else Color.rgb(220, 223, 230)
        trackOnColor = if (dark) Color.rgb(43, 133, 255) else Color.rgb(13, 132, 255)
        invalidate()
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (checked == value && (anim == null || !anim!!.isRunning)) {
            thumbPos = if (value) 1f else 0f
            invalidate()
            return
        }
        checked = value
        anim?.cancel()
        val target = if (value) 1f else 0f
        if (!animate || width <= 0) {
            thumbPos = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(thumbPos, target).apply {
            duration = 180L
            interpolator = HyperVolumeSlider.HYPER_INTERPOLATOR
            addUpdateListener {
                thumbPos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (trackW + pad * 2f).roundToInt()
        val h = (trackH + pad * 2f).roundToInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val left = (width - trackW) / 2f
        val top = (height - trackH) / 2f
        val radius = trackH / 2f
        trackRect.set(left, top, left + trackW, top + trackH)
        trackPaint.color = HyperControlMath.lerpColor(trackOffColor, trackOnColor, thumbPos)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val thumbR = (trackH - thumbInset * 2f) / 2f
        val minCx = left + thumbInset + thumbR
        val maxCx = left + trackW - thumbInset - thumbR
        val cx = minCx + (maxCx - minCx) * thumbPos
        val cy = top + radius
        canvas.drawCircle(cx, cy, thumbR, thumbPaint)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }
}
