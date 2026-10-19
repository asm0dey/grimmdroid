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

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.asm0dey.grimmdroid.catalog.CatalogDao
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** The server answered 403: the account lacks the canDownload permission. */
class NoDownloadPermission : IOException("Your account can't download books")

/**
 * Downloads one file per book into `filesDir/books/<accountId>/<bookId>/`. [http] returns the account's authed client.
 * A primary download with no known file id is stored under fileId 0.
 */
class DownloadRepository(
    private val dao: CatalogDao,
    private val http: suspend (accountId: Long) -> HttpClient,
    private val filesDir: File,
) {
    fun observe(
        accountId: Long,
        bookId: Long,
    ): Flow<DownloadEntity?> = dao.download(accountId, bookId)

    suspend fun title(
        accountId: Long,
        bookId: Long,
    ): String? = dao.book(accountId, bookId).first()?.title

    private fun bookDir(
        accountId: Long,
        bookId: Long,
    ) = File(filesDir, "books/$accountId/$bookId")

    // In-process jobs of running downloads, so delete() can stop one and wait for its cleanup before removing files.
    private val active = ConcurrentHashMap<Pair<Long, Long>, Job>()

    /**
     * [onProgress] gets 0..1, or -1 when the server sends no Content-Length. On failure the row is marked FAILED and the
     * error rethrown; an earlier completed copy is only replaced once the new file has been fully written.
     */
    suspend fun download(
        accountId: Long,
        bookId: Long,
        fileId: Long?,
        isPrimary: Boolean,
        onProgress: (Float) -> Unit,
    ): File {
        val url =
            if (isPrimary) "api/v1/books/$bookId/download" else "api/v1/books/$bookId/files/${requireNotNull(fileId)}/download"
        // primaryFileName comes from the server: sanitised like any other name.
        val fallback = safeFileName((if (isPrimary) dao.book(accountId, bookId).first()?.primaryFileName else null) ?: "", "book-$bookId")
        val dir = bookDir(accountId, bookId)
        val previous = dao.download(accountId, bookId).first()?.takeIf { it.state == DownloadState.DONE }
        var row = DownloadEntity(accountId, bookId, fileId ?: 0, fallback, "", 0, DownloadState.RUNNING, 0f)
        val key = accountId to bookId
        val job = currentCoroutineContext()[Job]?.also { active[key] = it }
        dao.deleteBookDownloads(accountId, bookId)
        dao.upsertDownload(row)
        var part: File? = null
        try {
            val file =
                withContext(Dispatchers.IO) {
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
                    http(accountId).prepareGet(url) { expectSuccess = false }.execute { response ->
                        if (response.status == HttpStatusCode.Forbidden) throw NoDownloadPermission()
                        if (!response.status.isSuccess()) throw IOException("Download failed: HTTP ${response.status}")
                        val name = safeFileName(parseContentDisposition(response.headers[HttpHeaders.ContentDisposition]) ?: "", fallback)
                        val target = File(dir, name)
                        // Defence in depth: safeFileName already yields one path component.
                        if (target.canonicalFile.parentFile != dir.canonicalFile) throw IOException("Unsafe file name")
                        val tmp = File(dir, "$name.part").also { part = it }
                        val total = response.contentLength() ?: -1L
                        val channel = response.bodyAsChannel()
                        var received = 0L
                        var lastPercent = -1L
                        if (total < 0) onProgress(-1f)
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = channel.readAvailable(buf, 0, buf.size)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                received += n
                                if (total > 0 && received * 100 / total != lastPercent) {
                                    lastPercent = received * 100 / total
                                    val p = received.toFloat() / total
                                    onProgress(p)
                                    dao.setDownloadProgress(accountId, bookId, p)
                                }
                            }
                        }
                        if (total >= 0 && received != total) throw IOException("Connection closed after $received of $total bytes")
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        // Only now drop the earlier copy (possibly under another name) and any stale .part files.
                        dir.listFiles()?.filter { it != target }?.forEach { it.deleteRecursively() }
                        row = row.copy(fileName = name, path = target.path, sizeBytes = received, state = DownloadState.DONE, progress = 1f)
                        if (total < 0) onProgress(1f)
                        target
                    }
                }
            dao.upsertDownload(row)
            return file
        } catch (e: CancellationException) {
            // Cancelled: back to the earlier copy if there is one, else forget the download so the UI offers Download again.
            withContext(NonCancellable) {
                part?.delete()
                dao.deleteBookDownloads(accountId, bookId)
                previous?.takeIf { File(it.path).exists() }?.let { dao.upsertDownload(it) }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                part?.delete()
                val kept = previous?.takeIf { File(it.path).exists() }
                if (kept != null) {
                    dao.deleteBookDownloads(accountId, bookId)
                    dao.upsertDownload(kept)
                } else {
                    dao.upsertDownload(row.copy(state = DownloadState.FAILED))
                }
            }
            throw e
        } finally {
            if (job != null) active.remove(key, job)
        }
    }

    /** Stops a queued or running download of the book first, then removes its files and row. */
    suspend fun delete(
        context: Context,
        accountId: Long,
        bookId: Long,
    ) {
        cancel(context, accountId, bookId)
        active[accountId to bookId]?.cancelAndJoin()
        withContext(Dispatchers.IO) { bookDir(accountId, bookId).deleteRecursively() }
        dao.deleteBookDownloads(accountId, bookId)
    }

    fun enqueue(
        context: Context,
        accountId: Long,
        bookId: Long,
        fileId: Long?,
        isPrimary: Boolean,
    ) {
        val input =
            workDataOf(
                DownloadWorker.KEY_ACCOUNT_ID to accountId,
                DownloadWorker.KEY_BOOK_ID to bookId,
                DownloadWorker.KEY_FILE_ID to (fileId ?: -1L),
                DownloadWorker.KEY_PRIMARY to isPrimary,
            )
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(accountId, bookId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(input)
                .build(),
        )
    }

    /** The book's latest download work: still queued before the worker writes a row; a failure's kind is [DownloadWorker.KEY_ERROR]. */
    fun work(
        context: Context,
        accountId: Long,
        bookId: Long,
    ): Flow<WorkInfo?> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(accountId, bookId)).map { it.lastOrNull() }

    /** The running worker's cancellation deletes the `.part` file and the row (see [download]). */
    fun cancel(
        context: Context,
        accountId: Long,
        bookId: Long,
    ) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(accountId, bookId))
    }

    private fun workName(
        accountId: Long,
        bookId: Long,
    ) = "dl-$accountId-$bookId"
}
