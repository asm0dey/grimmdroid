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

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The status bar's clock and battery follow the screen's start and stop. */
@RunWith(AndroidJUnit4::class)
class StatusBarLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val owner =
        object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }

    private fun setState(state: Lifecycle.State) = compose.runOnIdle { owner.registry.currentState = state }

    @Test
    fun clockReadsTheTimeAgainOnStart() {
        var wall = 1_000L
        var shown = 0L
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { shown = minuteClock { wall } } }
        compose.runOnIdle { assertEquals(1_000L, shown) }
        setState(Lifecycle.State.CREATED)
        wall = 30_000L // well within the minute: no tick would come
        setState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(30_000L, shown) }
    }

    @Test
    fun batteryListensOnlyWhileStartedAndRereadsOnStart() {
        battery(83)
        var shown: Int? = null
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { shown = batteryPercent() } }
        compose.runOnIdle { assertEquals(83, shown) }
        assertEquals(1, batteryReceivers())

        setState(Lifecycle.State.CREATED)
        assertEquals(0, batteryReceivers())
        battery(41)
        compose.runOnIdle { assertEquals(83, shown) }

        setState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(41, shown) }
        assertEquals(1, batteryReceivers())
    }

    private fun battery(percent: Int) {
        @Suppress("DEPRECATION") // the system's battery broadcast is sticky
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, percent).putExtra(BatteryManager.EXTRA_SCALE, 100),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun batteryReceivers() = shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) }
}
