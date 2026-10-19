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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubPreferencesEditor
import org.readium.r2.navigator.preferences.Preference
import org.readium.r2.navigator.preferences.RangePreference
import org.readium.r2.navigator.preferences.TextAlign
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign

/** A change to Readium's preferences, applied by an editor built on the stored value so quick taps don't undo each other. */
typealias EpubEdit = EpubPreferencesEditor.() -> Unit

/**
 * Every reading setting. Drawn in the reader palette ([ReaderColors]) without a scrim, so the page above shows each
 * change live. [editor] builds a Readium preferences editor for the current book; [onUpdate] stores a change.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    systemDark: Boolean,
    editor: (EpubPreferences) -> EpubPreferencesEditor,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onEpub: (EpubEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = remember(settings.epub) { editor(settings.epub) }
    ModalBottomSheet(onDismissRequest = onDismiss, scrimColor = Color.Transparent, properties = readerSheetProperties()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Heading(R.string.reader_theme)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val dark = settings.isDark(systemDark)
                readerThemes.forEach { theme ->
                    Swatch(theme, if (dark) theme.dark else theme.light, theme.name == settings.themeName) {
                        onUpdate { it.copy(themeName = theme.name) }
                    }
                }
            }
            val modes = listOf(null to R.string.reader_mode_system, false to R.string.reader_mode_light, true to R.string.reader_mode_dark)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (dark, label) ->
                    SegmentedButton(
                        selected = settings.dark == dark,
                        onClick = { onUpdate { it.copy(dark = dark) } },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(stringResource(label)) }
                }
            }

            Heading(R.string.reader_font)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                readerFontFamilies().forEach { family ->
                    FilterChip(
                        selected = family?.name == settings.fontFamily,
                        onClick = { onUpdate { it.copy(fontFamily = family?.name) } },
                        label = { Text(family?.name ?: stringResource(R.string.reader_font_publisher)) },
                    )
                }
            }
            Stepper(R.string.reader_font_size, shown, { fontSize }, onEpub)
            Stepper(R.string.reader_page_margins, shown, { pageMargins }, onEpub)
            Toggle(R.string.reader_scroll, shown, { scroll }, onEpub)
            Toggle(R.string.reader_publisher_styles, shown, { publisherStyles }, onEpub, R.string.reader_publisher_styles_hint)
            // Readium applies these only with the publisher's styles off.
            if (!shown.publisherStyles.effectiveValue) {
                Stepper(R.string.reader_line_height, shown, { lineHeight }, onEpub)
                Stepper(R.string.reader_letter_spacing, shown, { letterSpacing }, onEpub)
                Stepper(R.string.reader_word_spacing, shown, { wordSpacing }, onEpub)
                Text(stringResource(R.string.reader_text_align))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.textAlign.supportedValues.forEach { align ->
                        FilterChip(
                            selected = shown.textAlign.effectiveValue == align,
                            onClick = { onEpub { textAlign.set(align) } },
                            label = { Text(stringResource(align.label())) },
                        )
                    }
                }
                Toggle(R.string.reader_hyphens, shown, { hyphens }, onEpub)
            }

            SwitchRow(stringResource(R.string.reader_footnote_popup), settings.footnotePopup) { on ->
                onUpdate { it.copy(footnotePopup = on) }
            }

            Heading(R.string.reader_status_bar)
            // Task 10 adds the reader status bar's own items here.
            SwitchRow(stringResource(R.string.reader_keep_system_status_bar), settings.keepSystemStatusBar) { on ->
                onUpdate { it.copy(keepSystemStatusBar = on) }
            }
        }
    }
}

@Composable
private fun Heading(label: Int) {
    Text(stringResource(label), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Swatch(
    theme: ReaderTheme,
    palette: Palette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.width(64.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .size(48.dp)
                .background(palette.bg, shape)
                .border(
                    if (selected) {
                        BorderStroke(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    },
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) { Text("Aa", color = palette.fg) }
        Text(theme.label, style = MaterialTheme.typography.labelSmall, textAlign = ComposeTextAlign.Center, maxLines = 1)
    }
}

/** A Readium range preference, moved by the editor's own step within its range. */
@Composable
private fun Stepper(
    label: Int,
    shown: EpubPreferencesEditor,
    pref: EpubPreferencesEditor.() -> RangePreference<Double>,
    onEpub: (EpubEdit) -> Unit,
) {
    val name = stringResource(label)
    val p = shown.pref()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        val decrease = stringResource(R.string.reader_decrease, name)
        val increase = stringResource(R.string.reader_increase, name)
        TextButton(
            onClick = { onEpub { pref().decrement() } },
            enabled = p.effectiveValue > p.supportedRange.start,
            modifier = Modifier.semantics { contentDescription = decrease },
        ) { Text("−") }
        Text(p.formatValue(p.effectiveValue), Modifier.width(56.dp), textAlign = ComposeTextAlign.Center)
        TextButton(
            onClick = { onEpub { pref().increment() } },
            enabled = p.effectiveValue < p.supportedRange.endInclusive,
            modifier = Modifier.semantics { contentDescription = increase },
        ) { Text("+") }
    }
}

@Composable
private fun Toggle(
    label: Int,
    shown: EpubPreferencesEditor,
    pref: EpubPreferencesEditor.() -> Preference<Boolean>,
    onEpub: (EpubEdit) -> Unit,
    hint: Int? = null,
) {
    SwitchRow(stringResource(label), shown.pref().effectiveValue, hint?.let { stringResource(it) }) { on -> onEpub { pref().set(on) } }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

private fun TextAlign?.label(): Int =
    when (this) {
        TextAlign.LEFT -> R.string.reader_align_left
        TextAlign.RIGHT -> R.string.reader_align_right
        TextAlign.JUSTIFY -> R.string.reader_align_justify
        else -> R.string.reader_align_start
    }
