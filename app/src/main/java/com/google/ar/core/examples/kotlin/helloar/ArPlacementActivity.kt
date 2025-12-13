package com.google.ar.core.examples.kotlin.helloar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple activity to visualize and evaluate rectangular placements
 * using OverlayView.
 *
 * Layout expectation (activity_ar_placement.xml):
 *
 * - EditText  @+id/editExpected
 * - EditText  @+id/editPlaced
 * - Button    @+id/btnEvaluate
 * - OverlayView @+id/overlayView
 */
class ArPlacementActivity : AppCompatActivity() {

    private lateinit var overlayView: OverlayView
    private lateinit var expectedInput: EditText
    private lateinit var placedInput: EditText
    private lateinit var evaluateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make sure this XML exists and has the IDs listed in the KDoc above
        setContentView(R.layout.activity_ar_placement)

        overlayView = findViewById(R.id.overlayView)
        expectedInput = findViewById(R.id.editExpected)
        placedInput = findViewById(R.id.editPlaced)
        evaluateButton = findViewById(R.id.btnEvaluate)

        evaluateButton.setOnClickListener {
            val expectedText = expectedInput.text.toString()
            val placedText = placedInput.text.toString()

            val expectedRects = parseCoordinates(expectedText)
            val placedRects = parseCoordinates(placedText)
            val evalResults = evaluateRects(expectedRects, placedRects)

            overlayView.update(expectedRects, placedRects, evalResults)
        }
    }

    /**
     * Parses user text into a list of RectData.
     *
     * Supported per-rectangle formats (one per line OR separated by ';'):
     *   x,y,w,h
     *   x y w h
     *
     * Example:
     *   10,20,100,80
     *   50 60 120 90
     */
    private fun parseCoordinates(input: String): List<RectData> {
        return input
            .split('\n', ';')
            .mapNotNull { line ->
                val cleaned = line.trim()
                if (cleaned.isEmpty()) return@mapNotNull null

                val parts = cleaned
                    .split(',', ' ', '\t')
                    .filter { it.isNotBlank() }

                if (parts.size < 4) return@mapNotNull null

                val x = parts[0].toFloatOrNull()
                val y = parts[1].toFloatOrNull()
                val w = parts[2].toFloatOrNull()
                val h = parts[3].toFloatOrNull()

                if (x == null || y == null || w == null || h == null) {
                    return@mapNotNull null
                }

                RectData(x, y, w, h)
            }
    }

    /**
     * Simple evaluation: matches placed rects to expected rects by index
     * and checks position, size, and IoU against basic thresholds.
     */
    private fun evaluateRects(
        expected: List<RectData>,
        placed: List<RectData>
    ): List<CoordinateChecker.EvalResult> {

        val results = mutableListOf<CoordinateChecker.EvalResult>()

        placed.forEachIndexed { index, placedRect ->
            val expectedRect = expected.getOrNull(index)

            if (expectedRect == null) {
                // No expected rect at this index
                results.add(
                    CoordinateChecker.EvalResult(
                        matchedExpectedIndex = null,
                        isPositionOk = false,
                        isSizeOk = false,
                        isIoUOk = false
                    )
                )
            } else {
                val isPositionOk = isPositionClose(expectedRect, placedRect)
                val isSizeOk = isSizeSimilar(expectedRect, placedRect)
                val iou = computeIoU(expectedRect, placedRect)
                val isIoUOk = iou >= 0.5f   // threshold; adjust if needed

                results.add(
                    CoordinateChecker.EvalResult(
                        matchedExpectedIndex = index,
                        isPositionOk = isPositionOk,
                        isSizeOk = isSizeOk,
                        isIoUOk = isIoUOk
                    )
                )
            }
        }

        return results
    }

    private fun isPositionClose(a: RectData, b: RectData): Boolean {
        val axCenter = a.x + a.w / 2f
        val ayCenter = a.y + a.h / 2f
        val bxCenter = b.x + b.w / 2f
        val byCenter = b.y + b.h / 2f

        val dx = axCenter - bxCenter
        val dy = ayCenter - byCenter

        val maxAllowedOffset = (a.w.coerceAtLeast(a.h)) * 0.25f
        return (dx * dx + dy * dy) <= maxAllowedOffset * maxAllowedOffset
    }

    private fun isSizeSimilar(a: RectData, b: RectData): Boolean {
        if (a.w == 0f || a.h == 0f) return false
        val wRatio = b.w / a.w
        val hRatio = b.h / a.h
        // Accept if both width and height within 50%–150% of expected
        return wRatio in 0.5f..1.5f && hRatio in 0.5f..1.5f
    }

    private fun computeIoU(a: RectData, b: RectData): Float {
        val ax2 = a.x + a.w
        val ay2 = a.y + a.h
        val bx2 = b.x + b.w
        val by2 = b.y + b.h

        val interLeft = maxOf(a.x, b.x)
        val interTop = maxOf(a.y, b.y)
        val interRight = minOf(ax2, bx2)
        val interBottom = minOf(ay2, by2)

        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH

        val areaA = a.w * a.h
        val areaB = b.w * b.h
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f

        return interArea / union
    }
}
