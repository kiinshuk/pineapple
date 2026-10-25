package com.torrenttunes.app.audio

import android.util.Log
import androidx.media3.common.Player
import com.torrenttunes.app.util.CrashLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of everything `AudioEffects` can touch.
 *
 * Owns the audio-effect handles, reconciles them against the player's session,
 * persists the user's choices and publishes two things for the UI:
 *  - [settings]   (what the user asked for)
 *  - [capabilities] (what this device lets us actually express)
 *
 * The engine (this class) never holds Compose state and never reads from any
 * MutableStateFlow other than its own: all UI-facing values flow outwards.
 */
@Singleton
class AudioEffectsManager @Inject constructor(
    private val session: PlaybackSession,
    private val factory: AudioEffectFactory,
    private val store: AudioSettingsStore,
    private val routes: OutputRouteMonitor,
) {
    private val _settings = MutableStateFlow(AudioEffectsCodec.decode(store.load()))
    val settings: StateFlow<AudioEffectsSettings> = _settings.asStateFlow()

    private val _capabilities = MutableStateFlow(SoundCapabilities())
    val capabilities: StateFlow<SoundCapabilities> = _capabilities.asStateFlow()

    // ---- live handles (null means "nothing attached right now") ----
    private var eq: EqualizerHandle? = null
    private var bass: BassBoostHandle? = null
    private var loud: LoudnessHandle? = null
    private var spatial: SpatialHandle? = null
    private var attachedSessionId: Int = 0
    private var probeSessionId: Int = -1
    private var probeDone: Boolean = false
    private var dirty: Boolean = false
    private var effectFailure: String? = null

    // ---- lifecycle ----

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) { sync() }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Many devices can only create an effect once the session is live.
            // A previously failed probe is therefore retryable here.
            if (playbackState == Player.STATE_READY && !probeDone) sync()
            sync()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Only re-sync while playing; sync() itself is a no-op when
            // nothing is dirty and everything is already attached.
            if (isPlaying) sync()
        }
    }

    init {
        session.addListener(playerListener)
        routes.start { sync() }
        sync()
    }

    /** Pump to call from the UI on screen entry — picks up route changes and
     *  retries probes that were blocked before playback started. */
    fun refresh() { sync() }

    // ---- public actions (each persists + reconciles) ----

    fun setEnabled(enabled: Boolean) { mutate { copy(enabled = enabled) } }

    fun setPreset(id: String) { mutate { copy(presetId = id, selectedProfileName = null) } }

    fun setBandGain(index: Int, milliBel: Int) {
        val caps = _capabilities.value
        if (!caps.equalizerSupported) return
        val bandCount = caps.bands.size
        mutate {
            withBandGain(index, milliBel, bandCount, caps.levelRange)
        }
    }

    fun setBassBoost(enabled: Boolean, strengthMilliBel: Int? = null) {
        mutate {
            copy(
                bassBoostEnabled = enabled,
                bassBoostMilliBel = (strengthMilliBel ?: bassBoostMilliBel)
                    .coerceIn(0, MAX_BASS_MILLI_BEL),
            )
        }
    }

    fun setLoudness(enabled: Boolean, gainMilliBel: Int? = null) {
        mutate {
            copy(
                loudnessEnabled = enabled,
                loudnessMilliBel = (gainMilliBel ?: loudnessMilliBel)
                    .coerceIn(0, MAX_LOUDNESS_MILLI_BEL),
            )
        }
    }

    fun setSpatial(enabled: Boolean) { mutate { copy(spatialEnabled = enabled) } }

    fun saveProfile(name: String) {
        val caps = _capabilities.value
        if (!caps.equalizerSupported) return
        mutate { saveProfile(name, caps.bands.size, caps.levelRange) }
    }

    fun renameProfile(from: String, to: String) { mutate { renameProfile(from, to) } }
    fun deleteProfile(name: String) { mutate { deleteProfile(name) } }
    fun selectProfile(name: String) { mutate { selectProfile(name) } }
    fun resetToFlat() { mutate { resetToFlat() } }

    // ---- core reconciliation ----

    /**
     * The single place that decides what is attached. Idempotent: calling it
     * repeatedly when nothing changed does nothing and allocates nothing.
     */
    private fun sync() {
        val id = session.audioSessionId
        val s = _settings.value
        val enabled = s.enabled

        if (id < 0) {
            teardown()
            _capabilities.update { it.copy(sessionAttached = false) }
            return
        }

        // Probe first. Probing never leaves handles behind; it only records
        // what the device claims to support. Retried on each session change
        // and, when it previously came back empty, once playback is live.
        if (id > 0 && (!probeDone || probeSessionId != id)) {
            probe(id, id != probeSessionId)
        }

        if (!enabled) {
            // Bypass is the *removal* of effects, not their disabling: the
            // unprocessed path is the path with no effects attached at all.
            teardown()
            _capabilities.update { it.copy(sessionAttached = false, effectFailure = null) }
            return
        }

        if (id <= 0) {
            _capabilities.update { it.copy(sessionAttached = false) }
            return
        }

        // Attach against this session if we are not already attached to it.
        if (id != attachedSessionId || eq == null && _capabilities.value.equalizerSupported) {
            if (id != attachedSessionId) {
                teardown()
                attachedSessionId = id
            }
            openHandles(id)
            dirty = true
        }

        if (dirty && (eq != null || bass != null || loud != null || spatial != null)) {
            applySettings()
        } else if (dirty) {
            // Memory handle exists but openHandles failed everywhere: record one
            // failure flag so the UI can be honest.
            if (!probeSaysAnything()) {
                effectFailure = "No audio effect accepted this session."
            }
            _capabilities.update { it.copy(effectFailure = effectFailure) }
        }

        _capabilities.update {
            it.copy(
                sessionAttached = attachedSessionId == id && (eq != null || bass != null || loud != null || spatial != null),
                effectFailure = this@AudioEffectsManager.effectFailure,
            )
        }
    }

    private fun probe(id: Int, freshSession: Boolean) {
        // Equality handled by runs; probe both session count and singleton fact.
        if (freshSession || _capabilities.value.equalizerSupported.not() || probeSessionId != id) {
            // fine — run it
        }
        try {
            factory.openEqualizer(id)?.let { e ->
                val bands = (0 until e.numberOfBands()).map { i ->
                    EqualizerBand(
                        index = i,
                        centerFreqMilliHz = e.centerFreqMilliHz(i),
                        lowMilliHz = e.freqRangeMilliHz(i).getOrNull(0) ?: 0,
                        highMilliHz = e.freqRangeMilliHz(i).getOrNull(1) ?: 0,
                    )
                }
                val range = e.levelRange()
                _capabilities.update {
                    it.copy(
                        equalizerSupported = true,
                        bands = bands,
                        levelRange = range,
                    )
                }
                // Control status: mark external control if we cannot hold it.
                e.setControlStatusListener { has -> if (!has) onExternalControlLost() }
                // We keep this handle rather than free it, iff the user has
                // enhancements enabled. Probe-only detection is only the idle
                // (disabled) path, so release anything acquired when disabled.
                if (!_settings.value.enabled) e.release()
            } ?: run { _capabilities.update { it.copy(equalizerSupported = false) } }

            // Bass boost
            factory.openBassBoost(id)?.let { b ->
                _capabilities.update { it.copy(bassBoostSupported = true) }
                b.setControlStatusListener { has -> if (!has) onExternalControlLost() }
                if (!_settings.value.enabled) b.release()
            } ?: _capabilities.update { it.copy(bassBoostSupported = false) }

            // Loudness
            factory.openLoudness(id)?.let { l ->
                _capabilities.update { it.copy(loudnessSupported = true) }
                l.setControlStatusListener { has -> if (!has) onExternalControlLost() }
                if (!_settings.value.enabled) l.release()
            } ?: _capabilities.update { it.copy(loudnessSupported = false) }

            // Spatial
            factory.openSpatial(id)?.let { v ->
                _capabilities.update { it.copy(spatialSupported = true) }
                v.setControlStatusListener { has -> if (!has) onExternalControlLost() }
                if (!_settings.value.enabled) v.release()
            } ?: _capabilities.update { it.copy(spatialSupported = false) }
        } catch (t: Throwable) {
            android.util.Log.e("AudioEffectsManager", "audio failure", t)
            effectFailure = "Probe failed: ${t.javaClass.simpleName}"
        }

        probeSessionId = id
        probeDone = true
    }

    private fun applySettings() {
        val s = _settings.value
        val caps = _capabilities.value
        effectFailure = null
        try {
            if (caps.equalizerSupported) {
                eq?.let { e ->
                    if (e.hasControl()) {
                        val gains = resolvedGains(s, caps.bands.size, caps.levelRange)
                        gains.forEachIndexed { i, g -> e.setBandLevel(i, g) }
                        e.setEnabled(true)
                    } else externalControlEconomy()
                }
            }
            bass?.let { b ->
                if (b.hasControl()) {
                    if (s.bassBoostEnabled && caps.bassBoostSupported) {
                        b.setStrength(
                            s.bassBoostMilliBel.coerceIn(0, MAX_BASS_MILLI_BEL)
                                .coerceAtMost(MAX_STRENGTH_MILLI_BEL),
                        )
                        b.setEnabled(true)
                    } else {
                        b.setEnabled(false)
                    }
                } else externalControlEconomy()
            }
            loud?.let { l ->
                if (l.hasControl()) {
                    if (s.loudnessEnabled && caps.loudnessSupported) {
                        l.setTargetGain(
                            s.loudnessMilliBel.coerceIn(0, MAX_LOUDNESS_MILLI_BEL),
                        )
                        l.setEnabled(true)
                    } else {
                        l.setEnabled(false)
                    }
                } else externalControlEconomy()
            }
            spatial?.let { sp ->
                if (sp.hasControl()) {
                    if (s.spatialEnabled && caps.spatialSupported) {
                        sp.setStrength(MAX_STRENGTH_MILLI_BEL)
                        sp.setEnabled(true)
                    } else {
                        sp.setEnabled(false)
                    }
                } else externalControlEconomy()
            }
            dirty = false
            _capabilities.update { it.copy(externalControl = false, effectFailure = null) }
        } catch (t: Throwable) {
            android.util.Log.e("AudioEffectsManager", "audio failure", t)
            effectFailure = "Apply failed: ${t.javaClass.simpleName}"
            teardown()
            dirty = false
            attachedSessionId = 0
            _capabilities.update { it.copy(effectFailure = effectFailure) }
        }
    }

    private fun externalControlEconomy() {
        // Called when a handle still exists but has stopped being owners.
        // Mark it and back out rather than fighting the other controller.
        onExternalControlLost()
    }

    private fun onExternalControlLost() {
        _capabilities.update { it.copy(externalControl = true) }
        teardown()
        dirty = false
        attachedSessionId = 0
        _capabilities.update {
            it.copy(
                sessionAttached = false,
                externalControl = true,
                effectFailure = effectFailure,
            )
        }
    }

    private fun openHandles(id: Int) {
        val caps = _capabilities.value
        if (caps.equalizerSupported) {
            try {
                val e = factory.openEqualizer(id)
                eq = e
                e?.setControlStatusListener { has -> if (!has) onExternalControlLost() }
            } catch (t: Throwable) { log(t) }
        }
        if (caps.bassBoostSupported) {
            try {
                val b = factory.openBassBoost(id)
                bass = b
                b?.setControlStatusListener { has -> if (!has) onExternalControlLost() }
            } catch (t: Throwable) { log(t) }
        }
        if (caps.loudnessSupported) {
            try {
                val l = factory.openLoudness(id)
                loud = l
                l?.setControlStatusListener { has -> if (!has) onExternalControlLost() }
            } catch (t: Throwable) { log(t) }
        }
        if (caps.spatialSupported) {
            try {
                val v = factory.openSpatial(id)
                spatial = v
                v?.setControlStatusListener { has -> if (!has) onExternalControlLost() }
            } catch (t: Throwable) { log(t) }
        }
        dirty = true
    }

    private fun teardown() {
        try { eq?.release() } catch (t: Throwable) { log(t) }
        try { bass?.release() } catch (t: Throwable) { log(t) }
        try { loud?.release() } catch (t: Throwable) { log(t) }
        try { spatial?.release() } catch (t: Throwable) { log(t) }
        eq = null
        bass = null
        loud = null
        spatial = null
        attachedSessionId = 0
    }

    private fun probeSaysAnything(): Boolean =
        _capabilities.value.run { equalizerSupported || bassBoostSupported || loudnessSupported || spatialSupported }

    // ---- mutations ----

    private fun mutate(transform: AudioEffectsSettings.() -> AudioEffectsSettings) {
        val next = _settings.value.transform()
        if (next == _settings.value) return
        _capabilities.update { it.copy(externalControl = false) }
        _settings.value = next
        // persistence is best-effort and must never block the UI: a failed
        // write leaves the in-memory state correct, the next edit retries.
        try { store.save(AudioEffectsCodec.encode(next)) } catch (t: Throwable) { log(t) }
        sync()
    }

    private fun log(t: Throwable) {
        try { android.util.Log.e("AudioEffectsManager", "audio failure", t) } catch (_: Throwable) {
            Log.e("AudioEffectsManager", "audio step failed", t)
        }
    }

    // ---- Extensions ----
    private fun Int.coerceAtMost(max: Int) = if (this > max) max else this
}

// ---------------------------------------------------------------- state

data class SoundCapabilities(
    val equalizerSupported: Boolean = false,
    val bands: List<EqualizerBand> = emptyList(),
    val levelRange: LevelRange = LevelRange.ZERO,
    val bassBoostSupported: Boolean = false,
    val loudnessSupported: Boolean = false,
    val spatialSupported: Boolean = false,
    val sessionAttached: Boolean = false,
    val externalControl: Boolean = false,
    val effectFailure: String? = null,
) {
    companion object { val DEFAULT = SoundCapabilities() }
}

private fun IntArray.getOrElse(i: Int, default: () -> Int): Int =
    if (i in indices) get(i) else default()
