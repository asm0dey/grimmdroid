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
package io.github.asm0dey.grimmdroid.ui.settings

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkManager
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.catalog.CatalogDao
import io.github.asm0dey.grimmdroid.download.DownloadRepository
import io.github.asm0dey.grimmdroid.export.AfterDownloadAction
import io.github.asm0dey.grimmdroid.export.FolderExport
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import io.github.asm0dey.grimmdroid.settings.ThemeMode
import io.github.asm0dey.grimmdroid.sync.scheduleSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val app: Application,
    private val accounts: AccountRepository,
    private val settings: SettingsStore,
    private val catalog: CatalogDao,
    private val downloads: DownloadRepository,
    private val folderExport: FolderExport,
    private val dropClient: (Long) -> Unit,
    private val coverCacheSize: () -> Long,
    private val clearCoverCache: () -> Unit,
) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val accountList: StateFlow<List<AccountEntity>> = accounts.accounts.state(emptyList())
    val current: StateFlow<AccountEntity?> = accounts.current.state(null)
    val afterDownload = settings.afterDownload.state(AfterDownloadAction.NOTHING)
    val theme = settings.theme.state(ThemeMode.SYSTEM)
    val dynamicColor = settings.dynamicColor.state(true)

    /** The current account's export folder name, or null when none is chosen. */
    val folderName: StateFlow<String?> =
        settings.currentAccountId
            .flatMapLatest { id -> id?.let(settings::folderUri) ?: flowOf(null) }
            .map { uri -> uri?.let(::displayName) }
            .flowOn(Dispatchers.IO)
            .state(null)

    val booksBytes: StateFlow<Long> =
        settings.currentAccountId
            .flatMapLatest { id ->
                id?.let { catalog.downloads(it).map { list -> list.sumOf { d -> d.download.sizeBytes } } } ?: flowOf(0L)
            }.state(0L)

    private val _coverBytes = MutableStateFlow(0L)
    val coverBytes: StateFlow<Long> = _coverBytes.asStateFlow()

    init {
        refreshCoverSize()
    }

    private fun displayName(uri: Uri) = DocumentFile.fromTreeUri(app, uri)?.name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment

    private fun refreshCoverSize() {
        viewModelScope.launch { _coverBytes.value = withContext(Dispatchers.IO) { coverCacheSize() } }
    }

    fun setAfterDownload(action: AfterDownloadAction) {
        viewModelScope.launch { settings.setAfterDownload(action) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setTheme(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setFolder(uri: Uri) {
        val id = current.value?.id ?: return
        viewModelScope.launch { folderExport.setFolder(id, uri) }
    }

    fun syncNow(accountId: Long) = scheduleSync(app, accountId, now = true)

    fun clearCovers() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { clearCoverCache() }
            refreshCoverSize()
        }
    }

    fun signOut(
        accountId: Long,
        removeAccount: Boolean,
    ) {
        viewModelScope.launch { signOutNow(accountId, removeAccount) }
    }

    /**
     * Keep: forget the tokens only; catalogue and downloads stay for a later sign-in. Remove: stop its work, delete its
     * files, catalogue, row, tokens, client, folder grant and the (shared) cover cache.
     */
    internal suspend fun signOutNow(
        accountId: Long,
        removeAccount: Boolean,
    ) {
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork("sync-$accountId")
        wm.cancelUniqueWork("sync-$accountId-now")
        downloads.stopAll(app, accountId, deleteFiles = removeAccount)
        if (removeAccount) {
            catalog.deleteAccount(accountId)
            accounts.remove(accountId, deleteDownloads = true)
        } else {
            accounts.signOut(accountId)
        }
        dropClient(accountId)
        if (removeAccount) {
            folderExport.forget(accountId)
            withContext(Dispatchers.IO) { clearCoverCache() }
            refreshCoverSize()
        }
    }

    companion object {
        fun factory(container: AppContainer) =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        container.appContext as Application,
                        container.accounts,
                        container.settings,
                        container.db.catalogDao(),
                        container.downloadRepository,
                        container.folderExport,
                        container::dropAuthedClient,
                        { container.imageLoader.diskCache?.size ?: 0L },
                        container::clearCoverCache,
                    )
                }
            }
    }
}
