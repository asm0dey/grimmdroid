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
package io.github.asm0dey.grimmdroid.ui.details

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailsHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    private val book =
        BookEntity(1, 7, "Fellowship", "Tolkien, J.R.R., X", "LotR", 1f, null, null, null, null, 42, "EPUB", null, null, null, false, 1)

    @Test
    fun authorAndSeriesOpenTheirPages() {
        val authors = mutableListOf<String>()
        val series = mutableListOf<String>()
        compose.setContent {
            Header(book, listOf("Tolkien, J.R.R.", "X"), null, false, null, authors::add, series::add)
        }
        compose.onNodeWithText("Tolkien, J.R.R.").performClick()
        compose.onNodeWithText("LotR #1", substring = true).performClick()
        assertEquals(listOf("Tolkien, J.R.R."), authors)
        assertEquals(listOf("LotR"), series)
    }

    @Test
    fun withoutAuthorRowsTheAuthorsTextIsPlain() {
        val authors = mutableListOf<String>()
        compose.setContent { Header(book, emptyList(), null, false, null, authors::add) {} }
        compose.onNodeWithText("Tolkien, J.R.R., X").performClick()
        assertEquals(emptyList<String>(), authors)
    }
}
