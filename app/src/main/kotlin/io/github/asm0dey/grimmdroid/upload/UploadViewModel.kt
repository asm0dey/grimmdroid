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
package io.github.asm0dey.grimmdroid.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.asm0dey.grimmdroid.api.LibraryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

/** One library folder a book can be uploaded into. */
data class TargetOption(
    val libraryId: Long,
    val pathId: Long,
    val label: String,
    val watched: Boolean,
)

fun targetOptions(libraries: List<LibraryDto>): List<TargetOption> =
    libraries.flatMap { lib ->
        lib.paths.map { p -> TargetOption(lib.id, p.id, if (lib.paths.size == 1) lib.name else "${lib.name} — ${p.path}", lib.watch) }
    }

sealed interface UploadState {
    data object Preparing : UploadState

    data class ChooseTarget(
        val options: List<TargetOption>,
    ) : UploadState

    data class Uploading(
        val progress: Float,
    ) : UploadState

    data object Waiting : UploadState

    data object NotPickedUp : UploadState

    data object NoPermission : UploadState

    data object NotEpub : UploadState

    data class Failed(
        val message: String,
    ) : UploadState

    data object Opening : UploadState

    data object Opened : UploadState

    data class OpenFailed(
        val message: String,
    ) : UploadState
}

/** Copies the incoming file, picks the target folder (remembered per account), uploads, then opens the book. */
class UploadViewModel(
    private val copy: suspend () -> Pair<File, String>?,
    private val libraries: suspend () -> List<LibraryDto>,
    private val savedTarget: suspend () -> Pair<Long, Long>?,
    private val saveTarget: suspend (Pair<Long, Long>) -> Unit,
    private val upload: suspend (File, String, UploadTarget, (Float) -> Unit) -> UploadResult,
    private val open: suspend (bookId: Long) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow<UploadState>(UploadState.Preparing)
    val state: StateFlow<UploadState> = _state.asStateFlow()
    private var file: Pair<File, String>? = null

    init {
        step {
            val copied = copy() ?: return@step UploadState.Failed("Can't read the file")
            file = copied
            if (!copied.second.endsWith(".epub", ignoreCase = true)) return@step UploadState.NotEpub
            val options = targetOptions(libraries())
            val saved = savedTarget()
            val target =
                options.firstOrNull { (it.libraryId to it.pathId) == saved }
                    ?: options.singleOrNull()?.also { saveTarget(it.libraryId to it.pathId) }
                    ?: return@step UploadState.ChooseTarget(options)
            send(target)
            null
        }
    }

    fun choose(option: TargetOption) =
        step {
            saveTarget(option.libraryId to option.pathId)
            send(option)
            null
        }

    /** Runs [block] in the ViewModel's scope; a state it returns is shown, as is any failure. */
    private fun step(block: suspend () -> UploadState?) {
        viewModelScope.launch {
            try {
                block()?.let { _state.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UploadState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun send(option: TargetOption) {
        val (f, name) = file ?: return
        _state.value = UploadState.Uploading(0f)
        val result =
            upload(f, name, UploadTarget(option.libraryId, option.pathId, option.watched)) { p ->
                _state.value = if (p >= 1f) UploadState.Waiting else UploadState.Uploading(p)
            }
        _state.value =
            when (result) {
                is UploadResult.Found -> openBook(result.bookId)
                UploadResult.NotPickedUp -> UploadState.NotPickedUp
                UploadResult.NoPermission -> UploadState.NoPermission
                is UploadResult.Failed -> UploadState.Failed(result.error.message ?: result.error.javaClass.simpleName)
            }
    }

    /** The upload worked by now: a failure from here on is reported as such, never as a failed upload. */
    private suspend fun openBook(bookId: Long): UploadState {
        _state.value = UploadState.Opening
        return try {
            open(bookId)
            UploadState.Opened
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadState.OpenFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun onCleared() {
        file?.first?.let { Files.deleteIfExists(it.toPath()) }
    }
}
