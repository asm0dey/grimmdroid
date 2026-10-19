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
import io.github.asm0dey.grimmdroid.api.BookProgressDto
import io.github.asm0dey.grimmdroid.api.FileProgressDto
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.GrimmoryJson
import io.github.asm0dey.grimmdroid.api.SessionExpiredException
import io.github.asm0dey.grimmdroid.api.UpdateProgressDto
import io.github.asm0dey.grimmdroid.reader.cfi.EpubArchive
import io.github.asm0dey.grimmdroid.reader.cfi.PositionTarget
import io.github.asm0dey.grimmdroid.reader.cfi.cfiFor
import io.github.asm0dey.grimmdroid.reader.cfi.parseCfi
import io.github.asm0dey.grimmdroid.reader.cfi.resolve
import io.github.asm0dey.grimmdroid.reader.cfi.spineIndex
import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException

/** Two total progressions closer than this are the same place (about a page of a 200-page book). */
const val SAME_PLACE = 0.005

/** Where the server says the book is open, as a total progression (0..1). */
sealed interface ServerPosition {
    val totalProgression: Double

    /** A CFI that resolves in the downloaded file. */
    data class Exact(
        val target: PositionTarget,
        val cfi: String,
        override val totalProgression: Double,
    ) : ServerPosition

    /** Only a percentage: shown as "about N%". */
    data class Approximate(
        override val totalProgression: Double,
    ) : ServerPosition
}

sealed interface OpenDecision {
    data object Stay : OpenDecision

    data class OpenAt(
        val p: ServerPosition,
    ) : OpenDecision

    data class OfferJump(
        val p: ServerPosition,
    ) : OpenDecision
}

// The DTO's Float holds the server's JSON decimal; toString gives it back (0.6f is 0.6, not 0.6000000238).
private fun Float.decimal() = toString().toDouble()

/** The furthest place any server field claims (epub percentage is 0–100, KOReader's a 0–1 fraction); null when none. */
private fun furthest(dto: BookProgressDto): Double? =
    listOfNotNull(
        dto.epubProgress
            ?.percentage
            ?.decimal()
            ?.div(100),
        dto.koreaderProgress?.percentage?.decimal(),
    ).maxOrNull()

/**
 * Exact when the epub CFI resolves in [archive], unless KOReader is further ahead; otherwise the furthest percentage.
 * `epubProgress.href` is never trusted: the target's href comes from the CFI.
 */
fun serverPosition(
    dto: BookProgressDto,
    archive: EpubArchive,
): ServerPosition? {
    val epub = dto.epubProgress
    val cfi = epub?.cfi
    val parsed = cfi?.let(::parseCfi)
    val target = parsed?.let { archive.resolve(it) }
    if (cfi == null || parsed == null || target == null) return furthest(dto)?.let { ServerPosition.Approximate(it) }
    // The CFI only locates within its resource; without a percentage, (spine index + progression) / spine size is a fair estimate.
    val total = epub.percentage?.decimal()?.div(100) ?: ((parsed.spineIndex + target.progression) / archive.spine.size)
    val koreader = dto.koreaderProgress?.percentage?.decimal()
    return if (koreader != null && koreader - total > SAME_PLACE) {
        ServerPosition.Approximate(koreader)
    } else {
        ServerPosition.Exact(target, cfi, total)
    }
}

/** On open: go straight to the server's place when there is no local one; offer a jump only forward. */
fun decideOnOpen(
    local: Double?,
    server: ServerPosition?,
): OpenDecision =
    when {
        server == null -> OpenDecision.Stay
        local == null -> OpenDecision.OpenAt(server)
        server.totalProgression - local > SAME_PLACE -> OpenDecision.OfferJump(server)
        else -> OpenDecision.Stay
    }

/** On a manual "Sync now": offer a jump to any different place, behind included. */
fun decideOnSyncNow(
    local: Double,
    server: ServerPosition?,
): OpenDecision =
    if (server != null && kotlin.math.abs(server.totalProgression - local) > SAME_PLACE) {
        OpenDecision.OfferJump(server)
    } else {
        OpenDecision.Stay
    }

enum class UploadResult { SENT, HELD_BACK, FAILED }

