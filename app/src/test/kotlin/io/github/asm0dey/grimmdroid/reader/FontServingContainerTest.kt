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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.resource.Resource
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class FontServingContainerTest {
    @Test
    fun servesFileUnderPrefixAndDelegatesTheRest() =
        runBlocking {
            val empty = Files.createTempDirectory("fonts-a").toFile()
            val dir = Files.createTempDirectory("fonts-b").toFile()
            val font = dir.resolve("Foo.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val chapter = Url("chapter.xhtml")!!
            val chapterResource = FileResource(font)
            val asked = mutableListOf<Url>()
            val inner =
                object : Container<Resource> {
                    override val entries = setOf(chapter)

                    override fun get(url: Url): Resource? {
                        asked += url
                        return chapterResource.takeIf { url == chapter }
                    }

                    override fun close() {}
                }
            val container = FontServingContainer(inner, listOf(empty, dir))

            assertArrayEquals(byteArrayOf(1, 2, 3), container[fontUrl(font)]!!.read().getOrNull())
            assertEquals(emptyList<Url>(), asked)

            assertSame(chapterResource, container[chapter])
            assertEquals(listOf(chapter), asked)

            assertNull(container[AbsoluteUrl(FONT_URL_PREFIX + "..%2Fsecret.ttf")!!])
            assertNull(container[AbsoluteUrl(FONT_URL_PREFIX + "../secret.ttf")!!])
            assertNull(container[AbsoluteUrl(FONT_URL_PREFIX + "Missing.ttf")!!])
        }
}
