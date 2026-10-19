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
package io.github.asm0dey.grimmdroid.catalog

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CatalogDaoTest {
    private lateinit var db: CatalogDb
    private lateinit var dao: CatalogDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CatalogDb::class.java).allowMainThreadQueries().build()
        dao = db.catalogDao()
    }

    @After
    fun tearDown() = db.close()

    private fun book(
        id: Long,
        title: String = "T$id",
        account: Long = 1,
        authors: String = "A",
        series: String? = null,
        seriesNo: Float? = null,
        library: Long? = null,
        added: Long? = id,
        type: String? = "EPUB",
        generation: Long = 1,
        lastRead: Long? = null,
        progress: Float? = null,
    ) = BookEntity(
        account,
        id,
        title,
        authors,
        series,
        seriesNo,
        library,
        added,
        lastRead,
        progress,
        null,
        type,
        null,
        null,
        null,
        type == "AUDIOBOOK",
        generation,
    )

    private fun ids(q: BookQuery) = rows(q).map { it.book.id }

    private fun rows(q: BookQuery): List<BookRow> = load(dao.books(q.toSql()))

    private fun load(source: PagingSource<Int, BookRow>): List<BookRow> =
        runBlocking {
            val r = source.load(PagingSource.LoadParams.Refresh(null, 100, false))
            (r as PagingSource.LoadResult.Page).data
        }

    @Test
    fun accountsAreIsolated() =
        runBlocking {
            dao.upsertBooks(listOf(book(1), book(2, account = 2)))
            assertEquals(listOf(1L), ids(BookQuery(1)))
            assertEquals(listOf(2L), ids(BookQuery(2)))
        }

    @Test
    fun libraryFilter() =
        runBlocking {
            dao.upsertBooks(listOf(book(1, library = 10), book(2, library = 11), book(3)))
            assertEquals(listOf(1L), ids(BookQuery(1, libraryId = 10)))
        }

    @Test
    fun shelfAndMagicShelfFilterViaCrossRef() =
        runBlocking {
            dao.upsertBooks(listOf(book(1), book(2), book(3)))
            dao.replaceShelfMembers(1, 7, false, listOf(1, 2))
            dao.replaceShelfMembers(1, 7, true, listOf(3))
            assertEquals(listOf(2L, 1L), ids(BookQuery(1, shelfId = 7)))
            assertEquals(listOf(3L), ids(BookQuery(1, magicShelfId = 7)))
            dao.replaceShelfMembers(1, 7, false, listOf(2))
            assertEquals(listOf(2L), ids(BookQuery(1, shelfId = 7)))
        }

    @Test
    fun downloadedOnlyAndFlag() =
        runBlocking {
            dao.upsertBooks(listOf(book(1), book(2), book(3)))
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO DownloadEntity VALUES (1,1,5,'f','p',1,'DONE',1.0),(1,2,6,'f','p',1,'RUNNING',0.5)",
            )
            assertEquals(listOf(1L), ids(BookQuery(1, downloadedOnly = true)))
            assertEquals(listOf(true, false, false), rows(BookQuery(1, sort = BookSort.TITLE)).map { it.downloaded })
        }

    @Test
    fun sorts() =
        runBlocking {
            dao.upsertBooks(
                listOf(
                    book(1, title = "banana", authors = "Zed", series = "S", seriesNo = 2f, added = 10),
                    book(2, title = "Apple", authors = "amy", series = "S", seriesNo = 1f, added = 30),
                    book(3, title = "cherry", authors = "Bob", series = "R", seriesNo = 5f, added = 20),
                ),
            )
            assertEquals(listOf(2L, 3L, 1L), ids(BookQuery(1, sort = BookSort.ADDED)))
            assertEquals(listOf(2L, 1L, 3L), ids(BookQuery(1, sort = BookSort.TITLE)))
            assertEquals(listOf(2L, 3L, 1L), ids(BookQuery(1, sort = BookSort.AUTHOR)))
            assertEquals(listOf(3L, 2L, 1L), ids(BookQuery(1, sort = BookSort.SERIES)))
            dao.upsertBooks(listOf(book(4, added = 1)))
            assertEquals(4L, ids(BookQuery(1, sort = BookSort.SERIES)).last())
        }

    @Test
    fun formatFilterBindsValues() =
        runBlocking {
            dao.upsertBooks(listOf(book(1, type = "EPUB"), book(2, type = "PDF")))
            assertEquals(listOf(2L), ids(BookQuery(1, formats = setOf("PDF", "CBX"))))
            assertEquals(emptyList<Long>(), ids(BookQuery(1, formats = setOf("EPUB'; DROP TABLE BookEntity;--"))))
            assertEquals(2, ids(BookQuery(1)).size)
        }

    @Test
    fun ftsFindsByAuthorPrefixAndTracksTitleUpdates() =
        runBlocking {
            dao.upsertBooks(listOf(book(1, title = "Moby-Dick", authors = "Herman Melville"), book(2, title = "Other")))
            assertEquals(listOf(1L), load(dao.search(1, "mel*")).map { it.book.id })
            assertEquals(emptyList<Long>(), load(dao.search(2, "mel*")).map { it.book.id })
            dao.upsertBooks(listOf(book(1, title = "Whale", authors = "Herman Melville")))
            assertEquals(listOf(1L), load(dao.search(1, "whale")).map { it.book.id })
            assertEquals(emptyList<Long>(), load(dao.search(1, "moby")).map { it.book.id })
            dao.deleteBooksNotIn(1, 2)
            assertEquals(emptyList<Long>(), load(dao.search(1, "whale")).map { it.book.id })
        }

    @Test
    fun deleteBooksNotInKeepsCurrentGenerationAndOtherAccounts() =
        runBlocking {
            dao.upsertBooks(listOf(book(1, generation = 1), book(2, generation = 2), book(3, account = 2, generation = 1)))
            dao.deleteBooksNotIn(1, 2)
            assertEquals(listOf(2L), ids(BookQuery(1)))
            assertEquals(listOf(3L), ids(BookQuery(2)))
        }

    @Test
    fun homeShelvesAndSingleBook() =
        runBlocking {
            dao.upsertBooks(
                listOf(
                    book(1, lastRead = 5, progress = 50f),
                    book(2, lastRead = 9, progress = 10f),
                    book(3, lastRead = 7, progress = 100f),
                    book(4),
                ),
            )
            assertEquals(listOf(2L, 1L), dao.continueReading(1).first().map { it.id })
            assertEquals(listOf(4L, 3L), dao.recentlyAdded(1, 2).first().map { it.id })
            assertEquals("T1", dao.book(1, 1).first()?.title)
            assertNull(dao.book(2, 1).first())
        }

    @Test
    fun deleteAccountRemovesEverythingForThatAccountOnly() =
        runBlocking {
            dao.upsertBooks(listOf(book(1), book(1, account = 2)))
            dao.upsertLibraries(listOf(LibraryEntity(1, 1, "L", 1), LibraryEntity(2, 1, "L", 1)))
            dao.upsertShelves(listOf(ShelfEntity(1, 1, "S", 1, false), ShelfEntity(2, 1, "S", 1, false)))
            dao.replaceShelfMembers(1, 1, false, listOf(1))
            dao.replaceShelfMembers(2, 1, false, listOf(1))
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO DownloadEntity VALUES (1,1,5,'f','p',1,'DONE',1.0),(2,1,5,'f','p',1,'DONE',1.0)",
            )
            dao.deleteAccount(1)
            val sqlite = db.openHelper.readableDatabase
            for (t in listOf("BookEntity", "LibraryEntity", "ShelfEntity", "ShelfBookCrossRef", "DownloadEntity")) {
                sqlite.query("SELECT accountId FROM $t").use {
                    assertEquals(t, 1, it.count)
                    it.moveToFirst()
                    assertEquals(t, 2L, it.getLong(0))
                }
            }
        }

    private fun assertInvalidatedBy(
        source: PagingSource<Int, BookRow>,
        mutate: suspend () -> Unit,
    ) = runBlocking {
        load(source) // registers Room's table observer
        val fired = CountDownLatch(1)
        source.registerInvalidatedCallback { fired.countDown() }
        mutate()
        assertTrue(fired.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun booksPagingInvalidatesOnEachObservedTable() {
        val q = BookQuery(1).toSql()
        assertInvalidatedBy(dao.books(q)) { dao.upsertBooks(listOf(book(1))) }
        assertInvalidatedBy(dao.books(q)) { dao.replaceShelfMembers(1, 1, false, listOf(1)) }
        assertInvalidatedBy(dao.books(q)) {
            // runInTransaction makes Room's tracker refresh on commit; raw execSQL alone would not.
            db.runInTransaction {
                db.openHelper.writableDatabase.execSQL("INSERT INTO DownloadEntity VALUES (1,1,5,'f','p',1,'DONE',1.0)")
            }
        }
    }

    @Test
    fun searchPagingInvalidatesOnBookChange() {
        assertInvalidatedBy(dao.search(1, "x*")) { dao.upsertBooks(listOf(book(1))) }
    }

    @Test
    fun searchRowsCarryDownloadedFlag() =
        runBlocking {
            dao.upsertBooks(listOf(book(1, title = "Dune"), book(2, title = "Dunes")))
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO DownloadEntity VALUES (1,1,5,'f','p',1,'${DownloadState.DONE.name}',1.0)",
            )
            assertEquals(listOf(true, false), load(dao.search(1, "dune*")).map { it.downloaded })
        }

    @Test
    fun replaceLibrariesDropsRemovedOnes() =
        runBlocking {
            dao.replaceLibraries(1, listOf(LibraryEntity(1, 1, "A", 1), LibraryEntity(1, 2, "B", 1)))
            dao.replaceLibraries(2, listOf(LibraryEntity(2, 1, "A", 1)))
            dao.replaceLibraries(1, listOf(LibraryEntity(1, 2, "B2", 3)))
            assertEquals(listOf(1L to 2L, 2L to 1L), pairs("LibraryEntity"))
        }

    @Test
    fun replaceShelvesDropsRemovedShelvesAndTheirMembers() =
        runBlocking {
            dao.replaceShelves(1, false, listOf(ShelfEntity(1, 1, "S1", 1, false), ShelfEntity(1, 2, "S2", 1, false)))
            dao.replaceShelves(1, true, listOf(ShelfEntity(1, 1, "M1", 1, true)))
            dao.replaceShelves(2, false, listOf(ShelfEntity(2, 1, "S1", 1, false)))
            for (a in listOf(1L, 2L)) dao.replaceShelfMembers(a, 1, false, listOf(1))
            dao.replaceShelfMembers(1, 2, false, listOf(1))
            dao.replaceShelfMembers(1, 1, true, listOf(1))
            dao.replaceShelves(1, false, listOf(ShelfEntity(1, 1, "S1", 1, false)))
            assertEquals(listOf(1L to 1L, 1L to 1L, 2L to 1L), pairs("ShelfEntity"))
            // surviving: regular shelf 1 and magic shelf 1 of account 1, shelf 1 of account 2; shelf 2 members gone
            assertEquals(listOf(1L to 1L, 1L to 1L, 2L to 1L), pairs("ShelfBookCrossRef", "accountId", "shelfId"))
        }

    private fun pairs(
        table: String,
        a: String = "accountId",
        b: String = "id",
    ): List<Pair<Long, Long>> =
        db.openHelper.readableDatabase.query("SELECT $a, $b FROM $table ORDER BY $a, $b").use {
            buildList { while (it.moveToNext()) add(it.getLong(0) to it.getLong(1)) }
        }
}
