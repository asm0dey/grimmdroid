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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.asm0dey.grimmdroid.R

internal const val DOWNLOAD_CHANNEL = "downloads"

private fun Context.downloadChannel(): NotificationManager {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(DOWNLOAD_CHANNEL, getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_LOW),
    )
    return nm
}

/** [progress] is 0..1, or negative for indeterminate. */
internal fun Context.downloadProgressNotification(
    title: String,
    progress: Float,
): Notification {
    downloadChannel()
    return Notification
        .Builder(this, DOWNLOAD_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, (progress.coerceAtLeast(0f) * 100).toInt(), progress < 0)
        .build()
}

/** No-op on API 33+ without POST_NOTIFICATIONS; the download itself is unaffected. */
internal fun Context.notifyDownloaded(
    id: Int,
    title: String,
) {
    val notification =
        Notification
            .Builder(this, DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.download_done, title))
            .setAutoCancel(true)
            .build()
    downloadChannel().notify(id, notification)
}
