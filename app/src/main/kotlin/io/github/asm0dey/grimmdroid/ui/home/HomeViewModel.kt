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
package io.github.asm0dey.grimmdroid.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.BookRow
import io.github.asm0dey.grimmdroid.catalog.DownloadWithBook
import io.github.asm0dey.grimmdroid.ui.nav.Downloads
import io.github.asm0dey.grimmdroid.ui.nav.Library
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** [seeAll] is the route the "See all" link opens; [showProgress] draws the reading-progress bar under each cover. */
data class HomeRow(
    @param:StringRes val title: Int,
    val books: List<BookRow>,
    val seeAll: Any,
    val showProgress: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    accountId: Flow<Long?>,
    continueReading: (Long) -> Flow<List<BookEntity>>,
    recentlyAdded: (Long) -> Flow<List<BookEntity>>,
    downloads: (Long) -> Flow<List<DownloadWithBook>>,
) : ViewModel() {
    /** Empty rows are hidden, except "On this phone", whose empty state teaches where downloads show up. */
    val rows: StateFlow<List<HomeRow>> =
        accountId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyList())
                } else {
                    combine(continueReading(id), recentlyAdded(id), downloads(id)) { reading, added, saved ->
                        listOf(
                            HomeRow(R.string.continue_reading, reading.map { BookRow(it, false) }, Library, showProgress = true),
                            HomeRow(R.string.recently_added, added.map { BookRow(it, false) }, Library),
                            HomeRow(R.string.on_this_phone, saved.take(ROW_LIMIT).map { BookRow(it.book, true) }, Downloads),
                        ).filter { it.books.isNotEmpty() || it.seeAll == Downloads }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    companion object {
        private const val ROW_LIMIT = 20

        fun factory(container: AppContainer) =
            viewModelFactory {
                initializer {
                    val dao = container.db.catalogDao()
                    HomeViewModel(
                        container.accounts.current.map { it?.id },
                        { dao.continueReading(it) },
                        { dao.recentlyAdded(it) },
                        dao::downloads,
                    )
                }
            }
    }
}
