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

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalBannersTest {
    @get:Rule
    val compose = createComposeRule()

    private val none = BannerState(offline = false, syncing = null, signedOutServer = null, unsupportedVersion = null)

    @Test
    fun signedOutBannerShowsServerAndSignInIsClickable() {
        var clicks = 0
        compose.setContent { GlobalBanners(none.copy(signedOutServer = "books.example.net"), onSignIn = { clicks++ }) }
        compose
            .onNodeWithText("Signed out of books.example.net · Sign in")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun offlineShowsChip() {
        compose.setContent { GlobalBanners(none.copy(offline = true), onSignIn = {}) }
        compose.onNodeWithText("Offline").assertExists()
    }

    @Test
    fun syncingShowsProgress() {
        compose.setContent { GlobalBanners(none.copy(syncing = SyncState.Running(3, 10)), onSignIn = {}) }
        compose.onNodeWithContentDescription("Syncing library").assertExists()
    }

    @Test
    fun unsupportedShowsServerVersion() {
        compose.setContent { GlobalBanners(none.copy(unsupportedVersion = "v9.9.9"), onSignIn = {}) }
        compose.onNodeWithText("v9.9.9", substring = true).assertExists()
    }

    @Test
    fun nothingShownWhenAllClear() {
        compose.setContent { GlobalBanners(none, onSignIn = {}) }
        compose.onNodeWithText("Offline").assertDoesNotExist()
        compose.onNodeWithContentDescription("Syncing library").assertDoesNotExist()
    }
}
