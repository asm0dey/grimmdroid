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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AccountDao {
    @Query("SELECT * FROM AccountEntity ORDER BY id")
    abstract fun all(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM AccountEntity WHERE id = :id")
    abstract suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM AccountEntity WHERE baseUrl = :baseUrl AND username = :username")
    abstract suspend fun find(
        baseUrl: String,
        username: String,
    ): AccountEntity?

    @Insert
    abstract suspend fun insert(account: AccountEntity): Long

    @Update
    abstract suspend fun update(account: AccountEntity)

    @Query("UPDATE AccountEntity SET encryptedTokens = :tokens, signedOut = :signedOut WHERE id = :id")
    abstract suspend fun setTokens(
        id: Long,
        tokens: ByteArray?,
        signedOut: Boolean,
    )

    @Query("UPDATE AccountEntity SET signedOut = 1 WHERE id = :id")
    abstract suspend fun markSignedOut(id: Long)

    @Query("UPDATE AccountEntity SET canDownload = :canDownload, isAdmin = :isAdmin WHERE id = :id")
    abstract suspend fun setPermissions(
        id: Long,
        canDownload: Boolean,
        isAdmin: Boolean,
    )

    @Query("UPDATE AccountEntity SET lastSyncedAt = :at WHERE id = :id")
    abstract suspend fun setLastSyncedAt(
        id: Long,
        at: Long,
    )

    @Query("DELETE FROM AccountEntity WHERE id = :id")
    abstract suspend fun delete(id: Long)

    // Atomic find-then-write on the unique (baseUrl, username) index.
    @Transaction
    open suspend fun upsert(account: AccountEntity): Long {
        val existing = find(account.baseUrl, account.username)
        return if (existing == null) {
            insert(account)
        } else {
            update(account.copy(id = existing.id, lastSyncedAt = existing.lastSyncedAt))
            existing.id
        }
    }
}
