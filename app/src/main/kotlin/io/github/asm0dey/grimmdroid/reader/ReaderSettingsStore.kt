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
package io.github.asm0dey.grimmdroid.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubPreferencesSerializer

/** One item of the reader's own status bar (drawn by Task 10). */
enum class StatusItem {
    PROGRESS_BAR,
    PERCENT,
    PAGE_OF,
    PAGES_LEFT_IN_CHAPTER,
    CHAPTER_TITLE,
    CLOCK,
    BATTERY,
    TIME_LEFT_CHAPTER,
    TIME_LEFT_BOOK,
}

/** Phone-local reading settings, the same for every book. */
data class ReaderSettings(
    /** Readium's own preferences; colours and font family come from the fields below instead. */
    val epub: EpubPreferences = EpubPreferences(),
    val themeName: String = "default",
    /** null follows the system. */
    val dark: Boolean? = null,
    val footnotePopup: Boolean = true,
    val statusItems: List<StatusItem> = listOf(StatusItem.PROGRESS_BAR, StatusItem.PERCENT),
    val statusBarVisible: Boolean = true,
    val keepSystemStatusBar: Boolean = false,
    /** A Readium font family name; null keeps the publisher's. */
    val fontFamily: String? = null,
)

class ReaderSettingsStore(
    private val store: DataStore<Preferences>,
) {
    private val serializer = EpubPreferencesSerializer()

    val settings: Flow<ReaderSettings> = store.data.map { it.toSettings() }

    suspend fun update(f: (ReaderSettings) -> ReaderSettings) {
        store.edit { p -> p.write(f(p.toSettings())) }
    }

    private fun Preferences.toSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            epub = this[EPUB]?.let(::epubOrDefault) ?: defaults.epub,
            themeName = this[THEME] ?: defaults.themeName,
            dark = this[DARK],
            footnotePopup = this[FOOTNOTE_POPUP] ?: defaults.footnotePopup,
            // Items a newer or older build stored but this one doesn't know are dropped.
            statusItems =
                this[STATUS_ITEMS]?.split(',')?.mapNotNull { name -> StatusItem.entries.firstOrNull { it.name == name } }
                    ?: defaults.statusItems,
            statusBarVisible = this[STATUS_BAR_VISIBLE] ?: defaults.statusBarVisible,
            keepSystemStatusBar = this[KEEP_SYSTEM_STATUS_BAR] ?: defaults.keepSystemStatusBar,
            fontFamily = this[FONT_FAMILY],
        )
    }

    // A value an older or newer Readium wrote and this one can't read: start over from the defaults.
    private fun epubOrDefault(json: String): EpubPreferences = runCatching { serializer.deserialize(json) }.getOrDefault(EpubPreferences())

    private fun MutablePreferences.write(s: ReaderSettings) {
        this[EPUB] = serializer.serialize(s.epub)
        this[THEME] = s.themeName
        if (s.dark == null) remove(DARK) else this[DARK] = s.dark
        this[FOOTNOTE_POPUP] = s.footnotePopup
        this[STATUS_ITEMS] = s.statusItems.joinToString(",") { it.name }
        this[STATUS_BAR_VISIBLE] = s.statusBarVisible
        this[KEEP_SYSTEM_STATUS_BAR] = s.keepSystemStatusBar
        if (s.fontFamily == null) remove(FONT_FAMILY) else this[FONT_FAMILY] = s.fontFamily
    }

    private companion object {
        val EPUB = stringPreferencesKey("epub")
        val THEME = stringPreferencesKey("theme")
        val DARK = booleanPreferencesKey("dark")
        val FOOTNOTE_POPUP = booleanPreferencesKey("footnote_popup")
        val STATUS_ITEMS = stringPreferencesKey("status_items")
        val STATUS_BAR_VISIBLE = booleanPreferencesKey("status_bar_visible")
        val KEEP_SYSTEM_STATUS_BAR = booleanPreferencesKey("keep_system_status_bar")
        val FONT_FAMILY = stringPreferencesKey("font_family")
    }
}
