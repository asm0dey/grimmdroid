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
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import io.github.asm0dey.grimmdroid.R
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import java.io.File

class ReaderActivity : FragmentActivity() {
    private var publication: Publication? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // A restored navigator fragment needs a factory before the publication is open again.
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        setContentView(FragmentContainerView(this).apply { id = R.id.reader_navigator })
        val accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File) ?: return finish()
        val fontsDirs = listOf(filesDir.resolve("fonts/$accountId"), filesDir.resolve("fonts/local"))
        lifecycleScope.launch {
            val pub =
                openPublication(file, fontsDirs).getOrElse {
                    Log.w(TAG, "Cannot open $file", it)
                    Toast.makeText(this@ReaderActivity, it.message, Toast.LENGTH_LONG).show()
                    return@launch finish()
                }
            publication = pub
            // A commit after onSaveInstanceState throws: wait until the activity is visible again.
            withStarted {
                supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(initialLocator = null)
                supportFragmentManager.commit { replace(R.id.reader_navigator, EpubNavigatorFragment::class.java, null) }
            }
        }
    }

    override fun onDestroy() {
        publication?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReaderActivity"
        private const val EXTRA_ACCOUNT_ID = "accountId"
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_FILE = "file"

        fun intent(
            context: Context,
            accountId: Long,
            bookId: Long,
            file: File,
        ): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_FILE, file.path)
    }
}
