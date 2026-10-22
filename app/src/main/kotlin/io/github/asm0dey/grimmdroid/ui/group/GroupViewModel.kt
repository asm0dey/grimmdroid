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
package io.github.asm0dey.grimmdroid.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.nextToRead
import io.github.asm0dey.grimmdroid.catalog.toGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class GroupPage(
    val title: String,
    val readCount: Int,
    val total: Int,
    val sections: List<Section>,
)

/** [seriesName] is null for the books outside any series, which have no [next]. */
data class Section(
    val seriesName: String?,
    val books: List<BookEntity>,
    val next: BookEntity?,
)

/** A series page, or with [byAuthor] an author's books split by series. */
class GroupViewModel(
    books: Flow<List<BookEntity>>,
    title: String,
    byAuthor: Boolean,
) : ViewModel() {
    val page: StateFlow<GroupPage?> =
        books
            .map { list ->
                val all = list.toGroup(title)
                val sections =
                    if (byAuthor) {
                        val (inSeries, rest) = list.partition { it.seriesName != null }
                        inSeries
                            .groupBy { it.seriesName!! }
                            .toSortedMap(String.CASE_INSENSITIVE_ORDER.thenBy { it })
                            .map { (name, books) -> section(name, books) } +
                            listOfNotNull(
                                rest.takeIf { it.isNotEmpty() }?.let { r ->
                                    Section(null, r.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }), null)
                                },
                            )
                    } else {
                        listOf(section(title, list))
                    }
                GroupPage(title, all.readCount, all.total, sections)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun section(
        name: String,
        books: List<BookEntity>,
    ) = books.toGroup(name).let { Section(name, it.books, nextToRead(it.books)) }

    companion object {
        fun factory(
            container: AppContainer,
            accountId: Long,
            name: String,
            byAuthor: Boolean,
        ) = viewModelFactory {
            initializer {
                val dao = container.db.catalogDao()
                GroupViewModel(if (byAuthor) dao.authorBooks(accountId, name) else dao.seriesBooks(accountId, name), name, byAuthor)
            }
        }
    }
}
