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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.SessionExpiredException
import io.github.asm0dey.grimmdroid.reader.cfi.EpubArchive
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.Url
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** "Further ahead on another device: [percent]% — Jump"; [approximate] targets read "about N%". */
data class JumpOffer(
    val locator: Locator,
    val percent: Int,
    val approximate: Boolean,
)

/** Debounce between the last page turn and the upload. */
private const val UPLOAD_DEBOUNCE_MS = 3_000L

/**
 * One open book: owns the publication and the CFI archive (closed in [onCleared]), saves every locator locally and
 * uploads it debounced, and turns the server's position into navigation or a jump offer — never a jump on its own.
 * [api] is null when the account is gone or signed out; [pendingScope] outlives the screen so the last upload and
 * the closing of the book finish after it.
 */
class ReaderViewModel(
    private val accountId: Long,
    private val bookId: Long,
    bookFileId: suspend () -> Long?,
    file: File,
    open: suspend (File) -> Result<Publication>,
    private val sync: PositionSync,
    dao: ReaderDao,
    private val api: suspend () -> GrimmoryApi?,
    private val online: StateFlow<Boolean>,
    private val pendingScope: CoroutineScope,
    // One at a time: EpubArchive isn't thread-safe.
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : ViewModel() {
    val publication: Deferred<Result<Publication>> = pendingScope.async { open(file) }
    private val archive: Deferred<EpubArchive?> =
        pendingScope.async(io) {
            try {
                EpubArchive(file)
            } catch (_: IOException) {
                null
            }
        }
    private val fileId = viewModelScope.async { bookFileId() }
    private val localRow = viewModelScope.async { dao.position(accountId, bookId) }

    // Declared before [initialLocator], which sets it. Where the navigator was last sent (the saved place, a jump, the server's place). It reports the start of that
    // page, a little behind the target, which would read as "behind the server" and bring the jump bar back; while
    // the reports stay on that page ([arrivedPage]) the target is kept.
    private var arriving: Locator? = null
    private var arrivedPage: Double? = null

    /** The phone's own position; never waits on the network. */
    val initialLocator: Deferred<Locator?> =
        viewModelScope.async {
            localRow
                .await()
                ?.locatorJson
                ?.let {
                    try {
                        Locator.fromJSON(JSONObject(it))
                    } catch (_: JSONException) {
                        null
                    }
                }?.also { arriving = it }
        }

    private val _jump = MutableStateFlow<JumpOffer?>(null)
    val jump: StateFlow<JumpOffer?> = _jump.asStateFlow()

    private val navigation = Channel<Locator>(Channel.CONFLATED)

    /** Places to go now (first open at the server position, an accepted jump); kept until the navigator collects. */
    val navigateTo: Flow<Locator> = navigation.receiveAsFlow()

    private val madeCurrentEvents = Channel<Unit>(Channel.CONFLATED)

    /** Fires when "Make this position current" reached the server. */
    val madeCurrent: Flow<Unit> = madeCurrentEvents.receiveAsFlow()

    /** The position last recorded (reported by the navigator, or the target it is on); survives rotation. */
    var current: Locator? = null
        private set

    private var echo: Locator? = null

    // Where the navigator opened (else the first report); reading away from it means the user moved, so the
    // server's place is only offered, never opened.
    private var openedAt: Double? = null

    private fun position() = current?.locations?.totalProgression

    private fun moved(): Boolean {
        val now = position() ?: return false
        val start = openedAt ?: return false
        return kotlin.math.abs(now - start) > SAME_PLACE
    }

    private var save: Job? = null
    private var debounced: Job? = null
    private var flush: Job? = null

    init {
        viewModelScope.launch {
            val local = localRow.await()?.totalProgression
            online.first { it }
            val server = fetchServer()
            // Decided against where the reader is now: the user may have read on while offline or waiting.
            when (val d = decideOnOpen(if (local == null && !moved()) null else position() ?: local, server)) {
                is OpenDecision.OpenAt -> toLocator(d.p)?.let { if (!moved()) go(it) else offer(d.p) }
                is OpenDecision.OfferJump -> offer(d.p)
                OpenDecision.Stay -> scheduleUpload() // behind or equal: the phone's position goes up
            }
        }
    }

    /**
     * The locator to create the navigator with ([saved], or the book's start). Readium's currentLocator starts as
     * this very locator; that echo is not a report.
     */
    fun navigatorStart(
        pub: Publication,
        saved: Locator?,
    ): Locator? =
        (current ?: saved ?: pub.readingOrder.firstOrNull()?.let(pub::locatorFromLink)).also {
            echo = it
            if (openedAt == null) openedAt = it?.locations?.totalProgression ?: 0.0 // the book's start has none
        }

    fun onLocatorChanged(reported: Locator) {
        val start = echo
        // The activity may have added a selector to the echo.
        if (start != null && reported.copyWithLocations(otherLocations = start.locations.otherLocations) == start) {
            echo = null
            return
        }
        val page = reported.locations.totalProgression
        if (openedAt == null) openedAt = page
        val target = arriving
        // The target's page starts at or before it; a report past it is a page turn. shortcut: a page turned back
        // before the target's page was ever reported counts as arrival, until the next turn.
        val locator =
            if (
                target != null &&
                page != null &&
                target.href == reported.href &&
                page <= (target.locations.totalProgression ?: 1.0) &&
                (arrivedPage == null || arrivedPage == page)
            ) {
                arrivedPage = page
                target
            } else {
                arriving = null
                reported
            }
        record(locator)
    }

    /** Saves [locator] locally at once and schedules its upload. */
    private fun record(locator: Locator) {
        current = locator
        val previous = save
        save =
            // Outlives the screen: the last save must land before the final flush uploads it.
            pendingScope.launch {
                previous?.join() // in order: a late older save must not win
                sync.saveLocal(accountId, bookId, locator.toJSON().toString(), locator.locations.totalProgression ?: 0.0)
            }
        scheduleUpload()
    }

    /** Leaving the screen or the app: upload now, in a scope that outlives the screen. */
    fun onStop() {
        debounced?.cancel()
        val pendingSave = save
        flush =
            pendingScope.launch {
                pendingSave?.join()
                upload()
            }
    }

    /** Fetches the server position now and offers a jump to any different place, behind included. */
    fun syncNow() {
        viewModelScope.launch {
            val server = fetchServer() ?: return@launch
            val local = position() ?: localRow.await()?.totalProgression ?: 0.0
            when (val d = decideOnSyncNow(local, server)) {
                is OpenDecision.OfferJump -> offer(d.p)
                else -> _jump.value = null
            }
        }
    }

    /** Uploads the phone's position over the server's, even when the server is further ahead. */
    fun makeCurrent() {
        debounced?.cancel()
        val pendingSave = save
        viewModelScope.launch {
            pendingSave?.join()
            val id = fileId.await() ?: return@launch
            val archive = archive.await() ?: return@launch
            val result = withContext(io) { sync.makeCurrent(accountId, bookId, id) { archive.cfiForLocator(it) } }
            if (result == UploadResult.SENT) {
                _jump.value = null
                madeCurrentEvents.send(Unit)
            }
        }
    }

    fun acceptJump() {
        val offer = _jump.value ?: return
        _jump.value = null
        go(offer.locator)
    }

    private fun go(locator: Locator) {
        arriving = locator
        arrivedPage = null
        // Recorded now: Readium reports nothing when the target is on the page already shown.
        record(locator)
        navigation.trySend(locator)
    }

    fun dismissJump() {
        _jump.value = null
    }

    private fun scheduleUpload() {
        debounced?.cancel()
        debounced =
            viewModelScope.launch {
                delay(UPLOAD_DEBOUNCE_MS)
                save?.join()
                upload()
            }
    }

    private suspend fun upload() {
        // Offline: leave it to the worker, which waits for a network.
        if (!online.value) return sync.uploadLater(accountId)
        val id = fileId.await() ?: return // not a downloaded book: nothing the server can take
        val archive = archive.await() ?: return
        withContext(io) { sync.upload(accountId, bookId, id) { archive.cfiForLocator(it) } }
    }

    /** Null offline, on any server failure, or when the server has no position. */
    private suspend fun fetchServer(): ServerPosition? {
        if (!online.value) return null
        val api = api() ?: return null
        val archive = archive.await() ?: return null
        val dto =
            try {
                api.progress(bookId)
            } catch (_: IOException) {
                return null
            } catch (_: ResponseException) {
                return null
            } catch (_: SessionExpiredException) {
                return null
            }
        return withContext(io) { serverPosition(dto, archive) }
    }

    private suspend fun offer(p: ServerPosition) {
        val locator = toLocator(p) ?: return
        _jump.value = JumpOffer(locator, (p.totalProgression * 100).roundToInt(), p is ServerPosition.Approximate)
    }

    private suspend fun toLocator(p: ServerPosition): Locator? {
        val pub = publication.await().getOrNull() ?: return null
        return when (p) {
            is ServerPosition.Exact -> {
                val link = Url.fromDecodedPath(p.target.href)?.let(pub::linkWithHref) ?: return null
                pub.locatorFromLink(link)?.copyWithLocations(
                    progression = p.target.progression,
                    totalProgression = p.totalProgression,
                    otherLocations = p.target.cssSelector?.let { mapOf("cssSelector" to it) } ?: emptyMap(),
                )
            }

            is ServerPosition.Approximate -> {
                pub.locateProgression(p.totalProgression)
            }
        }
    }

    override fun onCleared() {
        val lastFlush = flush
        pendingScope.launch {
            lastFlush?.join() // the upload still reads the archive
            publication.await().getOrNull()?.close()
            withContext(io) { archive.await()?.close() }
        }
    }
}
