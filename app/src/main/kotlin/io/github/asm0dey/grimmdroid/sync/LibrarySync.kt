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
package io.github.asm0dey.grimmdroid.sync

import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.api.BookSummaryDto
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.SessionExpiredException
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.CatalogDao
import io.github.asm0dey.grimmdroid.catalog.LibraryEntity
import io.github.asm0dey.grimmdroid.catalog.ShelfEntity
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

/** Server `primaryFileType` value marking an audiobook (verified against a real server in Task 16). */
const val AUDIOBOOK_FILE_TYPE = "AUDIOBOOK"

class LibrarySync(
    private val dao: CatalogDao,
    private val accounts: AccountRepository,
    private val apiFor: (AccountEntity) -> GrimmoryApi,
    private val clock: () -> Long,
) {
    private val states = MutableStateFlow<Map<Long, SyncState>>(emptyMap())
    val state: StateFlow<Map<Long, SyncState>> = states.asStateFlow()

    // Two interleaved sweeps of one account would delete each other's rows by generation, so they run one at a time.
    private val locks = ConcurrentHashMap<Long, Mutex>()

    private fun emit(
        accountId: Long,
        s: SyncState,
    ) = states.update { it + (accountId to s) }

    /** Runs [block] while no sync of [accountId] runs; account removal uses it so a sweep can't re-insert deleted rows. */
    suspend fun <T> exclusive(
        accountId: Long,
        block: suspend () -> T,
    ): T = locks.getOrPut(accountId) { Mutex() }.withLock { block() }

    /** Returns this run's final state; [state] may already show a later queued run. Unknown account ⇒ [SyncState.Idle]. */
    suspend fun sync(accountId: Long): SyncState =
        locks.getOrPut(accountId) { Mutex() }.withLock {
            val account = accounts.account(accountId) ?: return@withLock SyncState.Idle
            if (account.signedOut) return@withLock SyncState.SignedOut.also { emit(accountId, it) }
            emit(accountId, SyncState.Running(0, 0))
            val api = apiFor(account)
            val result =
                try {
                    sweep(account, api)
                    SyncState.Idle
                } catch (e: AppApiMissing) {
                    SyncState.Unsupported(versionOrNull(api))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SessionExpiredException) {
                    SyncState.SignedOut // authedClient has already marked the account signed out
                } catch (e: Exception) {
                    SyncState.Failed(e)
                }
            emit(accountId, result)
            result
        }

    private suspend fun sweep(
        account: AccountEntity,
        api: GrimmoryApi,
    ) {
        val id = account.id
        val generation = clock()
        val me =
            try {
                api.me()
            } catch (e: ClientRequestException) {
                // Only this first call decides support; a 404 later in the sweep (e.g. a shelf just deleted) is an ordinary failure.
                if (e.response.status == HttpStatusCode.NotFound) throw AppApiMissing() else throw e
            }
        accounts.updatePermissions(id, me)

        dao.replaceLibraries(id, api.libraries().map { LibraryEntity(id, it.id, it.name, it.bookCount.toInt()) })
        val shelves = api.shelves().map { it to api.bookIds(shelfId = it.id) }
        val magic = api.magicShelves().map { it to api.bookIds(magicShelfId = it.id) }
        dao.replaceShelves(id, false, shelves.map { (s, _) -> ShelfEntity(id, s.id, s.name, s.bookCount, magic = false) })
        dao.replaceShelves(id, true, magic.map { (s, ids) -> ShelfEntity(id, s.id, s.name, ids.size, magic = true) })
        shelves.forEach { (s, ids) -> dao.replaceShelfMembers(id, s.id, false, ids) }
        magic.forEach { (s, ids) -> dao.replaceShelfMembers(id, s.id, true, ids) }

        var done = 0
        var page = 0
        do {
            val res = api.books(page)
            // A misbehaving server could page forever; failing here also keeps unseen rows from being deleted.
            check(!res.hasNext || (res.content.isNotEmpty() && page + 1 < res.totalPages)) { "Inconsistent page $page" }
            dao.upsertBooks(res.content.map { it.toEntity(id, generation) })
            done += res.content.size
            emit(id, SyncState.Running(done, res.totalElements.toInt()))
            page++
        } while (res.hasNext)

        dao.deleteBooksNotIn(id, generation)
        accounts.setLastSyncedAt(id, clock())
    }
}

private class AppApiMissing : Exception()

private suspend fun versionOrNull(api: GrimmoryApi): String? =
    try {
        api.version().current
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

internal fun BookSummaryDto.toEntity(
    accountId: Long,
    generation: Long,
) = BookEntity(
    accountId = accountId,
    id = id,
    title = title.orEmpty(),
    authors = authors.joinToString(", "),
    seriesName = seriesName,
    seriesNumber = seriesNumber,
    libraryId = libraryId,
    addedOn = epochMillis(addedOn),
    lastReadTime = epochMillis(lastReadTime),
    readProgress = readProgress,
    primaryFileId = primaryFileId,
    primaryFileType = primaryFileType,
    primaryFileName = primaryFileName,
    fileSizeKb = fileSizeKb,
    coverUpdatedOn = epochMillis(coverUpdatedOn),
    isAudiobook = primaryFileType == AUDIOBOOK_FILE_TYPE,
    syncGeneration = generation,
)

// One bad timestamp must not fail the whole sync.
private fun epochMillis(iso: String?): Long? =
    try {
        iso?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (e: DateTimeParseException) {
        null
    }
