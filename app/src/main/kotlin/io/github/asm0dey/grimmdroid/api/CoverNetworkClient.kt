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
package io.github.asm0dey.grimmdroid.api

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.ktor3.asNetworkClient
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.ktor.client.HttpClient

/**
 * Sends each cover request through the current account's [authed] client, resolved per request so account switches
 * need no new ImageLoader. A signed-out (or absent) account uses [plain], which carries no tokens, so covers never
 * trigger a refresh per tile; a failure of either surfaces as a Coil error and the placeholder stays.
 */
class CoverNetworkClient(
    private val account: suspend () -> AccountEntity?,
    private val authed: (AccountEntity) -> HttpClient,
    private val plain: HttpClient,
) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T = (account()?.takeUnless { it.signedOut }?.let(authed) ?: plain).asNetworkClient().executeRequest(request, block)
}
