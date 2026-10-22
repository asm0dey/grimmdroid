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
package io.github.asm0dey.grimmdroid.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.catalog.AuthorBook
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.Group
import io.github.asm0dey.grimmdroid.catalog.toGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

enum class LibraryMode { BOOKS, AUTHORS, SERIES }

private val ByName = compareBy(String.CASE_INSENSITIVE_ORDER, Group::name).thenBy { it.name }

/** The groups listed for [mode]; [groups] is null while that mode's data loads, so a switch never shows another mode's list. */
data class GroupListing(
    val mode: LibraryMode,
    val groups: List<Group>?,
)

/** The Library tab's author and series lists; [libraryId] mirrors the library filter of the books view. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModel(
    seriesBooks: Flow<List<BookEntity>>,
    authorPairs: Flow<List<AuthorBook>>,
) : ViewModel() {
    val mode = MutableStateFlow(LibraryMode.BOOKS)
    val notStartedOnly = MutableStateFlow(false)
    val libraryId = MutableStateFlow<Long?>(null)

    private val all: Flow<GroupListing> =
        mode.flatMapLatest { mode ->
            val groups: Flow<List<Group>?> =
                when (mode) {
                    LibraryMode.BOOKS -> {
                        flowOf(emptyList())
                    }

                    LibraryMode.SERIES -> {
                        seriesBooks.map { books -> books.groupBy { it.seriesName!! }.map { (n, b) -> b.toGroup(n) } }
                    }

                    LibraryMode.AUTHORS -> {
                        authorPairs.map { pairs ->
                            pairs.groupBy({ it.name }, { it.book }).map { (n, b) -> b.toGroup(n) }
                        }
                    }
                }
            groups.onStart { emit(null) }.map { GroupListing(mode, it) }
        }

    // The library only picks which groups are listed; counts still cover every book in the group.
    val listing: StateFlow<GroupListing> =
        combine(all, libraryId, notStartedOnly) { listing, lib, notStarted ->
            listing.copy(
                groups =
                    listing.groups
                        ?.filter { g -> (lib == null || g.books.any { it.libraryId == lib }) && !(notStarted && g.started) }
                        ?.sortedWith(ByName),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GroupListing(LibraryMode.BOOKS, emptyList()))

    companion object {
        fun factory(container: AppContainer) =
            viewModelFactory {
                initializer {
                    val dao = container.db.catalogDao()
                    val account =
                        container.accounts.current
                            .map { it?.id }
                            .filterNotNull()
                            .distinctUntilChanged()
                    GroupsViewModel(account.flatMapLatest(dao::seriesBooksAll), account.flatMapLatest(dao::authorBookPairs))
                }
            }
    }
}
