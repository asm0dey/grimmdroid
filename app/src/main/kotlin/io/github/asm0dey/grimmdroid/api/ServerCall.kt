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
package io.github.asm0dey.grimmdroid.api

import kotlinx.coroutines.CancellationException

/** How one request to the server ended. */
sealed interface ServerCall<out T> {
    data class Ok<T>(
        val value: T,
    ) : ServerCall<T>

    /** Unreachable, a server error, signed out, or an answer that isn't the expected JSON: worth trying again later. */
    data object Failed : ServerCall<Nothing>
}

/** Runs [block] against the server; any failure is a result, never a crash. Cancellation still propagates. */
suspend fun <T> callServer(block: suspend () -> T): ServerCall<T> =
    try {
        ServerCall.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // IO, any 4xx/5xx, SessionExpiredException, and decode failures (HTML on a 200, a changed schema)
        ServerCall.Failed
    }

fun <T> ServerCall<T>.valueOrNull(): T? = (this as? ServerCall.Ok)?.value
