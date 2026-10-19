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
package io.github.asm0dey.grimmdroid.export

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import io.github.asm0dey.grimmdroid.R
import java.io.File

/**
 * Invisible trampoline for the completion notification's Open and Share actions: a background worker can't start the
 * chooser, but tapping a notification action can start this activity. Not exported; only our PendingIntents reach it.
 */
class ExportActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let(::File)
        try {
            if (file == null || !file.isFile) {
                toast(R.string.file_not_available)
            } else {
                val exporter = Exporter(this)
                when (intent.action) {
                    ACTION_OPEN -> if (exporter.openWith(file) == ExportResult.NoApp) toast(R.string.no_app_to_open)
                    ACTION_SHARE -> exporter.share(file, intent.getStringExtra(EXTRA_TITLE) ?: file.name)
                }
            }
        } catch (e: IllegalArgumentException) {
            toast(R.string.file_not_available) // outside the FileProvider paths
        }
        finish()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_OPEN = "io.github.asm0dey.grimmdroid.OPEN"
        const val ACTION_SHARE = "io.github.asm0dey.grimmdroid.SHARE"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
    }
}
