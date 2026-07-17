package com.oneandonly.thelaunch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import kotlin.math.abs
import kotlin.math.min

/**
 * Right-edge alphabet scrubber: letters only on a transparent background,
 * active letter pill while scrubbing, light haptics on each letter change.
 * Only letters that have apps are shown; empty letters are omitted.
 */
class AlphabetIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /** [centerYInParent] is the active letter's vertical center in parent coordinates. */
        fun onLetterSelected(letter: Char, centerYInParent: Float)
        fun onScrubEnd()
    }

    var listener: Listener? = null

    /** Visible letters only (those with at least one app), sorted # then A-Z. */
    private var letters: List<Char> = emptyList()

    private var activeIndex = -1
    private var isScrubbing = false

    /** Animated highlight position (0..letters.lastIndex as float) for smooth pill motion. */
    private var highlightPos = -1f
    private var highlightAnim: android.animation.ValueAnimator? = null

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0x99FFFFFF.toInt()
        isSubpixelText = true
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = 0xFF1C1C1E.toInt()
        isSubpixelText = true
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val tmpRect = RectF()

    init {
        // No solid/glass rail — home content shows through behind the letters.
        setBackgroundColor(0x00000000)
    }

    fun setAvailableLetters(labels: Collection<String>) {
        val next = labels
            .map { firstLetter(it) }
            .toSet()
            .sortedWith(compareBy({ it != '#' }, { it }))
        if (next != letters) {
            if (activeIndex >= 0) {
                val was = letters.getOrNull(activeIndex)
                activeIndex = if (was != null) next.indexOf(was) else -1
                highlightPos = if (activeIndex >= 0) activeIndex.toFloat() else -1f
            }
            letters = next
            if (next.isEmpty()) visibility = GONE
            invalidate()
        }
    }

    fun hasLetters(): Boolean = letters.isNotEmpty()

    fun clearActive() {
        highlightAnim?.cancel()
        if (activeIndex != -1 || isScrubbing || highlightPos >= 0f) {
            activeIndex = -1
            isScrubbing = false
            highlightPos = -1f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = (32 * density).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private fun contentHeight(): Float {
        return (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(0f)
    }

    private fun contentTop(): Float = paddingTop.toFloat()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val contentH = contentHeight()
        if (contentH <= 0f || letters.isEmpty()) return

        val slotH = contentH / letters.size
        val baseSize = min(slotH * 0.62f, 12.5f * density)
        letterPaint.textSize = baseSize
        activePaint.textSize = baseSize * 1.05f
        val cx = w / 2f
        val top = contentTop()

        // No track/rail background — transparent strip; letters float over home.

        // Active pill slides smoothly between letters.
        if (highlightPos >= 0f) {
            val pillCy = top + slotH * highlightPos + slotH / 2f
            val pillW = 20f * density
            val pillH = min(slotH * 0.88f, 22f * density)
            pillPaint.color = 0xF2FFFFFF.toInt()
            tmpRect.set(cx - pillW / 2f, pillCy - pillH / 2f, cx + pillW / 2f, pillCy + pillH / 2f)
            canvas.drawRoundRect(tmpRect, pillH / 2f, pillH / 2f, pillPaint)
        }

        for (i in letters.indices) {
            val c = letters[i]
            val cy = top + slotH * i + slotH / 2f + baseSize / 3f
            val dist = if (highlightPos >= 0f) abs(i - highlightPos) else 99f
            if (dist < 0.55f) {
                // Near the active pill: dark text on white pill.
                val t = 1f - (dist / 0.55f)
                activePaint.alpha = (255 * t.coerceIn(0f, 1f)).toInt()
                letterPaint.alpha = ((1f - t) * 0x99).toInt()
                if (t > 0.5f) {
                    canvas.drawText(c.toString(), cx, cy, activePaint)
                } else {
                    canvas.drawText(c.toString(), cx, cy, letterPaint)
                }
            } else {
                // Soft fade for letters far from finger while scrubbing.
                letterPaint.alpha = if (isScrubbing) 0x66 else 0x99
                canvas.drawText(c.toString(), cx, cy, letterPaint)
            }
        }
        letterPaint.alpha = 0xFF
        activePaint.alpha = 0xFF
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                updateFromY(event.y, force = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromY(event.y, force = false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isScrubbing = false
                invalidate()
                listener?.onScrubEnd()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromY(y: Float, force: Boolean) {
        val contentH = contentHeight()
        if (contentH <= 0f || letters.isEmpty()) return
        val top = contentTop()
        val localY = (y - top).coerceIn(0f, contentH - 0.001f)
        val slotH = contentH / letters.size
        val idx = (localY / slotH).toInt().coerceIn(0, letters.lastIndex)
        if (idx != activeIndex || force) {
            val changed = idx != activeIndex
            activeIndex = idx
            animateHighlightTo(idx.toFloat())
            if (changed || force) {
                performLetterHaptic()
                val centerY = top + slotH * idx + slotH / 2f + this.top
                listener?.onLetterSelected(letters[idx], centerY)
            }
        }
    }

    private fun animateHighlightTo(target: Float) {
        highlightAnim?.cancel()
        val start = if (highlightPos < 0f) target else highlightPos
        if (abs(start - target) < 0.01f) {
            highlightPos = target
            invalidate()
            return
        }
        // Short spring-like settle so the pill glides between letters.
        highlightAnim = android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = 90L
            interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
            addUpdateListener {
                highlightPos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun performLetterHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    companion object {
        fun firstLetter(label: String): Char {
            val ch = label.trim().firstOrNull()?.uppercaseChar() ?: return '#'
            return if (ch in 'A'..'Z') ch else '#'
        }
    }
}
