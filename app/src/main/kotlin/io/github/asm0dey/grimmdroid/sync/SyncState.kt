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

sealed interface SyncState {
    data object Idle : SyncState

    data class Running(
        val done: Int,
        val total: Int,
    ) : SyncState

    data class Failed(
        val cause: Throwable,
    ) : SyncState

    data object SignedOut : SyncState

    /** The server has no mobile app API (`me()` answered 404); [serverVersion] is read live, null if unavailable. */
    data class Unsupported(
        val serverVersion: String?,
    ) : SyncState
}
