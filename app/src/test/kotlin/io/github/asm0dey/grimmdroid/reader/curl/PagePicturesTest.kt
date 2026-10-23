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

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PagePicturesTest {
    @Test
    fun pageIndexRoundsToTheNearestPage() {
        assertEquals(2, pageIndex(2159, 1080))
        assertEquals(1, pageIndex(1080, 1080))
    }

    @Test
    fun blankBitmapIsBlankTextIsNot() {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertTrue(bitmap.isBlank())
        bitmap.setPixel(50, 70, Color.BLACK) // (5, 7) on the 16×16 grid of a 160-pixel bitmap
        assertFalse(bitmap.isBlank())
    }

    @Test
    fun invalidateDropsAllPictures() {
        val pictures = PagePictures(FrameLayout(ApplicationProvider.getApplicationContext()))
        val before = pictures.generation
        pictures.invalidate()
        Side.entries.forEach { assertNull(pictures[it]) }
        assertTrue(pictures.generation > before)
    }
}
