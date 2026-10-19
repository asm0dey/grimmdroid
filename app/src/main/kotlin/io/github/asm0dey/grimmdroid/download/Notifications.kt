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
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.export.AfterDownloadOutcome
import io.github.asm0dey.grimmdroid.export.ExportActionActivity
import java.io.File

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
    file: File,
    outcome: AfterDownloadOutcome,
) {
    val text =
        when (outcome) {
            is AfterDownloadOutcome.Saved -> getString(R.string.saved_to_folder, outcome.folderName)
            AfterDownloadOutcome.NeedsFolder -> getString(R.string.choose_folder_hint)
            AfterDownloadOutcome.FolderLost -> getString(R.string.folder_lost_hint)
            AfterDownloadOutcome.SaveFailed -> getString(R.string.save_failed)
            else -> null
        }
    downloadChannel().notify(
        id,
        Notification
            .Builder(this, DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.download_done, title))
            .setContentText(text)
            .setAutoCancel(true)
            .addAction(exportAction(ExportActionActivity.ACTION_OPEN, R.string.open_action, android.R.drawable.ic_menu_view, file, title))
            .addAction(
                exportAction(ExportActionActivity.ACTION_SHARE, R.string.share_action, android.R.drawable.ic_menu_share, file, title),
            ).build(),
    )
}

private fun Context.exportAction(
    action: String,
    label: Int,
    icon: Int,
    file: File,
    title: String,
): Notification.Action {
    val intent =
        Intent(this, ExportActionActivity::class.java)
            .setAction(action)
            .setData(Uri.fromFile(file))
            .putExtra(ExportActionActivity.EXTRA_PATH, file.path)
            .putExtra(ExportActionActivity.EXTRA_TITLE, title)
    // The data URI makes each book's intent distinct: PendingIntents ignore extras when deciding whether two are the same.
    val pending = PendingIntent.getActivity(this, "$action:${file.path}".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pending).build()
}
