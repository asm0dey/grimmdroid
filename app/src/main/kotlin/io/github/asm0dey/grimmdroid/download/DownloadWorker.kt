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
package io.github.asm0dey.grimmdroid.download

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.asm0dey.grimmdroid.export.AfterDownload
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Implemented by the Application (Task 10), like `SyncHost`, so the worker reaches the app's single [DownloadRepository]. */
interface DownloadHost {
    val downloadRepository: DownloadRepository
    val afterDownload: AfterDownload
}

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val accountId get() = inputData.getLong(KEY_ACCOUNT_ID, -1)
    private val bookId get() = inputData.getLong(KEY_BOOK_ID, -1)

    // Per book, so parallel downloads each get their own notification.
    private val notificationId get() = "dl-$accountId-$bookId".hashCode()

    override suspend fun doWork(): Result {
        if (accountId < 0 || bookId < 0) return Result.failure()
        val host = applicationContext as DownloadHost
        val repo = host.downloadRepository
        val title = repo.title(accountId, bookId) ?: "book-$bookId"
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        return try {
            try {
                setForeground(foregroundInfo(title, -1f))
            } catch (e: IllegalStateException) {
                // Started from the background on API 31+: download without a foreground notification.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
                Log.w(TAG, "Foreground start not allowed; downloading in the background", e)
            }
            val file =
                repo.download(
                    accountId,
                    bookId,
                    inputData.getLong(KEY_FILE_ID, -1).takeIf { it >= 0 },
                    inputData.getBoolean(KEY_PRIMARY, true),
                ) { nm.notify(notificationId, applicationContext.downloadProgressNotification(title, it)) }
            val outcome = host.afterDownload.run(accountId, file)
            // Different id from the foreground one, which WorkManager removes when the worker ends.
            applicationContext.notifyDownloaded(notificationId + 1, title, file, outcome)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to downloadErrorCode(e))) // download() already marked the row FAILED; retry is from the UI
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("book-$bookId", -1f)

    private fun foregroundInfo(
        title: String,
        progress: Float,
    ): ForegroundInfo {
        val notification = applicationContext.downloadProgressNotification(title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_ACCOUNT_ID = "accountId"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_FILE_ID = "fileId"
        const val KEY_PRIMARY = "isPrimary"

        /** Output of a failed run: [ERROR_NO_PERMISSION], [ERROR_NETWORK] or [ERROR_OTHER]. */
        const val KEY_ERROR = "error"
        const val ERROR_NO_PERMISSION = "no_permission"
        const val ERROR_NETWORK = "network"
        const val ERROR_OTHER = "other"
    }
}

internal fun downloadErrorCode(e: Throwable): String =
    when (e) {
        is NoDownloadPermission -> DownloadWorker.ERROR_NO_PERMISSION
        is IOException -> DownloadWorker.ERROR_NETWORK
        else -> DownloadWorker.ERROR_OTHER
    }
