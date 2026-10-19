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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.catalog.BookQuery
import io.github.asm0dey.grimmdroid.catalog.BookRow
import io.github.asm0dey.grimmdroid.catalog.BookSort
import io.github.asm0dey.grimmdroid.catalog.LibraryEntity
import io.github.asm0dey.grimmdroid.catalog.toSql
import io.github.asm0dey.grimmdroid.settings.Layout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Limits the library to one shelf; [magic] picks a magic shelf. */
data class ShelfScope(
    val shelfId: Long,
    val magic: Boolean,
)

// Placeholder until the current account is known; nothing is queried for it.
private const val NO_ACCOUNT = -1L

// Placeholders give the fast scroller the full count; jumpThreshold makes a far scrollToItem reload around the target
// instead of paging through everything in between; maxSize drops pages far behind on a 10k-book mirror.
internal val LibraryPaging = PagingConfig(pageSize = 60, enablePlaceholders = true, jumpThreshold = 180, maxSize = 600)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val scope: ShelfScope?,
    accountId: Flow<Long?>,
    pagingSource: (SupportSQLiteQuery) -> PagingSource<Int, BookRow>,
    libraries: (accountId: Long) -> Flow<List<LibraryEntity>>,
    storedLayout: Flow<Layout>,
    private val saveLayout: suspend (Layout) -> Unit,
) : ViewModel() {
    private val _query = MutableStateFlow(base(NO_ACCOUNT))
    val query: StateFlow<BookQuery> = _query.asStateFlow()

    val books: Flow<PagingData<BookRow>> =
        _query
            .filter { it.accountId != NO_ACCOUNT }
            .flatMapLatest { q -> Pager(LibraryPaging) { pagingSource(q.toSql()) }.flow }
            .cachedIn(viewModelScope)

    val libraries: StateFlow<List<LibraryEntity>> =
        _query
            .map { it.accountId }
            .filter { it != NO_ACCOUNT }
            .distinctUntilChanged()
            .flatMapLatest(libraries)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val layout: StateFlow<Layout> = storedLayout.stateIn(viewModelScope, SharingStarted.Eagerly, Layout.GRID)

    init {
        viewModelScope.launch {
            // Library ids and filters belong to the old account; only the sort carries over.
            accountId.filterNotNull().collect { id -> _query.update { if (it.accountId == id) it else base(id).copy(sort = it.sort) } }
        }
    }

    private fun base(accountId: Long) =
        BookQuery(
            accountId,
            shelfId = scope?.takeUnless { it.magic }?.shelfId,
            magicShelfId = scope?.takeIf { it.magic }?.shelfId,
        )

    fun setLibrary(id: Long?) = _query.update { it.copy(libraryId = id) }

    fun setSort(sort: BookSort) = _query.update { it.copy(sort = sort) }

    fun setFormats(formats: Set<String>) = _query.update { it.copy(formats = formats) }

    fun setDownloadedOnly(only: Boolean) = _query.update { it.copy(downloadedOnly = only) }

    fun setLayout(layout: Layout) {
        viewModelScope.launch { saveLayout(layout) }
    }

    companion object {
        fun factory(
            container: AppContainer,
            scope: ShelfScope?,
        ) = viewModelFactory {
            initializer {
                val dao = container.db.catalogDao()
                LibraryViewModel(
                    scope,
                    container.accounts.current.map { it?.id },
                    dao::books,
                    dao::libraries,
                    container.settings.libraryLayout,
                    container.settings::setLibraryLayout,
                )
            }
        }
    }
}
