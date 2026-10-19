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
package io.github.asm0dey.grimmdroid.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    @Test
    fun scheduleSyncEnqueuesPeriodicAndExpeditedNow() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        scheduleSync(ctx, 7, now = true)
        val wm = WorkManager.getInstance(ctx)
        val periodic = wm.getWorkInfosForUniqueWork("sync-7").get().single()
        assertEquals(TimeUnit.HOURS.toMillis(6), periodic.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        val once = wm.getWorkInfosForUniqueWork("sync-7-now").get().single()
        assertEquals(null, once.periodicityInfo)
        assertEquals(NetworkType.CONNECTED, once.constraints.requiredNetworkType)
        scheduleSync(ctx, 8, now = false)
        assertTrue(wm.getWorkInfosForUniqueWork("sync-8-now").get().isEmpty())
    }
}
