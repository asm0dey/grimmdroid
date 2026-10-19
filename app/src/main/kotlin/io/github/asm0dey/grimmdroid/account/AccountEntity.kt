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
package io.github.asm0dey.grimmdroid.account

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [Index(value = ["baseUrl", "username"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseUrl: String,
    val username: String,
    val serverVersion: String,
    val canDownload: Boolean,
    val isAdmin: Boolean,
    val encryptedTokens: ByteArray?,
    val signedOut: Boolean = false,
    val lastSyncedAt: Long? = null,
) {
    // ByteArray field: data-class equality would be identity-based, and toString must not dump ciphertext.
    override fun equals(other: Any?) =
        other is AccountEntity &&
            id == other.id && baseUrl == other.baseUrl && username == other.username &&
            serverVersion == other.serverVersion && canDownload == other.canDownload &&
            isAdmin == other.isAdmin && encryptedTokens.contentEquals(other.encryptedTokens) &&
            signedOut == other.signedOut && lastSyncedAt == other.lastSyncedAt

    override fun hashCode() = 31 * (31 * id.hashCode() + baseUrl.hashCode()) + username.hashCode()

    override fun toString() = "AccountEntity(id=$id, baseUrl=$baseUrl, username=$username, signedOut=$signedOut)"
}
