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

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.download.DownloadRepository
import io.github.asm0dey.grimmdroid.reader.cfi.EpubArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Implemented by the Application, like `SyncHost`, so the worker reaches the app's single instances. */
interface PositionUploadHost {
    val positionSync: PositionSync
    val bookmarks: Bookmarks
    val readerDao: ReaderDao
    val accounts: AccountRepository
    val downloadRepository: DownloadRepository
}

/** Uploads one account's pending positions and bookmarks; HELD_BACK and REJECTED are done until the next reading session or a manual action. */
class PositionUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1)
        if (accountId < 0) return Result.failure()
        val host = applicationContext as PositionUploadHost
        val dao = host.readerDao
        host.positionSync.followUpStarted(accountId)
        val account = host.accounts.account(accountId)
        if (account == null) {
            dao.deleteAccount(accountId)
            return Result.success()
        }
        // Like SyncWorker: signed out is terminal until the user signs in again; the rows stay pending for then.
        if (account.signedOut) return Result.success()
        var failed = false
        for (row in dao.pendingPositions().filter { it.accountId == accountId }) {
            // The server wants the downloaded file's id; a book no longer on the phone keeps its row pending.
            val download =
                host.downloadRepository
                    .observe(accountId, row.bookId)
                    .first()
                    ?.takeIf { it.state == DownloadState.DONE } ?: continue
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        // One archive per book per run: EpubArchive isn't thread-safe.
                        EpubArchive(File(download.path)).use { archive ->
                            host.positionSync.upload(
                                accountId,
                                row.bookId,
                                download.fileId,
                                enqueueOnFailure = false,
                            ) { archive.cfiForLocator(it) }
                        }
                    } catch (_: IOException) {
                        null // file gone or unreadable: skip, keep pending
                    }
                }
            if (result == UploadResult.FAILED) failed = true
        }
        if (!host.bookmarks.flush(accountId)) failed = true
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"
    }
}

fun positionUploadWork(accountId: Long) = "pos-upload-$accountId"

fun enqueuePositionUpload(
    context: Context,
    accountId: Long,
) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        positionUploadWork(accountId),
        // Appends while a run is in progress, so a failure reported mid-run still gets a later run; reruns are harmless.
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<PositionUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofMillis(WorkRequest.MIN_BACKOFF_MILLIS))
            .setInputData(workDataOf(PositionUploadWorker.KEY_ACCOUNT_ID to accountId))
            .build(),
    )
}
