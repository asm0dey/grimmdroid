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

import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.api.BookmarkDto
import io.github.asm0dey.grimmdroid.api.CreateBookmarkDto
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.ServerCall
import io.github.asm0dey.grimmdroid.api.callServer
import io.github.asm0dey.grimmdroid.api.valueOrNull
import io.github.asm0dey.grimmdroid.reader.cfi.EpubArchive
import io.github.asm0dey.grimmdroid.reader.cfi.parseCfi
import io.github.asm0dey.grimmdroid.reader.cfi.resolve
import io.github.asm0dey.grimmdroid.reader.cfi.spineIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

private const val TITLE_TEXT = 60

/** "<chapter title> — <first 60 characters of the text at the bookmark>", either half alone when the other is missing. */
fun bookmarkTitle(
    chapter: String?,
    text: Locator.Text?,
): String {
    val words =
        listOfNotNull(text?.highlight, text?.after)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.isNotEmpty() && it != chapter?.trim() } // a page opening on its heading: not "Ch 1 — Ch 1"
            ?.take(TITLE_TEXT)
    return listOfNotNull(chapter?.takeIf { it.isNotBlank() }, words).joinToString(" — ")
}

/** The page on screen: resource [href] (as in a locator's JSON) from progression [start] up to the next page's [end]. */
data class PageSpan(
    val href: String,
    val start: Double,
    val end: Double,
) {
    companion object {
        /** The page [locator] starts, [fraction] of its resource long (1 / page count, or the viewport's share when scrolled). */
        fun of(
            locator: Locator,
            fraction: Double,
        ): PageSpan {
            val start = locator.locations.progression ?: 0.0
            return PageSpan(locator.href.toString(), start, start + fraction)
        }
    }
}

/** On [page] when in its resource between its start and the next page; the last page holds the resource's end. */
fun BookmarkRow.isOn(page: PageSpan): Boolean {
    if (locatorHref(locatorJson) != page.href) return false
    val p = locatorProgression(locatorJson) ?: return false
    return p >= page.start && (p < page.end || page.end >= 1.0)
}

/** Where [row] points in this publication; null for a bookmark whose CFI did not resolve. */
fun Publication.locatorFor(row: BookmarkRow): Locator? {
    val saved =
        try {
            Locator.fromJSON(JSONObject(row.locatorJson))
        } catch (_: JSONException) {
            null
        } ?: return null
    // The server's bookmarks carry the archive's href: take the publication's own link for it.
    val link = linkWithHref(saved.href) ?: return null
    return locatorFromLink(link)?.copyWithLocations(
        progression = saved.locations.progression,
        otherLocations = saved.locations.otherLocations,
    )
}

/**
 * Bookmarks shared with Grimmory: kept locally per account and book, sent at once when possible, else by
 * [PositionUploadWorker] through [enqueue]. A missing or signed-out account sends nothing and enqueues nothing.
 */
