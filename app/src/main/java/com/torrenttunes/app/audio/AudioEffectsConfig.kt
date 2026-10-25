package com.torrenttunes.app.audio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure data and rules for Pineapple's audio enhancement engine.
 *
 * Nothing in this file touches Android's audio APIs: bands, level ranges and
 * presets are *detected values* or *maths*, so every rule here runs on the
 * plain JVM in unit tests. The Android side lives behind the ports in
 * `AudioEffectsPorts.kt`, and coordination lives in `AudioEffectsManager.kt`.
 */

// ------------------------------------------------------------------ bands

/**
 * One equalizer band exactly as the device reported it.
 *
 * Frequencies are milli-Hz because that is the unit Android's Equalizer API
 * speaks (`getCenterFreq`, `getBandFreqRange`); the Hz convenience accessors
 * are for display only.
 */
data class EqualizerBand(
    val index: Int,
    val centerFreqMilliHz: Int,
    val lowMilliHz: Int,
    val highMilliHz: Int,
) {
    val centerFreqHz: Int get() = centerFreqMilliHz / 1000

    /** "60 Hz" under a kilohertz, "4.7 kHz" above — how a listener reads it. */
    val label: String
        get() = when {
            centerFreqHz >= 1000 && centerFreqHz % 1000 == 0 -> "${centerFreqHz / 1000} kHz"
            centerFreqHz >= 1000 -> String.format(Locale.US, "%.1f kHz", centerFreqHz / 1000f)
            else -> "$centerFreqHz Hz"
        }
}

/** The device's accepted band-level window, in milli-Bel. */
data class LevelRange(val lowMilliBel: Int, val highMilliBel: Int) {
    fun coerce(milliBel: Int): Int = milliBel.coerceIn(lowMilliBel, highMilliBel)

    companion object {
        /** Pre-probe placeholder. Never used to shape real gains. */
        val ZERO = LevelRange(0, 0)
    }
}

// ---------------------------------------------------------------- presets

/**
 * The preset shelf. `id` is what persists; `displayName` is what a person
 * reads. "custom" is deliberately *not* an enum value — it means "a stored
 * curve that no longer matches a shelf entry", i.e. the user's own.
 */
const val PRESET_CUSTOM: String = "custom"

enum class SoundPreset(val id: String, val displayName: String) {
    FLAT("flat", "Flat"),
    BASS_BOOST("bass", "Bass Boost"),
    VOCAL("vocal", "Vocal"),
    ROCK("rock", "Rock"),
    POP("pop", "Pop"),
    CLASSICAL("classical", "Classical");

    companion object {
        /** Unknown or legacy ids resolve to Flat rather than to silence or a crash. */
        fun fromId(id: String): SoundPreset =
            entries.firstOrNull { it.id == id } ?: FLAT
    }
}

/**
 * A preset as a *shape* on 0..1 (0 = lowest band, 1 = highest), not as
 * decibels. The shape is resampled onto however many bands the device
 * actually has, so a 5-band phone and a 15-band phone share one preset name
 * without either being handed a curve it was not built for.
 */
private fun SoundPreset.shape(bandCount: Int): FloatArray {
    if (bandCount <= 1) return floatArrayOf(0f)
    fun t(i: Int) = i / (bandCount - 1).toFloat()
    return FloatArray(bandCount) { i ->
        val x = t(i)
        when (this) {
            // The reference point. Everything else is measured against it.
            SoundPreset.FLAT -> 0f

            // Low shelf that fades out before the mids, so the bass lift does
            // not drag the midrange up with it.
            SoundPreset.BASS_BOOST -> 0.85f * (1f - x).pow(2.2f)

            // A mid bell for presence, with the very bottom eased down a
            // little so vocal clarity does not arrive as mud.
            SoundPreset.VOCAL -> 0.75f * gauss(x, 0.52f, 0.22f) - 0.3f * gauss(x, 0.06f, 0.16f)

            // Classic gentle smile: warmth below, a small mid dip, sparkle on
            // top. Deliberately shallow — a deep V is the sound people
            // describe as "harsh" after ten minutes.
            SoundPreset.ROCK -> 0.6f * (1f - x).pow(1.7f) + 0.55f * x.pow(1.9f) - 0.18f * gauss(x, 0.45f, 0.2f)

            // Lighter than Rock: a touch of low end, a touch of air, almost
            // no mid movement. Pop is already mixed loud; it needs less.
            SoundPreset.POP -> 0.45f * (1f - x).pow(2f) + 0.4f * x.pow(2f) - 0.08f * gauss(x, 0.5f, 0.25f)

            // The most conservative shelf of all: a small low warmth and an
            // open top, no mid surgery. Classical benefits from restraint.
            SoundPreset.CLASSICAL -> 0.35f * gauss(x, 0.18f, 0.28f) + 0.45f * x.pow(1.6f)
        }
    }
}

