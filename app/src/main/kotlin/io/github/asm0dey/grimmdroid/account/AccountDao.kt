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
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM AccountEntity ORDER BY id")
    fun all(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM AccountEntity WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM AccountEntity WHERE baseUrl = :baseUrl AND username = :username")
    suspend fun find(
        baseUrl: String,
        username: String,
    ): AccountEntity?

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("UPDATE AccountEntity SET encryptedTokens = :tokens, signedOut = :signedOut WHERE id = :id")
    suspend fun setTokens(
        id: Long,
        tokens: ByteArray?,
        signedOut: Boolean,
    )

    @Query("UPDATE AccountEntity SET signedOut = 1 WHERE id = :id")
    suspend fun markSignedOut(id: Long)

    @Query("DELETE FROM AccountEntity WHERE id = :id")
    suspend fun delete(id: Long)
}
