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
package io.github.asm0dey.grimmdroid.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PictureHitTest {
    @Test
    fun aBookPictureMapsToItsPathInTheBook() {
        val (href, alt) = parsePictureHit("""{"src":"https://readium_package/OEBPS/images/map%20one.jpg","alt":"The map"}""")!!
        assertEquals("OEBPS/images/map%20one.jpg", href.toString())
        assertEquals("The map", alt)
    }

    @Test
    fun blankAltIsNoDescription() {
        assertNull(parsePictureHit("""{"src":"https://readium_package/a.png","alt":""}""")!!.second)
        assertNull(parsePictureHit("""{"src":"https://readium_package/a.png","alt":null}""")!!.second)
    }

    @Test
    fun noPictureOrOneFromOutsideTheBookIsNothing() {
        assertNull(parsePictureHit("null"))
        assertNull(parsePictureHit(null))
        assertNull(parsePictureHit("""{"src":"https://example.com/a.png"}"""))
        assertNull(parsePictureHit("""{"src":"data:image/png;base64,AAAA"}"""))
    }
}