private fun gauss(x: Float, mu: Float, sigma: Float): Float {
    val d = (x - mu) / sigma
    return exp(-0.5f * d * d)
}

/**
 * Hard ceilings on preset boosts and cuts, in milli-Bel, applied *on top of*
 * whatever range the device reports.
 *
 * The device may accept ±1500 mB (±15 dB); no preset here needs more than
 * 500 mB of lift to be clearly audible, and an EQ stacked with bass boost at
 * full range is exactly how output starts clipping. The cap is what keeps
 * "Rock on a loud master" from becoming distortion.
 */
const val PRESET_MAX_BOOST_MILLI_BEL = 500
const val PRESET_MAX_CUT_MILLI_BEL = 400

/**
 * Resample [preset] onto [bandCount] bands inside [range], returning one
 * milli-Bel value per band. Pure: same inputs, same curve, every time.
 */
fun presetGainsMilliBel(preset: SoundPreset, bandCount: Int, range: LevelRange): IntArray {
    require(bandCount > 0) { "bandCount must be positive" }
    val shape = preset.shape(bandCount)
    val boostCap = minOf(range.highMilliBel, PRESET_MAX_BOOST_MILLI_BEL)
    val cutCap = minOf(-range.lowMilliBel, PRESET_MAX_CUT_MILLI_BEL)
    return IntArray(bandCount) { i ->
        val v = shape[i]
        val milliBel = if (v >= 0f) (v * boostCap).roundToInt() else (v * cutCap).roundToInt()
        range.coerce(milliBel)
    }
}

// ---------------------------------------------------------- range mapping

/**
 * Map a 0..1 slider position onto the device's level range, inclusive of
 * both endpoints and clamped so a floating-point edge cannot produce a gain
 * the device rejects.
 */
fun sliderToMilliBel(fraction: Float, range: LevelRange): Int {
    val f = fraction.coerceIn(0f, 1f)
    val span = range.highMilliBel - range.lowMilliBel
    return range.coerce((range.lowMilliBel + f * span).roundToInt())
}

/** The inverse of [sliderToMilliBel], for rendering an existing gain. */
fun milliBelToSlider(gainMilliBel: Int, range: LevelRange): Float {
    val span = range.highMilliBel - range.lowMilliBel
    if (span == 0) return 0f
    return ((gainMilliBel - range.lowMilliBel) / span.toFloat()).coerceIn(0f, 1f)
}

// --------------------------------------------------------------- settings

/** One saved user curve. [gainsMilliBel] is one value per band at save time. */
data class CustomProfile(val name: String, val gainsMilliBel: List<Int>)

/** Conservative defaults for the enhancements that have no device range. */
const val DEFAULT_BASS_MILLI_BEL = 300
const val MAX_BASS_MILLI_BEL = 700
const val DEFAULT_LOUDNESS_MILLI_BEL = 200
const val MAX_LOUDNESS_MILLI_BEL = 400
/** Virtualizer/BassBoost strength ceiling shared with the slider. */
const val MAX_STRENGTH_MILLI_BEL = 1000

