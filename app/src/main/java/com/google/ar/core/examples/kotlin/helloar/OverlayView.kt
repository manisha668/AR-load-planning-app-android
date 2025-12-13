package com.google.ar.core.examples.kotlin.helloar

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val expectedPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#6d28d9")
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }
    private val wrongPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#ef4444")
    }
    private val correctPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#059669")
    }
    private val fillCorrect = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#059669")
        alpha = 40
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
    }

    var expectedRects: List<RectData> = emptyList()
    var placedRects: List<RectData> = emptyList()
    var results: List<CoordinateChecker.EvalResult> = emptyList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Expected rectangles (dashed purple)
        expectedRects.forEach { r ->
            val rect = RectF(r.x, r.y, r.x + r.w, r.y + r.h)
            canvas.drawRect(rect, expectedPaint)
        }

        // Placed rectangles (green/red + label)
        placedRects.forEachIndexed { idx, r ->
            val rect = RectF(r.x, r.y, r.x + r.w, r.y + r.h)
            val res = results.getOrNull(idx)
            if (res == null || res.matchedExpectedIndex == null) {
                canvas.drawRect(rect, wrongPaint)
                canvas.drawText("${idx + 1} ✕", rect.left + 8f, rect.top + 36f, wrongPaint)
            } else {
                if (res.isPositionOk && res.isSizeOk && res.isIoUOk) {
                    canvas.drawRect(rect, correctPaint)
                    canvas.drawRect(rect, fillCorrect)
                    canvas.drawText("${idx + 1} ✓", rect.left + 8f, rect.top + 36f, labelPaint)
                } else {
                    canvas.drawRect(rect, wrongPaint)
                    canvas.drawText("${idx + 1} ✕", rect.left + 8f, rect.top + 36f, wrongPaint)
                }
            }
        }
    }

    fun update(
        expected: List<RectData>,
        placed: List<RectData>,
        res: List<CoordinateChecker.EvalResult>
    ) {
        expectedRects = expected
        placedRects = placed
        results = res
        invalidate()
    }
}