class Bookmarks(
    private val dao: ReaderDao,
    private val account: suspend (accountId: Long) -> AccountEntity?,
    private val api: (AccountEntity) -> GrimmoryApi,
    private val enqueue: (accountId: Long) -> Unit,
) {
    // One change at a time: a flush and the screen must not post the same bookmark twice or race a refresh.
    private val lock = Mutex()

    fun observe(
        accountId: Long,
        bookId: Long,
    ): Flow<List<BookmarkRow>> = dao.bookmarks(accountId, bookId)

    private suspend fun apiFor(accountId: Long) = account(accountId)?.takeUnless { it.signedOut }?.let(api)

    /** Swaps the book's synced bookmarks for the server's list; pending ones stay. Call it on [archive]'s thread. */
    suspend fun refresh(
        accountId: Long,
        bookId: Long,
        archive: EpubArchive,
    ) = lock.withLock {
        val api = apiFor(accountId) ?: return@withLock
        val server = callServer { api.bookmarks(bookId) }.valueOrNull() ?: return@withLock
        // A bookmark being deleted here stays hidden until the server forgets it.
        val deleting =
            dao
                .pendingBookmarks()
                .filter { it.accountId == accountId && it.bookId == bookId }
                .mapNotNull { it.serverId }
                .toSet()
        dao.replaceSynced(accountId, bookId, server.filter { it.id !in deleting }.map { it.toRow(accountId, archive) })
    }

    suspend fun add(
        accountId: Long,
        bookId: Long,
        locator: Locator,
        cfi: String?,
        title: String,
    ) = lock.withLock {
        val row =
            BookmarkRow(
                accountId = accountId,
                bookId = bookId,
                serverId = null,
                cfi = cfi,
                locatorJson = locator.toJSON().toString(),
                title = title,
                totalProgression = locator.locations.totalProgression ?: 0.0,
                state = BookmarkState.PENDING_CREATE,
            )
        val inserted = row.copy(localId = dao.insertBookmark(row))
        if (!send(inserted)) enqueue(accountId)
    }

    /** Deletes [row] here and on the server; one never sent is only deleted here. */
    suspend fun remove(row: BookmarkRow) =
        lock.withLock {
            // The row may have been sent since the caller read it.
            val current = dao.bookmark(row.localId) ?: return@withLock
            if (current.serverId == null) return@withLock dao.deleteBookmark(current.localId)
            val deleting = current.copy(state = BookmarkState.PENDING_DELETE)
            dao.updateBookmark(deleting)
            if (!send(deleting)) enqueue(current.accountId)
        }

    /** Sends [accountId]'s pending bookmarks; false when some failed and are worth retrying. Enqueues nothing. */
    suspend fun flush(accountId: Long): Boolean =
        lock.withLock {
            dao
                .pendingBookmarks()
                .filter { it.accountId == accountId }
                .map { send(it) }
                .all { it }
        }

    /**
     * Sends one pending change; true when nothing is left to retry. A missing or signed-out account is true: only a
     * sign-in helps, and the row stays pending for then. A bookmark without a CFI never goes to the server, nor
     * does one the server refused (400/403/404/410): it stays here, pending, and is only tried again by a later flush.
     */
    private suspend fun send(row: BookmarkRow): Boolean {
        val api = apiFor(row.accountId) ?: return true
        when (row.state) {
            BookmarkState.SYNCED -> {
                return true
            }

            BookmarkState.PENDING_CREATE -> {
                val cfi = row.cfi ?: return true
                when (val created = callServer { api.createBookmark(CreateBookmarkDto(row.bookId, cfi, row.title)) }) {
                    is ServerCall.Ok -> dao.updateBookmark(row.copy(serverId = created.value.id, state = BookmarkState.SYNCED))
                    ServerCall.Failed -> return false
                    ServerCall.Rejected -> Unit // the server won't take it: kept here, like one without a CFI
                }
            }

            BookmarkState.PENDING_DELETE -> {
                val id = checkNotNull(row.serverId) { "PENDING_DELETE without a server id" }
                // Rejected (404: already gone there; 400/403: never deletable): the user wanted it gone here anyway.
                if (callServer { api.deleteBookmark(id) } == ServerCall.Failed) return false
                dao.deleteBookmark(row.localId)
            }
        }
        return true
    }
}

/** The server's bookmark at its CFI's place in [archive]; an unresolvable one keeps no place but is still listed. */
private fun BookmarkDto.toRow(
    accountId: Long,
    archive: EpubArchive,
): BookmarkRow {
    val parsed = cfi?.let(::parseCfi)
    val target = parsed?.let { archive.resolve(it) }
    // shortcut: (spine index + progression) / spine size, like serverPosition's fallback; Readium's own
    // totalProgression weighs resources by length, so the list's order and % may be off (the toggle uses href +
    // progression, not this). Upgrade with positions.
    val total = if (parsed != null && target != null) (parsed.spineIndex + target.progression) / archive.spine.size else 0.0
    val locator =
        target?.let { t ->
            Url.fromDecodedPath(t.href)?.let { href ->
                Locator(
                    href,
                    MediaType.XHTML,
                    locations =
                        Locator.Locations(
                            progression = t.progression,
                            totalProgression = total,
                            otherLocations = t.cssSelector?.let { mapOf("cssSelector" to it) } ?: emptyMap(),
                        ),
                )
            }
        }
    return BookmarkRow(
        accountId = accountId,
        bookId = bookId,
        serverId = id,
        cfi = cfi,
        locatorJson = locator?.toJSON()?.toString() ?: "{}",
        title = title.orEmpty(),
        totalProgression = total,
        state = BookmarkState.SYNCED,
    )
}
