package com.google.ar.core.examples.kotlin.helloar

/**
 * Stores the allowed 3D placement region (in X–Z plane) and also
 * defines types used for 2D overlay evaluation / OverlayView.
 */

// 3D allowed region for AR placement
data class CoordinateRegion(
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float
)

object CoordinateChecker {

    // === Part 1: AR placement region ===

    private var region: CoordinateRegion? = null

    /** Set the allowed region using two corners in X–Z plane. */
    fun setRegion(x1: Float, z1: Float, x2: Float, z2: Float) {
        region = CoordinateRegion(
            minX = minOf(x1, x2),
            maxX = maxOf(x1, x2),
            minZ = minOf(z1, z2),
            maxZ = maxOf(z1, z2),
        )
    }

    /** Returns true if (x,z) lies inside the allowed region. */
    fun isWithin(x: Float, z: Float): Boolean {
        val r = region ?: return false  // If region not set, treat as invalid.
        return x in r.minX..r.maxX && z in r.minZ..r.maxZ
    }

    // === Part 2: types used by OverlayView (for evaluation of rectangles) ===

    /**
     * Result of comparing a placed rectangle with expected rectangles.
     * OverlayView only needs to know:
     *  - which expected rect it matched (if any)
     *  - if position, size and IoU are acceptable.
     */
    data class EvalResult(
        val matchedExpectedIndex: Int?,  // null if no match
        val isPositionOk: Boolean,
        val isSizeOk: Boolean,
        val isIoUOk: Boolean
    )
}
