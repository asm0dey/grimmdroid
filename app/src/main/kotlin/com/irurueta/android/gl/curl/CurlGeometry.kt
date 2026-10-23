/*
 * Grimmdroid - Android client for the Grimmory book server
 * Copyright (C) 2026 Grimmdroid contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.irurueta.android.gl.curl

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One-page curl geometry, in renderer coordinates (page rect `pageLeft..pageRight`).
 *
 * The fold sits halfway between the drag's anchor and the finger, so the turning page's edge stays
 * under the finger. Forward, the anchor is the drag start on the right edge; backward, it is
 * (page right, drag start y), so the incoming page's edge follows the finger the same way.
 */
internal object CurlGeometry {
    data class Fold(val x: Float, val y: Float, val dirX: Float, val dirY: Float, val radius: Double)

    data class Release(val completed: Boolean, val targetX: Float)

    /**
     * Eased fraction (0..1) of the finishing animation after [elapsedNanos], smoothstep from 0 to 1
     * over [durationMs]. No duration means already finished.
     */
    fun finishProgress(elapsedNanos: Long, durationMs: Int): Float {
        if (durationMs <= 0) return 1f
        val p = (elapsedNanos.toDouble() / (durationMs * 1_000_000.0)).coerceIn(0.0, 1.0).toFloat()
        return p * p * (3f - 2f * p)
    }

    fun fold(pointerX: Float, pointerY: Float, anchorX: Float, anchorY: Float, radius: Double, pageLeft: Float): Fold {
        val dirX = pointerX - anchorX
        val dirY = pointerY - anchorY
        val dist = sqrt(dirX * dirX + dirY * dirY)
        if (dist == 0f) return Fold(pointerX, pointerY, dirX, dirY, clampRadius(radius, pointerX, pageLeft))

        // The library's rule: dragged close to a page width beyond the left edge, the cylinder flattens.
        val pageWidth = anchorX - pageLeft
        var r = radius
        var curlLen = r * PI
        if (dist > pageWidth * 2.0 - curlLen) {
            curlLen = max(pageWidth * 2.0 - dist, 0.0)
            r = curlLen / PI
        }
        // Halfway, less half the cylinder length; below one cylinder length, the library's sine rule.
        val shift = if (dist >= curlLen) -(dist - curlLen) / 2.0 else r * sin(PI * sqrt(dist / curlLen))
        val x = pointerX + (dirX * shift / dist).toFloat()
        val y = pointerY + (dirY * shift / dist).toFloat()
        return Fold(x, y, dirX, dirY, clampRadius(r, x, pageLeft))
    }

    /** Past the middle the turn completes; the target puts the fold on the left edge, or on the right edge. */
    fun release(pointerX: Float, forward: Boolean, pageLeft: Float, pageRight: Float): Release {
        val middle = (pageLeft + pageRight) / 2f
        val completed = if (forward) pointerX < middle else pointerX > middle
        val foldOnLeft = completed == forward
        return Release(completed, if (foldOnLeft) pageLeft - (pageRight - pageLeft) else pageRight)
    }

    private fun clampRadius(radius: Double, foldX: Float, pageLeft: Float) = max(min((foldX - pageLeft).toDouble(), radius), 0.0)
}
