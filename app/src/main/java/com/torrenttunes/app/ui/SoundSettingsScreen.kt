package com.torrenttunes.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import com.torrenttunes.app.audio.AudioEffectsManager
import com.torrenttunes.app.audio.PRESET_CUSTOM
import com.torrenttunes.app.audio.MAX_BASS_MILLI_BEL
import com.torrenttunes.app.audio.MAX_LOUDNESS_MILLI_BEL
import com.torrenttunes.app.audio.MAX_LOUDNESS_MILLI_BEL
import com.torrenttunes.app.ui.components.PineIconButton
import com.torrenttunes.app.audio.SoundCapabilities

import com.torrenttunes.app.audio.SoundPreset

import com.torrenttunes.app.ui.theme.PineSurface
import com.torrenttunes.app.audio.milliBelToSlider
import com.torrenttunes.app.audio.sliderToMilliBel
import com.torrenttunes.app.ui.components.PineIconButton
import com.torrenttunes.app.ui.components.PillButton
import com.torrenttunes.app.ui.components.SectionHeader
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineBackground
import com.torrenttunes.app.ui.theme.PineSurfaceHigh
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary
import com.torrenttunes.app.ui.theme.PineTouchTarget

/**
 * Sound controls. Reads only the manager's published state flows; the manager
 * owns effect handles, band detection and persistence.
 *
 * The screen is a overlay over (and visually on top of) the player, so back
 * is handled by [onClose] rather than by popping a nav destination.
 */
@Composable
fun SoundSettingsScreen(
    manager: AudioEffectsManager,
    onClose: () -> Unit,
) {
    val settings by manager.settings.collectAsState()
    val caps by manager.capabilities.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(PineBackground)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PineIconButton(onClick = onClose, targetSize = PineTouchTarget) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(com.torrenttunes.app.R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = PineTextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                "Sound",
                style = MaterialTheme.typography.titleLarge,
                color = PineTextPrimary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // ---- master switch / bypass
        CardRow {
            Column(Modifier.weight(1f)) {
                Text("Sound enhancements", style = MaterialTheme.typography.bodyLarge, color = PineTextPrimary)
                Text(
                    if (settings.enabled) {
                        caps.sessionAttached.let {
                            if (it) "Applying your settings to the playback session." else "Waiting for a playback session."
                        }
                    } else {
                        "Bypassed \u2014 audio plays as decoded."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PineTextSecondary,
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { manager.setEnabled(it) },
                modifier = Modifier.defaultMinSize(minWidth = PineTouchTarget, minHeight = PineTouchTarget),
            )
        }

        if (caps.equalizerSupported.not() || !caps.equalizerSupported) {
            Notice("An equalizer is not available on this device. Other enhancements still work where supported.")
        }
        if (caps.externalControl) {
            Notice("Another app or the system is controlling this device's equaliser, so these settings may not be taking effect.")
        }
        caps.effectFailure?.let { Notice(it) }

        // ---- equalizer
        if (caps.equalizerSupported) {
            SectionHeader("Equaliser", trailing = {
                Text(
                    "${caps.bands.size} bands",
                    style = MaterialTheme.typography.labelMedium,
                    color = PineTextSecondary,
                )
            })

            // preset shelf
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val isSelected = { id: String -> settings.presetId == id }
                ShoeChipRow(
                    items = SoundPreset.entries.map { it.id to it.displayName }
                        + (if (settings.profiles.isNotEmpty() || settings.isCustom) listOf("custom" to "Custom") else emptyList()),
                    selectedId = settings.presetId,
                    onSelect = { id ->
                        if (id == PRESET_CUSTOM) {
                            // already custom; nothing to switch. Keeps selection
                            // state honest rather than faking a shelf entry.
                        } else manager.setPreset(id)
                    },
                )
            }

            // profiles
            if (settings.profiles.isNotEmpty() || settings.isCustom) {
                Text(
                    "Profiles: ${settings.profiles.joinToString { it.name }}",
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp, end = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = PineTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier
                        .padding(start = 16.dp, bottom = 16.dp, end = 16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    settings.profiles.forEach { p ->
                        PillButton(
                            label = p.name,
                            onClick = { manager.selectProfile(p.name) },
                            background = if (settings.selectedProfileName == p.name) PineAccent else PineSurfaceHigh,
                            contentColor = if (settings.selectedProfileName == p.name) PineTextPrimary else PineTextSecondary,
                        )
                    }
                }
            }

            ProfileActions(manager, caps, settings)

            // bands
            Column(Modifier.padding(horizontal = 16.dp)) {
                caps.bands.forEach { band ->
                    val gain = if (settings.isCustom && settings.bandGainsMilliBel.size == caps.bands.size) {
                        settings.bandGainsMilliBel[band.index]
                    } else {
                        manager.resolvedGainsForUi(band.index, settings, caps)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            band.label,
                            modifier = Modifier.size(width = 64.dp, height = 24.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = PineTextSecondary,
                        )
                        androidx.compose.material3.Slider(
                            value = milliBelToSlider(gain, caps.levelRange),
                            onValueChange = { f ->
                                manager.setBandGain(band.index, sliderToMilliBel(f, caps.levelRange))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            gain.toString(),
                            modifier = Modifier.size(width = 48.dp, height = 24.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = PineTextSecondary,
                        )
                    }
                }
            }
        }

        // ---- bass boost
        if (caps.bassBoostSupported) {
            SectionHeader("Bass boost")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Extra low end",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PineTextPrimary,
                )
                Switch(
                    checked = settings.bassBoostEnabled,
                    onCheckedChange = { manager.setBassBoost(it) },
                    modifier = Modifier.defaultMinSize(minWidth = PineTouchTarget, minHeight = PineTouchTarget),
                )
            }
            if (settings.bassBoostEnabled) {
                androidx.compose.material3.Slider(
                    value = settings.bassBoostMilliBel / MAX_SLIDER_B.toDouble().coerceIn(0.0, 1.0).toFloat(),
                    onValueChange = { f ->
                        manager.setBassBoost(true, (f * MAX_SLIDER_B.toInt()).toInt())
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                )
            }
        } else {
            Notice("Bass boost is not available on this device.")
        }

        // ---- loudness
        if (caps.loudnessSupported) {
            SectionHeader("Loudness")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Gentle lift",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PineTextPrimary,
                )
                Switch(
                    checked = settings.loudnessEnabled,
                    onCheckedChange = { manager.setLoudness(it) },
                    modifier = Modifier.defaultMinSize(minWidth = PineTouchTarget, minHeight = PineTouchTarget),
                )
            }
            if (settings.loudnessEnabled) {
                androidx.compose.material3.Slider(
                    value = settings.loudnessMilliBel / MAX_SLIDER_L.toDouble().coerceIn(0.0, 1.0).toFloat(),
                    onValueChange = { f -> manager.setLoudness(true, (f * MAX_SLIDER_L.toInt()).toInt()) },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                )
            }
        } else {
            Notice("Loudness enhancement is not available on this device.")
        }

        // ---- spatial
        if (caps.spatialSupported) {
            SectionHeader("Spatial audio")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Spatial enhancement", style = MaterialTheme.typography.bodyMedium, color = PineTextPrimary)
                    Text(
                        "Uses the virtualizer path; mainly audible on headphones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PineTextSecondary,
                    )
                }
                Switch(
                    checked = settings.spatialEnabled,
                    onCheckedChange = { manager.setSpatial(it) },
                    modifier = Modifier.defaultMinSize(minWidth = PineTouchTarget, minHeight = PineTouchTarget),
                )
            }
        } else if (settings.enabled) {
            Notice("Spatial audio is not supported by this device.")
        }

        Text(
            "Pineapple cannot override your phone's volume limits or another " +
                "app's audio processing. Disabling enhancements restores the " +
                "unprocessed playback path.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            style = MaterialTheme.typography.bodySmall,
            color = PineTextSecondary,
        )
    }
}

