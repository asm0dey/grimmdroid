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

import android.app.Application
import androidx.lifecycle.viewmodel.initializer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.reader.cfi.tinyEpub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.time.Duration.Companion.milliseconds

private const val BOOK = 7L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderViewModelSpec {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(app, CatalogDb::class.java).allowMainThreadQueries().build()
    private val dao = db.readerDao()
    private val server = FakeServer()
    private val enqueued = mutableListOf<Long>()
    private val sync = PositionSync(dao, { db.accountDao().get(it) }, { server.api }) { enqueued += it }
    private val epub = tinyEpub(c2Linear = true)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val id =
            kotlinx.coroutines.runBlocking {
                db.accountDao().upsert(
                    AccountEntity(0, "https://grim.example", "u", "", canDownload = true, isAdmin = false, encryptedTokens = null),
                )
            }
        check(id == 1L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun local(progression: Double) =
        kotlinx.coroutines.runBlocking {
            dao.upsertPosition(ReadingPosition(1, BOOK, locator(progression).toJSON().toString(), progression, PendingUpload.NORMAL, 5))
        }

    private fun serverCfi(percentage: Double) {
        server.progressJson = """{"epubProgress":{"cfi":"epubcfi(/6/4!/4/2)","href":"c1.xhtml","percentage":$percentage}}"""
    }

    private fun TestScope.vm(online: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        ReaderViewModel(
            1,
            BOOK,
            { 42L },
            epub,
            { app.openPublication(it, emptyList()) },
            sync,
            Bookmarks(dao, { db.accountDao().get(it) }, { server.api }) { enqueued += it },
            dao,
            { server.api },
            online,
            backgroundScope,
            dispatcher,
        )

    /** Room, Ktor's mock engine and Readium hop to real threads: run due tasks, without moving virtual time, until [done]. */
    private fun TestScope.until(done: () -> Boolean) {
        // up to ~10 s of real time: a cold Robolectric run is slow
        repeat(1_000) {
            runCurrent()
            if (done()) return
            Thread.sleep(10) // blocking: suspending here would let runTest skip virtual time ahead
        }
        error("condition never held: gets=${server.gets.size} puts=${server.puts.size}")
    }

    /** Lets in-flight real-thread work land, without moving virtual time. */
    private fun TestScope.settle() {
        repeat(20) {
            runCurrent()
            Thread.sleep(10) // blocking: suspending here would let runTest skip virtual time ahead
        }
    }

    private fun TestScope.runCurrent() = testScheduler.runCurrent()

    @Test
    fun firstOpenWithoutLocalPositionGoesToTheServerCfi() =
        runTest(dispatcher) {
            serverCfi(60.0)
            val vm = vm()
            assertNull(ready(vm.initialLocator))
            val navigated = mutableListOf<Locator>()
            backgroundScope.launchCollect(vm) { navigated += it }
            until { navigated.isNotEmpty() }
            assertEquals("c2.xhtml", navigated.single().href.toString())
            assertEquals("#a", navigated.single().locations.cssSelector)
            assertNull(vm.jump.value)
        }

    @Test
    fun serverAheadOffersAJump() =
        runTest(dispatcher) {
            local(0.4)
            serverCfi(60.0)
            val vm = vm()
            assertEquals(
                "c2.xhtml",
                ready(vm.initialLocator)!!
                    .href
                    .toString(),
            )
            until { vm.jump.value != null }
            assertEquals(60, vm.jump.value!!.percent)
            assertEquals(false, vm.jump.value!!.approximate)
            assertEquals(
                "c2.xhtml",
                vm.jump.value!!
                    .locator.href
                    .toString(),
            )
        }

    @Test
    fun theFirstLocatorAfterAJumpKeepsTheJumpTarget() =
        runTest(dispatcher) {
            local(0.4)
            serverCfi(60.0)
            val vm = vm()
            until { vm.jump.value != null }
            val target = vm.jump.value!!.locator
            vm.acceptJump()
            // saved before any report: Readium doesn't report again when the target is on the page already shown
            until { kotlinx.coroutines.runBlocking { dao.position(1, BOOK) }?.totalProgression == 0.6 }
            // the navigator reports the start of the page holding the target, a little behind it
            vm.onLocatorChanged(locator(0.58))
            vm.onLocatorChanged(locator(0.58)) // Readium reports the same page again once it has loaded
            until { kotlinx.coroutines.runBlocking { dao.position(1, BOOK) }?.totalProgression == 0.6 }
            settle()
            assertEquals(target.toJSON().toString(), kotlinx.coroutines.runBlocking { dao.position(1, BOOK) }!!.locatorJson)
            assertEquals(0.6, vm.current!!.locations.totalProgression!!, 1e-9)
            vm.onLocatorChanged(locator(0.62))
            until { kotlinx.coroutines.runBlocking { dao.position(1, BOOK) }?.totalProgression == 0.62 }
        }

    @Test
    fun aPageTurnPastAJumpTargetOnTheShownPageIsSaved() =
        runTest(dispatcher) {
            local(0.4)
            serverCfi(60.0)
            val vm = vm()
            until { vm.jump.value != null }
            vm.acceptJump() // the target is on the page shown: Readium reports nothing
            vm.onLocatorChanged(locator(0.61)) // the next page
            settle()
            assertEquals(0.61, kotlinx.coroutines.runBlocking { dao.position(1, BOOK) }!!.totalProgression, 0.0)
        }

    @Test
    fun reopeningTwiceKeepsTheSavedPlaceWhileOnItsPage() =
        runTest(dispatcher) {
            local(0.6)
            serverCfi(60.0)
            repeat(2) {
                val gets = server.gets.size
                val vm = vm()
                assertEquals(0.6, openNavigator(vm).locations.totalProgression!!, 0.0) // Readium's echo of the saved place
                vm.onLocatorChanged(locator(0.58)) // then the start of its page, twice
                vm.onLocatorChanged(locator(0.58))
                until { server.gets.size > gets }
                settle()
                assertEquals(0.6, row()!!.totalProgression, 0.0)
                assertNull(vm.jump.value)
            }
        }

    @Test
    fun theLastSaveAndFlushOutliveTheScreen() =
        runTest(dispatcher) {
            local(0.5)
            serverCfi(10.0)
            val store = androidx.lifecycle.ViewModelStore()
            val created = vm()
            val vm =
                androidx.lifecycle.ViewModelProvider(
                    store,
                    androidx.lifecycle.viewmodel.viewModelFactory { initializer { created } },
                )[ReaderViewModel::class.java]
            until { server.gets.size == 1 }
            openNavigator(vm)
            vm.onLocatorChanged(locator(0.48))
            vm.onLocatorChanged(locator(0.7))
            vm.onStop()
            store.clear() // onCleared cancels viewModelScope before the save lands
            until { server.puts.size == 1 }
            assertEquals(0.7, row()!!.totalProgression, 0.0)
            assertTrue(server.puts.single().contains("\"progressPercent\":70.0"))
        }

    @Test
    fun offlineFirstOpenNeverMovesTheReaderBack() =
        runTest(dispatcher) {
            serverCfi(10.0)
            val online = MutableStateFlow(false)
            val vm = vm(online)
            val navigated = mutableListOf<Locator>()
            backgroundScope.launchCollect(vm) { navigated += it }
            assertEquals("c1.xhtml", openNavigator(vm).href.toString()) // no saved place: the book's start
            vm.onLocatorChanged(locator(0.5)) // read on while offline
            settle()
            online.value = true
            until { server.gets.size == 1 }
            settle()
            assertTrue(navigated.isEmpty())
            assertNull(vm.jump.value)
            assertEquals(0.5, row()!!.totalProgression, 0.0)
        }

    @Test
    fun aFewPagesReadOfflineAreNeverUndoneByAServerJustBehind() =
        runTest(dispatcher) {
            server.progressJson = """{"epubProgress":{"percentage":0.1}}""" // 0.001: behind, within the tolerance
            val online = MutableStateFlow(false)
            val vm = vm(online)
            val navigated = mutableListOf<Locator>()
            backgroundScope.launchCollect(vm) { navigated += it }
            openNavigator(vm)
            vm.onLocatorChanged(locator(0.004, "c1.xhtml")) // a few pages into a long book
            settle()
            online.value = true
            until { server.gets.size == 1 }
            settle()
            assertTrue(navigated.isEmpty())
            assertNull(vm.jump.value)
            assertEquals(0.004, row()!!.totalProgression, 0.0)
        }

    @Test
    fun theNavigatorStartsAtTheSavedPlaceBeforeAnyReport() =
        runTest(dispatcher) {
            local(0.6)
            serverCfi(60.0)
            val vm = vm()
            ready(vm.initialLocator)
            // e.g. the activity recreated after the book opened but before the navigator reported
            val start = vm.navigatorStart(ready(vm.publication).getOrThrow())!!
            assertEquals(0.6, start.locations.totalProgression!!, 0.0)
        }

    @Test
    fun aServerAheadOfWhereTheUserMovedIsOfferedNotOpened() =
        runTest(dispatcher) {
            serverCfi(60.0)
            val online = MutableStateFlow(false)
            val vm = vm(online)
            val navigated = mutableListOf<Locator>()
            backgroundScope.launchCollect(vm) { navigated += it }
            openNavigator(vm)
            vm.onLocatorChanged(locator(0.2))
            settle()
            online.value = true
            until { vm.jump.value != null }
            assertEquals(60, vm.jump.value!!.percent)
            assertTrue(navigated.isEmpty())
        }

    @Test
    fun firstOpenAtTheServerSurvivesTheBookStartEchoEitherSide() =
        runTest(dispatcher) {
            for (echoFirst in listOf(true, false)) {
                kotlinx.coroutines.runBlocking { dao.deleteAccount(1) }
                serverCfi(60.0)
                val vm = vm()
                val navigated = mutableListOf<Locator>()
                val collecting = backgroundScope.launchCollect(vm) { navigated += it }
                if (echoFirst) openNavigator(vm)
                until { navigated.isNotEmpty() }
                if (!echoFirst) openNavigator(vm)
                vm.onLocatorChanged(locator(0.58)) // the start of the target's page
                settle()
                assertEquals("echoFirst=$echoFirst", 0.6, row()!!.totalProgression, 0.0)
                collecting.cancel()
            }
        }

    @Test
    fun aReportWithoutTotalProgressionIsNotTakenForTheTarget() =
        runTest(dispatcher) {
            local(0.6)
            serverCfi(10.0)
            val vm = vm()
            openNavigator(vm)
            vm.onLocatorChanged(locator(null))
            vm.onLocatorChanged(locator(null))
            settle()
            assertNull(vm.current!!.locations.totalProgression)
        }

    @Test
    fun percentageOnlyServerOffersAnApproximateJump() =
        runTest(dispatcher) {
            local(0.4)
            server.progressJson = """{"epubProgress":{"percentage":60.0}}"""
            val vm = vm()
            until { vm.jump.value != null }
            assertEquals(60, vm.jump.value!!.percent)
            assertEquals(true, vm.jump.value!!.approximate)
        }

    @Test
    fun serverBehindStaysAndUploadsAfterTheDebounce() =
        runTest(dispatcher) {
            local(0.6)
            serverCfi(40.0)
            val vm = vm()
            until { server.gets.size == 1 }
            settle() // the answer lands and starts the debounce at virtual time 0
            advanceTimeBy(2_900.milliseconds)
            settle()
            assertEquals(0, server.puts.size)
            advanceTimeBy(200.milliseconds)
            until { server.puts.size == 1 }
            assertNull(vm.jump.value)
        }

    @Test
    fun anHtmlAnswerNeitherCrashesNorMovesTheReader() =
        runTest(dispatcher) {
            local(0.4)
            server.html = io.ktor.http.HttpStatusCode.OK
            val vm = vm()
            until { server.gets.size == 2 } // progress and bookmarks
            settle()
            advanceTimeBy(3_100.milliseconds)
            until { enqueued == listOf(1L) } // the upload failed like an IO error: left to the worker
            assertNull(vm.jump.value)
            assertEquals(0.4, row()!!.totalProgression, 0.0)
            assertEquals(PendingUpload.NORMAL, row()!!.pending)
        }

    @Test
    fun aBookGoneFromTheServerIsNotAskedForAgainOnEveryPage() =
        runTest(dispatcher) {
            local(0.4)
            server.html = io.ktor.http.HttpStatusCode.NotFound
            val vm = vm()
            until { server.gets.size == 2 } // progress and bookmarks
            settle()
            openNavigator(vm)
            vm.onLocatorChanged(locator(0.45))
            advanceTimeBy(3_100.milliseconds)
            settle()
            vm.onStop()
            settle()
            assertEquals(2, server.gets.size)
            assertEquals(0, server.puts.size)
            assertTrue(enqueued.isEmpty())
            assertEquals(0.45, row()!!.totalProgression, 0.0) // still saved here
        }

    @Test
    fun locatorChangesSaveAtOnceAndOnStopFlushes() =
        runTest(dispatcher) {
            local(0.5)
            serverCfi(10.0)
            val vm = vm()
            until { server.gets.size == 1 }
            openNavigator(vm)
            vm.onLocatorChanged(locator(0.48)) // the start of the saved place's page
            vm.onLocatorChanged(locator(0.7))
            until { kotlinx.coroutines.runBlocking { dao.position(1, BOOK) }?.totalProgression == 0.7 }
            vm.onStop()
            until { server.puts.size == 1 }
            assertTrue(server.puts.single().contains("\"progressPercent\":70.0"))
        }

    @Test
    fun syncNowOffersAJumpBack() =
        runTest(dispatcher) {
            local(0.6)
            serverCfi(40.0)
            val vm = vm()
            until { server.gets.size == 1 }
            assertNull(vm.jump.value)
            vm.syncNow()
            until { vm.jump.value != null }
            assertEquals(40, vm.jump.value!!.percent)
        }

    @Test
    fun makeCurrentSendsOverAFurtherServer() =
        runTest(dispatcher) {
            local(0.4)
            serverCfi(60.0)
            val vm = vm()
            until { vm.jump.value != null }
            vm.makeCurrent()
            until { server.puts.size == 1 }
            assertTrue(server.puts.single().contains("\"progressPercent\":40.0"))
            vm.madeCurrent.first()
            assertNull(vm.jump.value)
        }

    @Test
    fun offlineMakesNoRequests() =
        runTest(dispatcher) {
            local(0.4)
            serverCfi(60.0)
            val vm = vm(MutableStateFlow(false))
            ready(vm.publication)
            vm.onLocatorChanged(locator(0.45))
            advanceTimeBy(3_100.milliseconds)
            settle()
            assertEquals(0, server.requests)
            assertNull(vm.jump.value)
            assertEquals(listOf(1L), enqueued)
        }

    /**
     * What the activity does: create the navigator, whose currentLocator StateFlow first echoes its initial locator.
     * Never suspends: a suspended test body lets runTest skip virtual time past the upload debounce.
     */
    private fun TestScope.openNavigator(vm: ReaderViewModel): Locator {
        ready(vm.initialLocator)
        val start = vm.navigatorStart(ready(vm.publication).getOrThrow())!!
        vm.onLocatorChanged(start)
        return start
    }

    /** [d]'s value, waited for without suspending (see [openNavigator]). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> TestScope.ready(d: kotlinx.coroutines.Deferred<T>): T {
        until { d.isCompleted }
        return d.getCompleted()
    }

    private fun row() = kotlinx.coroutines.runBlocking { dao.position(1, BOOK) }

    private fun bookmarks() = kotlinx.coroutines.runBlocking { dao.bookmarks(1, BOOK).first() }

    @Test
    fun toggleBookmarkAddsThenRemovesTheBookmarkOnThisPage() =
        runTest(dispatcher) {
            val vm = vm(MutableStateFlow(false))
            vm.toggleBookmark(locator(0.5).copy(title = "Two"), PageSpan("c2.xhtml", 0.0, 0.5), Locator.Text(highlight = "Alpha"))
            until { bookmarks().singleOrNull()?.state == BookmarkState.SYNCED }
            settle() // the toggle finishes; a tap before that is ignored
            val row = bookmarks().single()
            assertEquals("Two — Alpha", row.title)
            assertEquals("epubcfi(/6/4!/4/2)", row.cfi)
            assertEquals(0.5, row.totalProgression, 0.0)
            vm.toggleBookmark(locator(0.5), PageSpan("c2.xhtml", 0.0, 0.5), null) // the same page again
            until { bookmarks().isEmpty() && server.bookmarkDeletes == listOf("/api/v1/bookmarks/1") }
        }

    @Test
    fun toggleOnTheNextPageAddsABookmarkAndKeepsThePreviousOne() =
        runTest(dispatcher) {
            val vm = vm(MutableStateFlow(false))
            vm.toggleBookmark(locator(0.5), PageSpan("c2.xhtml", 0.0, 0.5), null)
            until { bookmarks().singleOrNull()?.state == BookmarkState.SYNCED }
            settle()
            val next = locator(0.75).copyWithLocations(progression = 0.5)
            vm.toggleBookmark(next, PageSpan("c2.xhtml", 0.5, 1.0), null)
            until { bookmarks().size == 2 && server.bookmarkPosts.size == 2 }
            assertTrue(server.bookmarkDeletes.isEmpty())
        }

    @Test
    fun aDoubleTapAddsOneBookmark() =
        runTest(dispatcher) {
            val vm = vm(MutableStateFlow(false))
            vm.toggleBookmark(locator(0.5), PageSpan("c2.xhtml", 0.0, 0.5), null)
            vm.toggleBookmark(locator(0.5), PageSpan("c2.xhtml", 0.0, 0.5), null)
            until { bookmarks().isNotEmpty() && server.bookmarkPosts.isNotEmpty() }
            settle()
            assertEquals(1, bookmarks().size)
            assertEquals(1, server.bookmarkPosts.size)
        }

    private fun locator(
        total: Double?,
        href: String = "c2.xhtml",
    ) = Locator(
        Url(href)!!,
        MediaType.XHTML,
        locations = Locator.Locations(progression = 0.0, totalProgression = total, otherLocations = mapOf("cssSelector" to "#a")),
    )

    private fun CoroutineScope.launchCollect(
        vm: ReaderViewModel,
        onEach: (Locator) -> Unit,
    ) = launch { vm.navigateTo.collect(onEach) }
}
