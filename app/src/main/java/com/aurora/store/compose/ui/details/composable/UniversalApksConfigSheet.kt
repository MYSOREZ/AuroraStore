/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.compose.ui.details.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.aurora.store.R
import com.aurora.store.compose.composable.SectionHeader
import java.util.Properties

/**
 * Bottom sheet for configuring a Universal APKS bundle before downloading.
 *
 * Shows device profiles from the Spoof Manager so the user can pick which devices to sweep
 * (each device profile = one purchase request with that device's full properties). Also lets
 * the user choose target locales and whether to include dynamic feature modules.
 *
 * [availableDevices] comes from [SpoofProvider.availableSpoofDeviceProperties]; each entry has
 * `UserReadableName`, `Build.PRODUCT`, and `Platforms` (comma-separated ABIs).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UniversalApksConfigSheet(
    availableDevices: List<Properties>,
    onDismiss: () -> Unit,
    onDownload: (
        deviceProductIds: Set<String>,
        locales: Set<String>,
        includeDynamicFeatures: Boolean
    ) -> Unit
) {
    // Default: pick one representative device per unique primary-ABI group so the user gets
    // common splits without selecting every single device in the list.
    val defaultSelected = remember(availableDevices) {
        availableDevices
            .groupBy { it.getProperty("Platforms", "").split(",").firstOrNull()?.trim() ?: "" }
            .values
            .mapNotNull { group -> group.firstOrNull()?.getProperty("Build.PRODUCT") }
            .toSet()
    }

    var selectedDeviceProductIds by remember(availableDevices) { mutableStateOf(defaultSelected) }
    var selectedLocales by remember { mutableStateOf(setOf("en")) }
    var includeDfs by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Devices
            SectionHeader(title = stringResource(R.string.universal_apks_section_devices))
            if (availableDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.universal_apks_no_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.spacing_medium))
                )
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimensionResource(R.dimen.spacing_medium)),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_xsmall))
                ) {
                    availableDevices.forEach { deviceProps ->
                        val productId = deviceProps.getProperty("Build.PRODUCT") ?: return@forEach
                        val name = deviceProps.getProperty("UserReadableName") ?: productId
                        val abis = deviceProps.getProperty("Platforms", "")
                            .split(",").map { it.trim() }.filter { it.isNotBlank() }
                        val chipLabel = if (abis.isNotEmpty()) "$name (${abis.joinToString()})" else name
                        FilterChip(
                            selected = productId in selectedDeviceProductIds,
                            onClick = {
                                selectedDeviceProductIds = if (productId in selectedDeviceProductIds)
                                    selectedDeviceProductIds - productId
                                else
                                    selectedDeviceProductIds + productId
                            },
                            label = { Text(chipLabel) }
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = dimensionResource(R.dimen.spacing_xsmall))
            )

            // Locales
            SectionHeader(title = stringResource(R.string.universal_apks_section_locales))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensionResource(R.dimen.spacing_medium)),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_xsmall))
            ) {
                COMMON_LOCALES.forEach { (code, label) ->
                    FilterChip(
                        selected = code in selectedLocales,
                        onClick = {
                            selectedLocales = if (code in selectedLocales)
                                selectedLocales - code else selectedLocales + code
                        },
                        label = { Text(label) }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = dimensionResource(R.dimen.spacing_xsmall))
            )

            // Extras
            SectionHeader(title = stringResource(R.string.universal_apks_section_extras))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { includeDfs = !includeDfs }
                    .padding(
                        horizontal = dimensionResource(R.dimen.spacing_medium),
                        vertical = dimensionResource(R.dimen.spacing_xsmall)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_small))
            ) {
                Checkbox(checked = includeDfs, onCheckedChange = { includeDfs = it })
                Text(
                    text = stringResource(R.string.universal_apks_include_dynamic_features),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = dimensionResource(R.dimen.spacing_medium),
                        vertical = dimensionResource(R.dimen.spacing_small)
                    ),
                enabled = selectedDeviceProductIds.isNotEmpty() || availableDevices.isEmpty(),
                onClick = {
                    onDownload(selectedDeviceProductIds, selectedLocales, includeDfs)
                }
            ) {
                Text(stringResource(R.string.action_download_universal_apks))
            }

            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private val COMMON_LOCALES = listOf(
    "en" to "EN",
    "ru" to "RU",
    "de" to "DE",
    "es" to "ES",
    "fr" to "FR",
    "zh" to "ZH",
    "ja" to "JA",
    "ko" to "KO",
    "pt" to "PT",
    "ar" to "AR",
    "hi" to "HI"
)
