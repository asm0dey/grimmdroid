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

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalLinkTest {
    @Test
    fun onlyWebAndMailLinksLeaveTheReader() {
        val opened =
            listOf(
                "https://example.com/a",
                "http://example.com",
                "HTTPS://EXAMPLE.COM",
                "mailto:a@example.com",
                "file:///sdcard/secret.txt",
                "market://details?id=x",
                "intent://scan/#Intent;scheme=zxing;end",
                "javascript:alert(1)",
                "content://media/1",
                "no-scheme",
            ).filter(::opensOutside)
        assertEquals(listOf("https://example.com/a", "http://example.com", "HTTPS://EXAMPLE.COM", "mailto:a@example.com"), opened)
    }
}
