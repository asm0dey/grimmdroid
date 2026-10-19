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

import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.GrimmoryJson
import io.github.asm0dey.grimmdroid.api.TokenSource
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.security.GeneralSecurityException

class AccountRepository(
    private val dao: AccountDao,
    private val cipher: TokenCipher,
    private val settings: SettingsStore,
) {
    val accounts: Flow<List<AccountEntity>> = dao.all()

    val current: Flow<AccountEntity?> =
        combine(dao.all(), settings.currentAccountId) { all, id -> all.firstOrNull { it.id == id } }

    private fun seal(t: Tokens) = cipher.encrypt(GrimmoryJson.encodeToString(t).encodeToByteArray())

    suspend fun addOrUpdate(
        baseUrl: String,
        username: String,
        version: String,
        user: AppUserInfo,
        tokens: Tokens,
    ): Long {
        val id =
            dao.upsert(
                AccountEntity(
                    baseUrl = baseUrl,
                    username = username,
                    serverVersion = version,
                    canDownload = user.canDownload,
                    isAdmin = user.isAdmin,
                    encryptedTokens = seal(tokens),
                ),
            )
        settings.setCurrentAccountId(id)
        return id
    }

    suspend fun account(id: Long): AccountEntity? = dao.get(id)

    suspend fun updatePermissions(
        id: Long,
        user: AppUserInfo,
    ) = dao.setPermissions(id, user.canDownload, user.isAdmin)

    suspend fun setLastSyncedAt(
        id: Long,
        at: Long,
    ) = dao.setLastSyncedAt(id, at)

    suspend fun switchTo(id: Long) = settings.setCurrentAccountId(id)

    // shortcut: only the row and its tokens go here; Task 15 orchestrates catalogue and downloaded-file deletion (deleteDownloads).
    @Suppress("UNUSED_PARAMETER")
    suspend fun remove(
        id: Long,
        deleteDownloads: Boolean,
    ) {
        dao.delete(id)
        if (settings.currentAccountId.first() == id) {
            settings.setCurrentAccountId(
                dao
                    .all()
                    .first()
                    .firstOrNull()
                    ?.id,
            )
        }
    }

    fun tokenSource(id: Long): TokenSource =
        object : TokenSource {
            override suspend fun current(): Tokens? {
                val blob = dao.get(id)?.encryptedTokens ?: return null
                return try {
                    GrimmoryJson.decodeFromString<Tokens>(cipher.decrypt(blob).decodeToString())
                } catch (e: GeneralSecurityException) {
                    unreadable()
                } catch (e: IllegalArgumentException) {
                    // includes SerializationException
                    unreadable()
                }
            }

            // Blob restored onto another device (or key lost): force a fresh sign-in instead of failing forever.
            private suspend fun unreadable(): Tokens? {
                dao.markSignedOut(id)
                return null
            }

            override suspend fun save(t: Tokens) = dao.setTokens(id, seal(t), signedOut = false)

            // Idempotent: concurrent in-flight 401s all land on the same UPDATE.
            override suspend fun expired() = dao.markSignedOut(id)
        }
}
