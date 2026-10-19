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
package io.github.asm0dey.grimmdroid

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.NetworkFetcher
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.account.KeystoreTokenCipher
import io.github.asm0dey.grimmdroid.api.CoverNetworkClient
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.ServerProbe
import io.github.asm0dey.grimmdroid.api.authedClient
import io.github.asm0dey.grimmdroid.api.httpClient
import io.github.asm0dey.grimmdroid.auth.OidcLogin
import io.github.asm0dey.grimmdroid.auth.PasswordLogin
import io.github.asm0dey.grimmdroid.auth.PendingOidcStore
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.download.DownloadRepository
import io.github.asm0dey.grimmdroid.export.AfterDownload
import io.github.asm0dey.grimmdroid.export.Exporter
import io.github.asm0dey.grimmdroid.export.FolderExport
import io.github.asm0dey.grimmdroid.net.Connectivity
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import io.github.asm0dey.grimmdroid.sync.LibrarySync
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toOkioPath
import java.util.concurrent.ConcurrentHashMap

/** Every app-wide object, built once by [GrimmdroidApp]. */
class AppContainer(
    context: Context,
) {
    private val app = context.applicationContext
    val appContext: Context get() = app

    /** Outlives every screen: for work a navigation away must not cancel (account removal). */
    val appScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Log.e("AppContainer", "Background work failed: ${e::class.java.name}") },
        )

    /** One-shot: a sign-out or account removal failed part-way; AppNav shows a snackbar. */
    val signOutFailures = Channel<Unit>(Channel.CONFLATED)

    val db: CatalogDb = Room.databaseBuilder(app, CatalogDb::class.java, "catalog.db").build()

    // One DataStore per file, in the default files/datastore/ dir that the backup rules exclude.
    val settings = SettingsStore(PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("settings") })
    val pendingOidc = PendingOidcStore(PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("oidc") })

    val accounts = AccountRepository(db.accountDao(), KeystoreTokenCipher(), settings)
    val connectivity = Connectivity(app)

    /** Carries no tokens: server probe, login and the OIDC identity provider. */
    val httpClient: HttpClient = httpClient()

    private val authed = ConcurrentHashMap<Long, HttpClient>()

    fun authedClient(account: AccountEntity): HttpClient =
        authed.getOrPut(account.id) {
            authedClient(account.baseUrl, accounts.tokenSource(account.id))
        }

    /** Drops and closes [accountId]'s cached client, so a sign-out or removal leaves no tokens in a live client. */
    fun dropAuthedClient(accountId: Long) {
        authed.remove(accountId)?.close()
    }

    fun api(account: AccountEntity) = GrimmoryApi(authedClient(account))

    val serverProbe = ServerProbe(httpClient)
    val passwordLogin = PasswordLogin(httpClient)

    // The identity provider must never receive Grimmory tokens.
    val oidcLogin = OidcLogin(httpClient)

    val downloadRepository =
        DownloadRepository(
            db.catalogDao(),
            { id -> authedClient(checkNotNull(accounts.account(id)) { "No account $id" }) },
            app.filesDir,
        )
    val librarySync =
        LibrarySync(db.catalogDao(), accounts, ::api, System::currentTimeMillis) { accountId, bookId ->
            downloadRepository.delete(app, accountId, bookId)
        }
    val exporter = Exporter(app)
    val folderExport = FolderExport(app, settings)
    val afterDownload = AfterDownload(settings, folderExport)

    val imageLoader: ImageLoader =
        ImageLoader
            .Builder(app)
            .components {
                // Covers share the per-account authed client, so its refresh path and host scoping apply to them too.
                add(NetworkFetcher.Factory({ CoverNetworkClient({ accounts.current.first() }, ::authedClient, httpClient) }))
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(app.cacheDir.resolve("covers").toOkioPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }.build()

    /** Drops every cached cover (memory and disk); for account removal. Blocks: call it off the main thread. */
    fun clearCoverCache() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }
}
