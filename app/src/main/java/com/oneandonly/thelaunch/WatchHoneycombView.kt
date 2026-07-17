package com.oneandonly.thelaunch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Apple Watch-style honeycomb launcher:
 * apps packed in a hex spiral, free 2D pan/fling, fish-eye focus, and
 * **whole-cluster zoom** (pinch / double-tap) that scales every icon circle
 * and the spacing between them together — not just the art inside a mask.
 */
class WatchHoneycombView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onAppClick(app: AppInfo)
        fun onAppLongClick(app: AppInfo)
        /** Fired when the user pinches; host should persist [clusterZoom]. */
        fun onClusterZoomChanged(zoom: Float) {}
    }

    var listener: Listener? = null

    private data class Node(
        val app: AppInfo,
        /** Unit lattice coords (spacing = 1); multiplied by [worldUnit] when drawing. */
        val uq: Float,
        val ur: Float
    )

    private var apps: List<AppInfo> = emptyList()
    private var nodes: List<Node> = emptyList()
    private var panX = 0f
    private var panY = 0f

    private val density = resources.displayMetrics.density
    /** Icon diameter at clusterZoom = 1 (dp → px). */
    private val baseIconDp = 54f

    /**
     * Whole-cluster zoom: multiplies **every** icon’s on-screen diameter and the
     * center-to-center gaps by the same factor so the whole field zooms as one.
     * Range ~0.45 … 2.4 (pinch / double-tap; persisted).
     */
    private var clusterZoom = 1f
    /**
     * Relative packing independent of zoom: how many icon-diameters between centers.
     * ~0.90 packed … ~1.55 loose.
     */
    private var spacingFactor = 1.08f
    private var showLabels = true
    private var labelTextSizeSp = 11f

    private val minFisheye = 0.34f
    private val maxFisheye = 1.18f
    private val minClusterZoom = 0.45f
    private val maxClusterZoom = 2.4f

    // FILTER_BITMAP: smooth when downscaling HD bitmaps to on-screen size (avoids shimmer).
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val clipPath = Path()
    private val tmpRect = RectF()

    private val scroller = OverScroller(context)
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                if (!scroller.isFinished) scroller.abortAnimation()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Zoom the whole field around the pinch focal point.
                val focusX = detector.focusX
                val focusY = detector.focusY
                val cx = width / 2f
                val cy = height / 2f
                val oldZoom = clusterZoom
                val newZoom = (oldZoom * detector.scaleFactor)
                    .coerceIn(minClusterZoom, maxClusterZoom)
                if (newZoom == oldZoom) return true

                // Keep the world point under the fingers stable while zooming.
                val worldX = (focusX - cx - panX) / oldZoom
                val worldY = (focusY - cy - panY) / oldZoom
                clusterZoom = newZoom
                panX = focusX - cx - worldX * newZoom
                panY = focusY - cy - worldY * newZoom
                clampPan()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                listener?.onClusterZoomChanged(clusterZoom)
            }
        }
    )

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.abortAnimation()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (isScaling || e2.pointerCount > 1) return false
            panX -= distanceX
            panY -= distanceY
            clampPan()
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (isScaling) return false
            val (minX, maxX, minY, maxY) = panBounds()
            scroller.fling(
                panX.roundToInt(),
                panY.roundToInt(),
                velocityX.roundToInt(),
                velocityY.roundToInt(),
                minX.roundToInt(),
                maxX.roundToInt(),
                minY.roundToInt(),
                maxY.roundToInt(),
                (48 * density).roundToInt(),
                (48 * density).roundToInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isScaling) return false
            hitTest(e.x, e.y)?.let { listener?.onAppClick(it.app) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (isScaling) return
            hitTest(e.x, e.y)?.let {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                listener?.onAppLongClick(it.app)
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double-tap cycles a couple of whole-cluster zoom levels.
            val target = when {
                clusterZoom < 0.85f -> 1f
                clusterZoom < 1.35f -> 1.6f
                else -> 1f
            }
            clusterZoom = target
            clampPan()
            invalidate()
            listener?.onClusterZoomChanged(clusterZoom)
            return true
        }
    })

    /** On-screen icon diameter at the center (before fish-eye), in px. */
    private fun iconDiameterPx(): Float =
        baseIconDp * density * clusterZoom.coerceIn(minClusterZoom, maxClusterZoom)

    /**
     * One lattice unit in px = center-to-center distance at current zoom/closeness.
     * Scaling this with [clusterZoom] is what makes the **whole** field zoom together.
     */
    private fun worldUnit(): Float =
        iconDiameterPx() * spacingFactor.coerceIn(0.88f, 1.60f)

    fun getClusterZoom(): Float = clusterZoom

    /**
     * Apply layout from Settings.
     * [clusterZoom] = whole icons + spacing zoom (pinch / double-tap).
     * [spacingFactor] = relative closeness only.
     */
    fun setAppearance(
        clusterZoom: Float = this.clusterZoom,
        spacingFactor: Float = this.spacingFactor,
        showLabels: Boolean = this.showLabels,
        labelTextSizeSp: Float = this.labelTextSizeSp
    ) {
        val zoom = clusterZoom.coerceIn(minClusterZoom, maxClusterZoom)
        val spacing = spacingFactor.coerceIn(0.88f, 1.60f)
        val labels = showLabels
        val labelSp = labelTextSizeSp.coerceIn(9f, 17f)

        val layoutChanged = this.spacingFactor != spacing
        // Zoom does not require rebuilding unit lattice — worldUnit() applies at draw time.
        this.clusterZoom = zoom
        this.spacingFactor = spacing
        this.showLabels = labels
        this.labelTextSizeSp = labelSp

        if (layoutChanged && apps.isNotEmpty()) {
            rebuildNodes()
        } else {
            clampPan()
            invalidate()
        }
    }

    fun setApps(list: List<AppInfo>) {
        apps = list
        rebuildNodes()
    }

    private fun rebuildNodes() {
        // Store unit hex positions (spacing = 1); scale with worldUnit() when drawing.
        val unitPositions = hexSpiralPositions(apps.size, spacing = 1f)
        nodes = apps.mapIndexed { i, app ->
            val (x, y) = unitPositions[i]
            Node(app, x, y)
        }
        if (nodes.isNotEmpty()) clampPan()
        invalidate()
    }

    fun recenter() {
        panX = 0f
        panY = 0f
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            invalidate()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (nodes.isEmpty() || width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val focusR = min(width, height) * 0.48f
        val unit = worldUnit()
        val base = iconDiameterPx()

        val ordered = nodes.map { node ->
            val sx = cx + node.uq * unit + panX
            val sy = cy + node.ur * unit + panY
            val dist = hypot(sx - cx, sy - cy)
            val fish = fisheyeScale(dist, focusR)
            Triple(node, sx to sy, fish)
        }.sortedBy { it.third }

        for ((node, pos, fish) in ordered) {
            val (sx, sy) = pos
            // Whole-icon size = cluster zoom × fish-eye (center icons larger).
            val size = base * fish
            val labelPad = if (showLabels) size * 0.45f else 0f
            if (sx + size < 0 || sy + size < 0 || sx - size > width || sy - size - labelPad > height) continue
            drawIcon(canvas, node.app.icon, sx, sy, size)
            if (showLabels) {
                drawLabel(canvas, node.app.label, sx, sy, size)
            }
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Bitmap, cx: Float, cy: Float, size: Float) {
        val half = size / 2f
        val shadowPad = size * 0.06f
        tmpRect.set(
            cx - half + shadowPad,
            cy - half + size * 0.08f,
            cx + half - shadowPad,
            cy + half + size * 0.1f
        )
        canvas.drawOval(tmpRect, shadowPaint)

        val save = canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, half, Path.Direction.CW)
        canvas.clipPath(clipPath)
        // Fill the whole circle — the on-screen size is the real zoom, not inner padding.
        tmpRect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawBitmap(icon, null, tmpRect, iconPaint)
        canvas.restoreToCount(save)
    }

    /**
     * @param size on-screen icon diameter in px (already includes cluster zoom × fish-eye)
     */
    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float) {
        // Tie text size to on-screen icon diameter (cluster zoom × fish-eye).
        // Zooming the whole field out shrinks labels with the icons so they stay proportional
        // instead of looking oversized/odd. [labelTextSizeSp] is the size at clusterZoom≈1.
        val baseIconPx = baseIconDp * density
        val sizeRatio = (size / baseIconPx).coerceIn(0.30f, 2.6f)
        val textSize = labelTextSizeSp * density * sizeRatio
        // Below ~5.5sp the glyph is noise under tiny icons — skip rather than draw a mess.
        if (textSize < 5.5f * density) return

        labelPaint.textSize = textSize
        val maxWidth = size * 1.35f
        val text = TextUtils.ellipsize(label, labelPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        val y = cy + size * 0.5f + textSize * 1.05f
        val oldColor = labelPaint.color
        labelPaint.color = 0x66000000
        canvas.drawText(text, cx + 0.5f, y + 0.5f, labelPaint)
        labelPaint.color = oldColor
        canvas.drawText(text, cx, y, labelPaint)
    }

    private fun fisheyeScale(dist: Float, focusR: Float): Float {
        val t = (dist / focusR).coerceAtLeast(0f)
        val w = 1f / (1f + t * t * 2.2f)
        return minFisheye + (maxFisheye - minFisheye) * w
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        // Always feed the gesture detector so pan/tap still work after a pinch.
        gesture.onTouchEvent(event)
        return true
    }

    private fun hitTest(x: Float, y: Float): Node? {
        if (nodes.isEmpty()) return null
        val cx = width / 2f
        val cy = height / 2f
        val focusR = min(width, height) * 0.48f
        val unit = worldUnit()
        val base = iconDiameterPx()
        var best: Node? = null
        var bestScore = Float.MAX_VALUE
        for (node in nodes) {
            val sx = cx + node.uq * unit + panX
            val sy = cy + node.ur * unit + panY
            val dist = hypot(sx - cx, sy - cy)
            val fish = fisheyeScale(dist, focusR)
            val r = base * fish * 0.55f
            val d = hypot(x - sx, y - sy)
            if (d <= r && d < bestScore) {
                bestScore = d
                best = node
            }
        }
        return best
    }

    private fun panBounds(): FloatArray {
        if (nodes.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        val unit = worldUnit()
        var minWx = 0f
        var maxWx = 0f
        var minWy = 0f
        var maxWy = 0f
        for (n in nodes) {
            val wx = n.uq * unit
            val wy = n.ur * unit
            minWx = min(minWx, wx)
            maxWx = max(maxWx, wx)
            minWy = min(minWy, wy)
            maxWy = max(maxWy, wy)
        }
        val base = iconDiameterPx()
        val pad = max(width, height) * 0.35f + base
        return floatArrayOf(-maxWx - pad, -minWx + pad, -maxWy - pad, -minWy + pad)
    }

    private fun clampPan() {
        val (minX, maxX, minY, maxY) = panBounds()
        panX = panX.coerceIn(minX, maxX)
        panY = panY.coerceIn(minY, maxY)
    }

    companion object {
        private val SQRT3 = sqrt(3f)

        fun hexSpiralPositions(count: Int, spacing: Float): List<Pair<Float, Float>> {
            if (count <= 0) return emptyList()
            val out = ArrayList<Pair<Float, Float>>(count)
            out.add(0f to 0f)
            if (count == 1) return out

            val dq = intArrayOf(+1, 0, -1, -1, 0, +1)
            val dr = intArrayOf(0, +1, +1, 0, -1, -1)

            var ring = 1
            while (out.size < count) {
                var q = 0
                var r = -ring
                for (side in 0 until 6) {
                    for (step in 0 until ring) {
                        if (out.size >= count) return out
                        val x = spacing * (1.5f * q)
                        val y = spacing * (SQRT3 * (r + q * 0.5f))
                        out.add(x to y)
                        q += dq[side]
                        r += dr[side]
                    }
                }
                ring++
            }
            return out
        }
    }
}
