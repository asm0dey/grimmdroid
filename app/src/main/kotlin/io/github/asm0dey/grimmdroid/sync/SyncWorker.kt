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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.asm0dey.grimmdroid.R
import java.util.concurrent.TimeUnit

/** Implemented by the Application (Task 10) so the worker reaches the app's single [LibrarySync] without a WorkerFactory. */
interface SyncHost {
    val librarySync: LibrarySync
}

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1)
        if (accountId < 0) return Result.failure()
        val state = (applicationContext as SyncHost).librarySync.sync(accountId)
        // Unsupported and SignedOut are terminal: retrying can't help until the user acts.
        return if (state is SyncState.Failed) Result.retry() else Result.success()
    }

    // Expedited work runs as a foreground service below API 31 and needs a notification there.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val title = applicationContext.getString(R.string.sync_notification)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_MIN))
        val notification =
            Notification
                .Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 0x5C
    }
}

/** Periodic work is `sync-<id>`; the immediate run is `sync-<id>-now` so it never replaces the periodic schedule. */
fun scheduleSync(
    context: Context,
    accountId: Long,
    now: Boolean,
) {
    val wm = WorkManager.getInstance(context)
    val input = workDataOf(SyncWorker.KEY_ACCOUNT_ID to accountId)
    val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    wm.enqueueUniquePeriodicWork(
        "sync-$accountId",
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).setInputData(input).build(),
    )
    if (now) {
        wm.enqueueUniqueWork(
            "sync-$accountId-now",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}
