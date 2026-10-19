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

import kotlinx.serialization.Serializable

@Serializable
data class PublicSettings(
    val oidcEnabled: Boolean = false,
    val remoteAuthEnabled: Boolean = false,
    val oidcForceOnlyMode: Boolean = false,
    val oidcProviderDetails: OidcProviderDetails? = null,
)

@Serializable
data class OidcProviderDetails(
    val providerName: String? = null,
    val clientId: String,
    val issuerUri: String,
    val scopes: String? = null,
)

@Serializable
data class VersionInfo(
    val current: String,
    val latest: String? = null,
)
