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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.account.KeystoreTokenCipher
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.ServerProbe
import io.github.asm0dey.grimmdroid.api.authedClient
import io.github.asm0dey.grimmdroid.api.coverAuthInterceptor
import io.github.asm0dey.grimmdroid.api.coverAuthenticator
import io.github.asm0dey.grimmdroid.api.coverSession
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
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.ConcurrentHashMap

/** Every app-wide object, built once by [GrimmdroidApp]. */
class AppContainer(
    context: Context,
) {
    private val app = context.applicationContext

    val db: CatalogDb = Room.databaseBuilder(app, CatalogDb::class.java, "catalog.db").build()

    // One DataStore per file, in the default files/datastore/ dir that the backup rules exclude.
    val settings = SettingsStore(PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("settings") })
    val pendingOidc = PendingOidcStore(PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("oidc") })

    val accounts = AccountRepository(db.accountDao(), KeystoreTokenCipher(), settings)
    val connectivity = Connectivity(app)

    /** Carries no tokens: server probe, login and the OIDC identity provider. */
    val httpClient: HttpClient = httpClient()

    // shortcut: a removed account's client stays cached until process death (ids are never reused); Task 15 may drop it.
    private val authed = ConcurrentHashMap<Long, HttpClient>()

    fun authedClient(account: AccountEntity): HttpClient =
        authed.getOrPut(account.id) {
            authedClient(account.baseUrl, accounts.tokenSource(account.id))
        }

    fun api(account: AccountEntity) = GrimmoryApi(authedClient(account))

    val serverProbe = ServerProbe(httpClient)
    val passwordLogin = PasswordLogin(httpClient)

    // The identity provider must never receive Grimmory tokens.
    val oidcLogin = OidcLogin(httpClient)

    val librarySync = LibrarySync(db.catalogDao(), accounts, ::api, System::currentTimeMillis)
    val downloadRepository =
        DownloadRepository(
            db.catalogDao(),
            { id -> authedClient(checkNotNull(accounts.account(id)) { "No account $id" }) },
            app.filesDir,
        )
    val exporter = Exporter(app)
    val folderExport = FolderExport(app, settings)
    val afterDownload = AfterDownload(settings, folderExport)

    val imageLoader: ImageLoader =
        ImageLoader
            .Builder(app)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            val session = accounts.coverSession()
                            OkHttpClient
                                .Builder()
                                .addInterceptor(coverAuthInterceptor(session))
                                // Refreshes through the same per-server lock as the API clients; httpClient carries no tokens.
                                .authenticator(coverAuthenticator(session, httpClient))
                                .build()
                        },
                    ),
                )
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(app.cacheDir.resolve("covers").toOkioPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }.build()

    /** Drops every cached cover (memory and disk); for account removal. */
    fun clearCoverCache() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }
}