/**
 * Everything the user has chosen, persistable as-is.
 *
 * [bandGainsMilliBel] is the *custom* curve storage: it is only authoritative
 * when [presetId] is [PRESET_CUSTOM]; a shelf preset derives its gains from
 * the detected band count instead, which is what makes presets survive a
 * device with a different band count than the one they were dialled on.
 */
data class AudioEffectsSettings(
    val enabled: Boolean = false,
    val presetId: String = SoundPreset.FLAT.id,
    val bandGainsMilliBel: List<Int> = emptyList(),
    val bassBoostEnabled: Boolean = false,
    val bassBoostMilliBel: Int = DEFAULT_BASS_MILLI_BEL,
    val loudnessEnabled: Boolean = false,
    val loudnessMilliBel: Int = DEFAULT_LOUDNESS_MILLI_BEL,
    val spatialEnabled: Boolean = false,
    val profiles: List<CustomProfile> = emptyList(),
    val selectedProfileName: String? = null,
) {
    val isCustom: Boolean get() = presetId == PRESET_CUSTOM
}

/**
 * The curve that is actually in effect for a device with [bandCount] bands
 * inside [range].
 *
 * - Flat is always all-zero, by definition.
 * - A shelf preset is resampled from its shape.
 * - A custom curve is used only when it still fits the device. If the band
 *   count changed (a restore crossing devices, say) the honest answer is
 *   Flat — applying a 5-band curve to 10 bands by truncation would silently
 *   reshape the user's sound.
 */
fun resolvedGains(settings: AudioEffectsSettings, bandCount: Int, range: LevelRange): IntArray {
    if (bandCount <= 0) return IntArray(0)
    if (settings.presetId == PRESET_CUSTOM) {
        val stored = settings.bandGainsMilliBel
        return if (stored.size == bandCount) {
            IntArray(bandCount) { i -> range.coerce(stored[i]) }
        } else {
            IntArray(bandCount)
        }
    }
    return presetGainsMilliBel(SoundPreset.fromId(settings.presetId), bandCount, range)
}

// ------------------------------------------------------- settings actions

/**
 * Each action below is a pure `settings -> settings` transition. The manager
 * applies the result to the audio session and persists it; the tests drive
 * these directly without any Android in the way.
 */

fun AudioEffectsSettings.withBandGain(
    index: Int,
    milliBel: Int,
    bandCount: Int,
    range: LevelRange,
): AudioEffectsSettings {
    if (index < 0 || index >= bandCount) return this
    // Dragging one band under a shelf preset means the user has left the
    // shelf: adopt the shelf's current curve as the starting point for their
    // own, rather than snapping the whole EQ to a bare custom list.
    val base = if (isCustom) bandGainsMilliBel else resolvedGains(this, bandCount, range).toList()
    val next = base.toMutableList()
    while (next.size < bandCount) next.add(0)
    next[index] = range.coerce(milliBel)
    return copy(
        presetId = PRESET_CUSTOM,
        bandGainsMilliBel = next.take(bandCount),
        selectedProfileName = null,
    )
}

/** Snapshot the *currently audible* curve under [name], replacing a same-named profile. */
fun AudioEffectsSettings.saveProfile(
    name: String,
    bandCount: Int,
    range: LevelRange,
): AudioEffectsSettings {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || bandCount <= 0) return this
    val gains = resolvedGains(this, bandCount, range).toList()
    val profile = CustomProfile(trimmed, gains)
    val without = profiles.filterNot { it.name.equals(trimmed, ignoreCase = true) }
    return copy(
        profiles = without + profile,
        selectedProfileName = trimmed,
        presetId = PRESET_CUSTOM,
        bandGainsMilliBel = gains,
    )
}

fun AudioEffectsSettings.selectProfile(name: String): AudioEffectsSettings {
    val profile = profiles.firstOrNull { it.name == name } ?: return this
    return copy(
        presetId = PRESET_CUSTOM,
        bandGainsMilliBel = profile.gainsMilliBel,
        selectedProfileName = profile.name,
    )
}

