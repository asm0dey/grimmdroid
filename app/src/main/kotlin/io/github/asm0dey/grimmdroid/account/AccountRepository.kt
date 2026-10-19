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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Decrypted tokens per account, so cover requests don't hit Room and the Keystore each time. Every token write goes
    // through this class under [tokenLock], so a read racing a write can't cache the old tokens.
    private val tokenCache = HashMap<Long, Tokens>()
    private val tokenLock = Mutex()

    private fun seal(t: Tokens) = cipher.encrypt(GrimmoryJson.encodeToString(t).encodeToByteArray())

    suspend fun addOrUpdate(
        baseUrl: String,
        username: String,
        version: String,
        user: AppUserInfo,
        tokens: Tokens,
    ): Long {
        val id =
            tokenLock.withLock {
                dao
                    .upsert(
                        AccountEntity(
                            baseUrl = baseUrl,
                            username = username,
                            serverVersion = version,
                            canDownload = user.canDownload,
                            isAdmin = user.isAdmin,
                            encryptedTokens = seal(tokens),
                        ),
                    ).also { tokenCache.remove(it) }
            }
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

    /** Forgets the tokens but keeps the account, its catalogue and downloads; the "Sign in" banner offers re-login. */
    suspend fun signOut(id: Long) =
        tokenLock.withLock {
            dao.setTokens(id, null, signedOut = true)
            tokenCache.remove(id)
        }

    // Only the row and its tokens go here; SettingsViewModel orchestrates catalogue, file and folder cleanup.
    @Suppress("UNUSED_PARAMETER")
    suspend fun remove(
        id: Long,
        deleteDownloads: Boolean,
    ) {
        tokenLock.withLock {
            dao.delete(id)
            tokenCache.remove(id)
        }
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
            override suspend fun current(): Tokens? = tokenLock.withLock { tokenCache[id] ?: read() }

            private suspend fun read(): Tokens? {
                val blob = dao.get(id)?.encryptedTokens ?: return null
                return try {
                    GrimmoryJson.decodeFromString<Tokens>(cipher.decrypt(blob).decodeToString()).also { tokenCache[id] = it }
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

            override suspend fun save(t: Tokens) =
                tokenLock.withLock {
                    dao.setTokens(id, seal(t), signedOut = false)
                    tokenCache[id] = t
                }

            // Idempotent: concurrent in-flight 401s all land on the same UPDATE.
            override suspend fun expired() =
                tokenLock.withLock {
                    tokenCache.remove(id)
                    dao.markSignedOut(id)
                }
        }
}
