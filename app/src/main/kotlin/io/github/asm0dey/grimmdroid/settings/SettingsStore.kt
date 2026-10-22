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
package io.github.asm0dey.grimmdroid.settings

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.asm0dey.grimmdroid.export.AfterDownloadAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Layout { GRID, LIST }

class SettingsStore(
    private val store: DataStore<Preferences>,
) {
    val currentAccountId: Flow<Long?> = store.data.map { it[CURRENT_ACCOUNT] }

    suspend fun setCurrentAccountId(id: Long?) {
        store.edit { if (id == null) it.remove(CURRENT_ACCOUNT) else it[CURRENT_ACCOUNT] = id }
    }

    val afterDownload: Flow<AfterDownloadAction> =
        store.data.map { p -> AfterDownloadAction.entries.firstOrNull { it.name == p[AFTER_DOWNLOAD] } ?: AfterDownloadAction.NOTHING }

    suspend fun setAfterDownload(action: AfterDownloadAction) {
        store.edit { it[AFTER_DOWNLOAD] = action.name }
    }

    val theme: Flow<ThemeMode> =
        store.data.map { p -> ThemeMode.entries.firstOrNull { it.name == p[THEME] } ?: ThemeMode.SYSTEM }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    val dynamicColor: Flow<Boolean> = store.data.map { it[DYNAMIC_COLOR] ?: true }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = enabled }
    }

    val animatePageTurns: Flow<Boolean> = store.data.map { it[ANIMATE_PAGE_TURNS] ?: false }

    suspend fun setAnimatePageTurns(enabled: Boolean) {
        store.edit { it[ANIMATE_PAGE_TURNS] = enabled }
    }

    val libraryLayout: Flow<Layout> = store.data.map { p -> Layout.entries.firstOrNull { it.name == p[LAYOUT] } ?: Layout.GRID }

    suspend fun setLibraryLayout(layout: Layout) {
        store.edit { it[LAYOUT] = layout.name }
    }

    /** The tree URI the user picked for [accountId]'s saved books, if any. */
    fun folderUri(accountId: Long): Flow<Uri?> = store.data.map { p -> p[folderKey(accountId)]?.let(Uri::parse) }

    suspend fun setFolderUri(
        accountId: Long,
        uri: Uri?,
    ) {
        store.edit { if (uri == null) it.remove(folderKey(accountId)) else it[folderKey(accountId)] = uri.toString() }
    }

    /** The (libraryId, pathId) [accountId]'s uploads go to, once chosen. */
    fun uploadTarget(accountId: Long): Flow<Pair<Long, Long>?> =
        store.data.map { p ->
            p[uploadKey(accountId)]?.split(':')?.let { (lib, path) -> lib.toLong() to path.toLong() }
        }

    suspend fun setUploadTarget(
        accountId: Long,
        target: Pair<Long, Long>?,
    ) {
        store.edit {
            if (target ==
                null
            ) {
                it.remove(uploadKey(accountId))
            } else {
                it[uploadKey(accountId)] = "${target.first}:${target.second}"
            }
        }
    }

    private companion object {
        val AFTER_DOWNLOAD = stringPreferencesKey("after_download")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ANIMATE_PAGE_TURNS = booleanPreferencesKey("animate_page_turns")
        val LAYOUT = stringPreferencesKey("library_layout")

        fun folderKey(accountId: Long) = stringPreferencesKey("folder_uri_$accountId")

        fun uploadKey(accountId: Long) = stringPreferencesKey("upload_target_$accountId")

        val CURRENT_ACCOUNT = longPreferencesKey("current_account_id")
    }
}