@Composable
private fun Notice(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = PineTextSecondary)
    }
}

@Composable
private fun CardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ShoeChipRow(
    items: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, label) ->
            PillButton(
                label = label,
                onClick = { onSelect(id) },
                background = if (id == selectedId) PineAccent else PineSurface,
                contentColor = if (id == selectedId) PineTextPrimary else PineTextSecondary,
            )
        }
    }
}

@Composable
private fun ProfileActions(
    manager: AudioEffectsManager,
    caps: SoundCapabilities,
    settings: com.torrenttunes.app.audio.AudioEffectsSettings,
) {
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameTo by remember { mutableStateOf("") }
    var saveName by remember { mutableStateOf("") }
    var showSave by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(start = 16.dp, bottom = 12.dp, end = 16.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillButton(label = "Save profile", onClick = { showSave = true })
        if (settings.selectedProfileName != null) {
            PillButton(label = "Rename", onClick = {
                renameTarget = settings.selectedProfileName
                renameTo = settings.selectedProfileName ?: ""
            })
            PillButton(label = "Delete", onClick = {
                settings.selectedProfileName?.let { manager.deleteProfile(it) }
            })
        }
        PillButton(label = "Reset", onClick = { manager.resetToFlat() })
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            containerColor = PineSurface,
            title = { Text("Save profile", color = PineTextPrimary) },
            text = {
                TextField(value = saveName, onValueChange = { saveName = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    manager.saveProfile(saveName)
                    showSave = false
                    saveName = ""
                }) { Text("Save", color = PineAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("Cancel", color = PineTextSecondary) }
            },
        )
    }

    renameTarget?.let { from ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = PineSurface,
            title = { Text("Rename profile", color = PineTextPrimary) },
            text = {
                TextField(value = renameTo, onValueChange = { renameTo = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    manager.renameProfile(from, renameTo)
                    renameTarget = null
                }) { Text("Rename", color = PineAccent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = PineTextSecondary) }
            },
        )
    }
}

/** Imports for icons / brush handled lazily below. */
private const val MAX_SLIDER_B = MAX_BASS_MILLI_BEL
private const val MAX_SLIDER_L = MAX_LOUDNESS_MILLI_BEL

private fun AudioEffectsManager.resolvedGainsForUi(
    index: Int,
    settings: com.torrenttunes.app.audio.AudioEffectsSettings,
    caps: SoundCapabilities,
): Int {
    val gains = com.torrenttunes.app.audio.resolvedGains(settings, caps.bands.size, caps.levelRange)
    return gains.getOrElse(index) { 0 }
}
