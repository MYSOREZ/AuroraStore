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

/**
 * Bottom sheet that lets the user choose which APK splits to include in the
 * Universal APKS bundle before starting the download.
 *
 * @param onDismiss Called when the sheet is dismissed without starting a download
 * @param onDownload Called with the user's selections when the Download button is tapped
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UniversalApksConfigSheet(
    onDismiss: () -> Unit,
    onDownload: (
        abis: Set<String>,
        densities: Set<Int>,
        locales: Set<String>,
        includeDynamicFeatures: Boolean
    ) -> Unit
) {
    var selectedAbis by remember {
        mutableStateOf(setOf("arm64-v8a", "armeabi-v7a"))
    }
    var selectedDensities by remember {
        mutableStateOf(ALL_DENSITIES.map { it.first }.toSet())
    }
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
            // Architecture
            SectionHeader(title = stringResource(R.string.universal_apks_section_architecture))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensionResource(R.dimen.spacing_medium)),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_xsmall))
            ) {
                ALL_ABIS.forEach { abi ->
                    FilterChip(
                        selected = abi in selectedAbis,
                        onClick = {
                            selectedAbis = if (abi in selectedAbis) selectedAbis - abi
                            else selectedAbis + abi
                        },
                        label = { Text(abi) }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = dimensionResource(R.dimen.spacing_xsmall))
            )

            // Screen density
            SectionHeader(title = stringResource(R.string.universal_apks_section_density))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensionResource(R.dimen.spacing_medium)),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_xsmall))
            ) {
                ALL_DENSITIES.forEach { (dpi, label) ->
                    FilterChip(
                        selected = dpi in selectedDensities,
                        onClick = {
                            selectedDensities = if (dpi in selectedDensities)
                                selectedDensities - dpi else selectedDensities + dpi
                        },
                        label = { Text(label) }
                    )
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
                enabled = selectedAbis.isNotEmpty(),
                onClick = {
                    onDownload(selectedAbis, selectedDensities, selectedLocales, includeDfs)
                }
            ) {
                Text(stringResource(R.string.action_download_universal_apks))
            }

            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private val ALL_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "armeabi")

private val ALL_DENSITIES = listOf(
    640 to "xxxhdpi",
    480 to "xxhdpi",
    320 to "xhdpi",
    240 to "hdpi",
    160 to "mdpi",
    120 to "ldpi",
    213 to "tvdpi"
)

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
