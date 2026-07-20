package com.rescue360.detector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import timber.log.Timber

/**
 * Custom View for drawing detection bounding boxes and labels
 *
 * Overlays on camera preview:
 * - Colored bounding boxes (by class)
 * - Class name + confidence score
 * - Real-time update
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private var sourceFrameWidth: Int = 0
    private var sourceFrameHeight: Int = 0
    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBackground = Paint(Paint.ANTI_ALIAS_FLAG)

    // Color map for each class
    private val classColors = mapOf(
        "hand" to Color.parseColor("#00FFFF"),      // Cyan
        "arm" to Color.parseColor("#FFA500"),       // Orange
        "head" to Color.parseColor("#FF0000"),      // Red
        "leg" to Color.parseColor("#FF00FF"),       // Magenta
        "foot" to Color.parseColor("#FFFF00"),      // Yellow
        "person" to Color.parseColor("#00FF00")     // Green
    )

    init {
        paintBox.strokeWidth = 4f
        paintBox.style = Paint.Style.STROKE
        paintBox.isAntiAlias = true

        paintText.textSize = 20f
        paintText.color = Color.WHITE
        paintText.style = Paint.Style.FILL
        paintText.textAlign = Paint.Align.LEFT
        paintText.isAntiAlias = true

        paintBackground.style = Paint.Style.FILL
        paintBackground.alpha = 200
    }

    /**
     * Set detections to display
     */
    fun setDetections(detections: List<Detection>) {
        this.detections = detections
        invalidate()  // Request redraw
    }

    fun setSourceFrameSize(width: Int, height: Int) {
        sourceFrameWidth = width
        sourceFrameHeight = height
    }

    /**
     * Draw boxes and labels
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty()) {
            return
        }

        for (detection in detections) {
            drawDetection(canvas, detection)
        }
    }

    /**
     * Draw single detection box
     */
    private fun drawDetection(canvas: Canvas, detection: Detection) {
        try {
            // Get color for this class
            val color = classColors[detection.className] ?: Color.WHITE
            paintBox.color = color

            val box = scaleBoxToView(detection.bbox)

            // Draw bounding box
            canvas.drawRect(
                box,
                paintBox
            )

            // Draw label background
            val label = "${detection.className} ${(detection.confidence * 100).toInt()}%"
            val textBounds = Rect()
            paintText.getTextBounds(label, 0, label.length, textBounds)

            val bgPadding = 8f
            val bgX = box.left - bgPadding
            val bgY = box.top - textBounds.height() - bgPadding * 2
            val bgWidth = textBounds.width() + bgPadding * 2
            val bgHeight = textBounds.height() + bgPadding * 2

            paintBackground.color = color
            paintBackground.alpha = 200
            canvas.drawRect(
                bgX, bgY, bgX + bgWidth, bgY + bgHeight,
                paintBackground
            )

            // Draw label text
            paintText.color = Color.WHITE
            canvas.drawText(
                label,
                box.left, box.top - bgPadding,
                paintText
            )

            Timber.v("Drew box for ${detection.className} at (${box.left.toInt()},${box.top.toInt()})")

        } catch (e: Exception) {
            Timber.e(e, "Error drawing detection")
        }
    }

    private fun scaleBoxToView(bbox: FloatArray): RectF {
        val rawX1 = bbox.getOrNull(0) ?: 0f
        val rawY1 = bbox.getOrNull(1) ?: 0f
        val rawX2 = bbox.getOrNull(2) ?: 0f
        val rawY2 = bbox.getOrNull(3) ?: 0f

        val valuesLookNormalized = bbox.all { it in 0f..1.05f }

        val scaleX: Float
        val scaleY: Float

        if (valuesLookNormalized) {
            scaleX = width.toFloat()
            scaleY = height.toFloat()
        } else {
            scaleX = if (sourceFrameWidth > 0) width.toFloat() / sourceFrameWidth else 1f
            scaleY = if (sourceFrameHeight > 0) height.toFloat() / sourceFrameHeight else 1f
        }

        val left = (rawX1 * scaleX).coerceIn(0f, width.toFloat())
        val top = (rawY1 * scaleY).coerceIn(0f, height.toFloat())
        val right = (rawX2 * scaleX).coerceIn(0f, width.toFloat())
        val bottom = (rawY2 * scaleY).coerceIn(0f, height.toFloat())

        return RectF(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom)
        )
    }
}

/**
 * Detection data class
 */
data class Detection(
    val classId: Int,
    val className: String,           // "hand", "arm", "head", "leg", "foot", "person"
    val confidence: Float,            // 0.0 - 1.0
    val bbox: FloatArray              // [x1, y1, x2, y2]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Detection

        if (classId != other.classId) return false
        if (className != other.className) return false
        if (confidence != other.confidence) return false
        if (!bbox.contentEquals(other.bbox)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = classId
        result = 31 * result + className.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + bbox.contentHashCode()
        return result
    }
}