private fun locator(json: String): JsonObject? =
    try {
        GrimmoryJson.parseToJsonElement(json) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun locatorHref(json: String): String? = locator(json)?.string("href")

/** CFI for a Readium locator JSON (href, locations.cssSelector, locations.progression); null when it doesn't map. */
fun EpubArchive.cfiForLocator(json: String): String? {
    val loc = locator(json) ?: return null
    val href = loc.string("href") ?: return null
    val locations = loc["locations"] as? JsonObject
    val progression = (locations?.get("progression") as? JsonPrimitive)?.doubleOrNull ?: 0.0
    return cfiFor(href, locations?.string("cssSelector"), progression)
}

/** Local positions and their upload. [account] looks an account up; [enqueue] schedules [PositionUploadWorker] for it. */
class PositionSync(
    private val dao: ReaderDao,
    private val account: suspend (accountId: Long) -> AccountEntity?,
    private val api: (AccountEntity) -> GrimmoryApi,
    private val enqueue: (accountId: Long) -> Unit,
) {
    // Accounts with a follow-up run already queued: repeated failures before it starts enqueue nothing more.
    private val queued =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<Long>()

    /** Called by [PositionUploadWorker] as it starts: a failure from now on needs a new run. */
    fun followUpStarted(accountId: Long) {
        queued.remove(accountId)
    }

    suspend fun saveLocal(
        accountId: Long,
        bookId: Long,
        locatorJson: String,
        totalProgression: Double,
    ) = dao.savePosition(
        ReadingPosition(accountId, bookId, locatorJson, totalProgression, PendingUpload.NORMAL, System.currentTimeMillis()),
    )

    /** "Make this position current": uploads over the server even when it is further ahead. No local position ⇒ FAILED. */
    suspend fun makeCurrent(
        accountId: Long,
        bookId: Long,
        bookFileId: Long,
        cfi: (locatorJson: String) -> String?,
    ): UploadResult {
        dao.forcePosition(accountId, bookId)
        if (dao.position(accountId, bookId) == null) return UploadResult.FAILED
        return upload(accountId, bookId, bookFileId, cfi = cfi)
    }

    /**
     * Uploads the pending position. NORMAL never overwrites a server position further ahead (HELD_BACK, still pending);
     * FORCE always does. [cfi] maps the uploaded row's locator JSON to a CFI. Nothing pending counts as SENT.
     * A missing or signed-out account is FAILED with no request and nothing enqueued: only a sign-in can help.
     * Other failures enqueue a follow-up run unless [enqueueOnFailure] is false (the worker retries by itself).
     */
    suspend fun upload(
        accountId: Long,
        bookId: Long,
        bookFileId: Long,
        enqueueOnFailure: Boolean = true,
        cfi: (locatorJson: String) -> String?,
    ): UploadResult {
        val row = dao.position(accountId, bookId)?.takeIf { it.pending != PendingUpload.NONE } ?: return UploadResult.SENT
        val account = account(accountId)?.takeUnless { it.signedOut } ?: return UploadResult.FAILED
        return try {
            val api = api(account)
            // shortcut: a server CFI without any percentage isn't weighed (it needs the archive); Grimmory sends both.
            if (row.pending == PendingUpload.NORMAL) {
                val server = furthest(api.progress(bookId))
                if (server != null && server - row.totalProgression > SAME_PLACE) return UploadResult.HELD_BACK
            }
            val body =
                FileProgressDto(bookFileId, cfi(row.locatorJson), locatorHref(row.locatorJson), (row.totalProgression * 100).toFloat())
            api.putProgress(bookId, UpdateProgressDto(body))
            dao.markUploaded(accountId, bookId, row.updatedAt, row.locatorJson)
            UploadResult.SENT
        } catch (_: IOException) {
            failed(accountId, enqueueOnFailure)
        } catch (_: ResponseException) {
            failed(accountId, enqueueOnFailure)
        } catch (_: SessionExpiredException) {
            failed(accountId, enqueueOnFailure) // the account is signed out now: the worker returns success and keeps the row
        }
    }

    private fun failed(
        accountId: Long,
        enqueueOnFailure: Boolean,
    ): UploadResult {
        if (enqueueOnFailure && queued.add(accountId)) enqueue(accountId)
        return UploadResult.FAILED
    }
}
