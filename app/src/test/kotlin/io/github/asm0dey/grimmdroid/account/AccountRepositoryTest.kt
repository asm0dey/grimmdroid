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

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException

private class FakeTokenCipher : TokenCipher {
    var decrypts = 0

    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(cipher: ByteArray): ByteArray {
        decrypts++
        return encrypt(cipher)
    }
}

@RunWith(AndroidJUnit4::class)
class AccountRepositoryTest {
    private lateinit var db: CatalogDb
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var repo: AccountRepository
    private val cipher = FakeTokenCipher()
    private val user = AppUserInfo(isAdmin = false, canDownload = true)
    private val tokens = Tokens("access-1", "refresh-1")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CatalogDb::class.java).allowMainThreadQueries().build()
        dir = Files.createTempDirectory("ds").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        repo = AccountRepository(db.accountDao(), cipher, SettingsStore(ds))
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun add(
        url: String,
        name: String = "u",
    ) = repo.addOrUpdate(url, name, "1.0", user, tokens)

    @Test
    fun twoAccountsLatestIsCurrent() =
        runBlocking {
            add("https://a")
            val b = add("https://b")
            assertEquals(2, repo.accounts.first().size)
            assertEquals(b, repo.current.first()?.id)
        }

    @Test
    fun readdingSameServerAndUserUpdatesInPlace() =
        runBlocking {
            val first = add("https://a")
            val second = repo.addOrUpdate("https://a", "u", "2.0", AppUserInfo(isAdmin = true), Tokens("x", "y"))
            assertEquals(first, second)
            val all = repo.accounts.first()
            assertEquals(1, all.size)
            assertEquals("2.0", all.single().serverVersion)
            assertTrue(all.single().isAdmin)
            assertEquals(Tokens("x", "y"), repo.tokenSource(first).current())
        }

    @Test
    fun tokensRoundTripAndAreEncryptedAtRest() =
        runBlocking {
            val id = add("https://a")
            assertEquals(tokens, repo.tokenSource(id).current())
            val stored =
                repo.accounts
                    .first()
                    .single()
                    .encryptedTokens!!
            assertFalse(stored.decodeToString().contains("access-1"))
        }

    @Test
    fun expiredKeepsAccountAndIsIdempotent() =
        runBlocking {
            val id = add("https://a")
            val src = repo.tokenSource(id)
            src.expired()
            src.expired()
            assertTrue(
                repo.accounts
                    .first()
                    .single()
                    .signedOut,
            )
            // The revoked tokens are dropped, not kept for the next request to try again.
            assertNull(src.current())
            assertNull(
                repo.accounts
                    .first()
                    .single()
                    .encryptedTokens,
            )
            src.save(Tokens("n", "m"))
            assertFalse(
                repo.accounts
                    .first()
                    .single()
                    .signedOut,
            )
        }

    @Test
    fun undecryptableBlobSignsOutAndReturnsNull() =
        runBlocking {
            val id = add("https://a")
            val broken =
                AccountRepository(
                    db.accountDao(),
                    object : TokenCipher {
                        override fun encrypt(plain: ByteArray) = plain

                        override fun decrypt(cipher: ByteArray): ByteArray = throw AEADBadTagException()
                    },
                    SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "other.preferences_pb") }),
                )
            assertNull(broken.tokenSource(id).current())
            val account = repo.accounts.first().single()
            assertTrue(account.signedOut)
            assertNull(account.encryptedTokens)
        }

    @Test
    fun removingCurrentSwitchesToRemainingThenNull() =
        runBlocking {
            val a = add("https://a")
            val b = add("https://b")
            repo.remove(b, deleteDownloads = false)
            assertEquals(a, repo.current.first()?.id)
            repo.remove(a, deleteDownloads = true)
            assertNull(repo.current.first())
        }

    @Test
    fun permissionsAndLastSyncedAtAreStored() =
        runBlocking {
            val id = add("https://a")
            repo.updatePermissions(id, AppUserInfo(isAdmin = true, canDownload = false))
            repo.setLastSyncedAt(id, 42)
            val a = repo.account(id)!!
            assertFalse(a.canDownload)
            assertTrue(a.isAdmin)
            assertEquals(42L, a.lastSyncedAt)
            assertEquals(tokens, repo.tokenSource(id).current())
        }

    @Test
    fun tokensAreDecryptedOnceAndEveryWriteRefreshesTheCache() =
        runBlocking {
            val id = add("https://a")
            repeat(3) { assertEquals(tokens, repo.tokenSource(id).current()) }
            assertEquals(1, cipher.decrypts)
            repo.tokenSource(id).save(Tokens("n", "m"))
            assertEquals(Tokens("n", "m"), repo.tokenSource(id).current())
            repo.addOrUpdate("https://a", "u", "1.0", user, Tokens("relogin", "r"))
            assertEquals(Tokens("relogin", "r"), repo.tokenSource(id).current())
            repo.remove(id, deleteDownloads = false)
            assertNull(repo.tokenSource(id).current())
        }
}