fun AudioEffectsSettings.renameProfile(from: String, to: String): AudioEffectsSettings {
    val trimmed = to.trim()
    if (trimmed.isEmpty() || from == trimmed) return this
    // Renaming onto an existing profile replaces it; two profiles cannot
    // share a name, or "select by name" stops meaning anything.
    val renamed = profiles.map {
        if (it.name == from) it.copy(name = trimmed) else it
    }.filter { p -> p.name != trimmed || p.name == from }
    return copy(
        profiles = renamed.distinctBy { it.name },
        selectedProfileName = if (selectedProfileName == from) trimmed else selectedProfileName,
    )
}

fun AudioEffectsSettings.deleteProfile(name: String): AudioEffectsSettings = copy(
    profiles = profiles.filterNot { it.name == name },
    // The curve stays where it is — deleting the *profile* must not yank the
    // sound back to Flat underneath the listener.
    selectedProfileName = if (selectedProfileName == name) null else selectedProfileName,
)

/** Back to the reference point: Flat, no custom curve, no selected profile. */
fun AudioEffectsSettings.resetToFlat(): AudioEffectsSettings = copy(
    presetId = SoundPreset.FLAT.id,
    bandGainsMilliBel = emptyList(),
    selectedProfileName = null,
)

// ------------------------------------------------------------------ codec

/**
 * JSON persistence for [AudioEffectsSettings], mirroring `LibraryStore`'s
 * org.json approach. Decode is total: anything missing, malformed or of the
 * wrong type falls back to defaults rather than to a crash or to silence —
 * a corrupt prefs file must not be able to disable playback features.
 */
object AudioEffectsCodec {

    fun encode(settings: AudioEffectsSettings): String {
        val root = JSONObject()
        root.put("enabled", settings.enabled)
        root.put("preset", settings.presetId)
        root.put("gains", JSONArray(settings.bandGainsMilliBel))
        root.put("bassOn", settings.bassBoostEnabled)
        root.put("bass", settings.bassBoostMilliBel)
        root.put("loudOn", settings.loudnessEnabled)
        root.put("loud", settings.loudnessMilliBel)
        root.put("spatial", settings.spatialEnabled)
        val arr = JSONArray()
        settings.profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("gains", JSONArray(p.gainsMilliBel)),
            )
        }
        root.put("profiles", arr)
        root.put("selected", settings.selectedProfileName ?: "")
        return root.toString()
    }

    fun decode(payload: String?): AudioEffectsSettings {
        if (payload.isNullOrBlank()) return AudioEffectsSettings()
        return try {
            val root = JSONObject(payload)
            val presetRaw = root.optString("preset", SoundPreset.FLAT.id)
            val preset = if (presetRaw == PRESET_CUSTOM) PRESET_CUSTOM else SoundPreset.fromId(presetRaw).id
            AudioEffectsSettings(
                enabled = root.optBoolean("enabled", false),
                presetId = preset,
                bandGainsMilliBel = root.optJSONArray("gains").toIntList(),
                bassBoostEnabled = root.optBoolean("bassOn", false),
                bassBoostMilliBel = root.optInt("bass", DEFAULT_BASS_MILLI_BEL)
                    .coerceIn(0, MAX_BASS_MILLI_BEL),
                loudnessEnabled = root.optBoolean("loudOn", false),
                loudnessMilliBel = root.optInt("loud", DEFAULT_LOUDNESS_MILLI_BEL)
                    .coerceIn(0, MAX_LOUDNESS_MILLI_BEL),
                spatialEnabled = root.optBoolean("spatial", false),
                profiles = root.optJSONArray("profiles").toProfiles(),
                selectedProfileName = root.optString("selected", "").ifBlank { null },
            )
        } catch (t: Throwable) {
            AudioEffectsSettings()
        }
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).map { i -> optInt(i, 0) }
    }

    private fun JSONArray?.toProfiles(): List<CustomProfile> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "")
            if (name.isBlank()) return@mapNotNull null
            CustomProfile(name, o.optJSONArray("gains").toIntList())
        }
    }
}
