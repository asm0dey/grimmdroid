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
package io.github.asm0dey.grimmdroid.ui.nav

import io.github.asm0dey.grimmdroid.settings.LastBook
import io.github.asm0dey.grimmdroid.settings.StartScreen

/** Where a cold launch lands: the tab to show and the book to reopen on top of it, if any. */
internal fun launchFor(
    choice: StartScreen,
    lastTab: String?,
    lastBook: LastBook?,
    currentAccountId: Long?,
    fileExists: (String) -> Boolean,
): Pair<Tab, LastBook?> {
    val tab = Tab.entries.firstOrNull { it.name == lastTab } ?: Tab.HOME
    // Another account's book, or one deleted since, can't be reopened: fall back to the tab.
    val book = lastBook?.takeIf { it.accountId == currentAccountId && fileExists(it.file) }
    return when (choice) {
        StartScreen.HOME -> Tab.HOME to null
        StartScreen.LIBRARY -> Tab.LIBRARY to null
        StartScreen.LAST_BOOK -> tab to book
        StartScreen.LAST_PLACE -> tab to book?.takeIf { it.open }
    }
}
