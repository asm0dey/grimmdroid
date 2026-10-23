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
package io.github.asm0dey.grimmdroid.reader.curl

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real pixels, so drawn pages aren't blank
class PagePicturesTest {
    @Test
    fun pageIndexRoundsToTheNearestPage() {
        assertEquals(2, pageIndex(2159, 1080))
        assertEquals(1, pageIndex(1080, 1080))
    }

    @Test
    fun pageCountRoundsLikeReadium() {
        assertEquals(3, pageCount(3239, 1080))
        assertEquals(3, pageCount(3240, 1080))
        assertEquals(3, pageCount(3779, 1080))
        assertEquals(4, pageCount(3780, 1080))
    }

    @Test
    fun blankBitmapIsBlankTextIsNot() {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertTrue(bitmap.isBlank())
        bitmap.setPixel(50, 70, Color.BLACK) // (5, 7) on the 16×16 grid of a 160-pixel bitmap
        assertFalse(bitmap.isBlank())
    }

    @Test
    fun refreshDrawsPagesAndInvalidateDropsThem() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val web = TwoPageWebView(activity)
        val container = FrameLayout(activity).apply { addView(web) }
        activity.setContentView(container)
        container.measure(exactly(WIDTH), exactly(HEIGHT))
        container.layout(0, 0, WIDTH, HEIGHT)
        // WebView hands its frame to the WebView provider, which Robolectric stubs out; set it directly.
        web.right = WIDTH
        web.bottom = HEIGHT
        val pictures = PagePictures(container)

        pictures.refresh()
        assertNotNull(pictures[Side.CURRENT])
        assertNotNull(pictures[Side.NEXT])
        assertNull(pictures[Side.PREVIOUS]) // page 0 with no chapter before it
        val refreshed = pictures.generation

        pictures.invalidate()
        Side.entries.forEach { assertNull(pictures[it]) }
        assertTrue(pictures.generation > refreshed)
    }

    /** A chapter two pages wide whose every page paints a dark mark on white. */
    private class TwoPageWebView(
        context: Context,
    ) : WebView(context) {
        override fun computeHorizontalScrollRange() = 2 * width

        override fun draw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            for (page in 0 until 2) canvas.drawRect(page * width + 10f, 10f, page * width + 60f, 60f, paint)
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 480
    }
}
