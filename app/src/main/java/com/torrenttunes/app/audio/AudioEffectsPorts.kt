package com.torrenttunes.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ports. `AudioEffectsManager` only knows these interfaces; the concrete
 * Android classes below are the ONLY place that names `android.media.audiofx`.
 *
 * Splitting them out is what makes the manager and its lifecycle logic
 * unit-testable on the plain JVM.
 *
 * I/O handle
 * ----------
 * `AudioManager.generateAudioSessionId()` returns an id that AudioFlinger
 * remembers even before a matching AudioTrack exists, so effects can attach
 * against the player's session from the moment it is constructed. When a
 * given device STILL cannot open an effect on an idle session, the port
 * returns null / throws and the manager retries when playback actually
 * starts (see its playback-state handling) — it never assumes "unsupported"
 * from a single failed attempt.
 */

// --------------------------------------------------------------- handles

interface EffectHandle {
    fun setEnabled(enabled: Boolean): Boolean
    fun hasControl(): Boolean
    fun setControlStatusListener(listener: ((Boolean) -> Unit)?)
    fun release()
}

interface EqualizerHandle : EffectHandle {
    fun numberOfBands(): Int
    fun levelRange(): LevelRange
    fun centerFreqMilliHz(band: Int): Int
    fun freqRangeMilliHz(band: Int): IntArray
    fun setBandLevel(band: Int, levelMilliBel: Int)
}

interface BassBoostHandle : EffectHandle {
    fun setStrength(strengthMilliBel: Int)
    fun strengthSupported(): Boolean
}

interface LoudnessHandle : EffectHandle {
    fun setTargetGain(gainMilliBel: Int)
}

interface SpatialHandle : EffectHandle {
    fun setStrength(strengthMilliBel: Int)
    fun strengthSupported(): Boolean
}

// --------------------------------------------------------------- factory

interface AudioEffectFactory {
    fun openEqualizer(sessionId: Int): EqualizerHandle?
    fun openBassBoost(sessionId: Int): BassBoostHandle?
    fun openLoudness(sessionId: Int): LoudnessHandle?
    fun openSpatial(sessionId: Int): SpatialHandle?
}

// ------------------------------------------------------------- playback

interface PlaybackSession {
    val audioSessionId: Int
    fun addListener(listener: Player.Listener)
    fun removeListener(listener: Player.Listener)
}

@Singleton
class ExoPlayerSession @Inject constructor(private val player: ExoPlayer) : PlaybackSession {
    override val audioSessionId: Int get() = player.audioSessionId

    override fun addListener(listener: Player.Listener) { player.addListener(listener) }
    override fun removeListener(listener: Player.Listener) { player.removeListener(listener) }
}

// ---------------------------------------------------------------- store

interface AudioSettingsStore {
    fun load(): String?
    fun save(payload: String)
}

@Singleton
class SharedPrefsAudioSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) : AudioSettingsStore {
    private val prefs: android.content.SharedPreferences =
        context.getSharedPreferences("torrenttunes.audio_effects", android.content.Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(payload: String) {
        prefs.edit().putString(KEY, payload).apply()
    }

    private companion object { const val KEY = "settings" }
}

// ------------------------------------------------------ output routes

interface OutputRouteMonitor {
    fun start(onChanged: () -> Unit)
    fun stop()
}

@Singleton
class AndroidOutputRouteMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : OutputRouteMonitor {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var callback: android.media.AudioDeviceCallback? = null

    override fun start(onChanged: () -> Unit) {
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<android.media.AudioDeviceInfo>) {
                // Announce on the main thread; the probe results do not themselves
                // change per route, but this is where the app gets a chance to
                // act on a route change (e.g. pause spatial on speaker swap).
                main.post { onChanged() }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<android.media.AudioDeviceInfo>) {
                main.post { onChanged() }
            }
        }
        callback = cb
        try {
            audioManager.registerAudioDeviceCallback(cb, main)
        } catch (t: Throwable) {
            // Detection of route changes is a nicety, never a load-bearing
            // requirement; if the device rejects it, the core keeps working.
        }
    }

    override fun stop() {
        try { val c = callback; if (c != null) audioManager.unregisterAudioDeviceCallback(c) } catch (t: Throwable) { }
    }
}

// ----------------------------------------------------------- Android facts

@Singleton
class AndroidAudioEffectFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioEffectFactory {

    override fun openEqualizer(sessionId: Int): EqualizerHandle? = runCatching {
        AndroidEqualizerHandle(Equalizer(0 /* priority: lowest common one */, sessionId))
    }.getOrNull()

    override fun openBassBoost(sessionId: Int): BassBoostHandle? {
        val bb = runCatching { BassBoost(0, sessionId) }.getOrNull() ?: return null
        if (!bb.strengthSupported) { runCatching { bb.release() }; return null }
        return AndroidBassBoostHandle(bb)
    }

    override fun openLoudness(sessionId: Int): LoudnessHandle? =
        runCatching { AndroidLoudnessHandle(LoudnessEnhancer(sessionId)) }.getOrNull()

    override fun openSpatial(sessionId: Int): SpatialHandle? {
        @Suppress("DEPRECATION")
        val v = runCatching { Virtualizer(0, sessionId) }.getOrNull() ?: return null
        if (!v.strengthSupported) { runCatching { v.release() }; return null }
        return AndroidSpatialHandle(v)
    }

    // ------------------------------------------------------- handle impls

    private class AndroidEqualizerHandle(private val e: Equalizer) : EqualizerHandle {
        override fun numberOfBands(): Int = e.numberOfBands.toInt()

        override fun levelRange(): LevelRange {
            val r = e.bandLevelRange
            return LevelRange(r[0].toInt(), r[1].toInt())
        }

        override fun centerFreqMilliHz(band: Int): Int = e.getCenterFreq(band.toShort())

        override fun freqRangeMilliHz(band: Int): IntArray = e.getBandFreqRange(band.toShort())

        override fun setBandLevel(band: Int, levelMilliBel: Int) {
            e.setBandLevel(band.toShort(), levelMilliBel.toShort())
        }

        override fun setEnabled(enabled: Boolean): Boolean =
            runCatching { e.setEnabled(enabled) == AudioEffect.SUCCESS }.getOrDefault(false)

        override fun hasControl(): Boolean = runCatching { e.hasControl() }.getOrDefault(false)

        override fun setControlStatusListener(listener: ((Boolean) -> Unit)?) {
            if (listener == null) {
                e.setControlStatusListener(null)
            } else {
                // Equalizer callbacks arrive on the binder thread that owns the
                // effect, not on the app's main thread. Post inward so the
                // manager never has to know it is being called off-thread.
                val main = Handler(Looper.getMainLooper())
                e.setControlStatusListener { _, has -> main.post { listener(has) } }
            }
        }

        override fun release() { runCatching { e.release() } }
    }

    @Suppress("DEPRECATION")
    private class AndroidBassBoostHandle(private val b: BassBoost) : BassBoostHandle {
        override fun setStrength(strengthMilliBel: Int) {
            b.setStrength(strengthMilliBel.toShort().coerceIn(0, 1000))
        }

        override fun strengthSupported(): Boolean = b.strengthSupported

        override fun setEnabled(enabled: Boolean): Boolean =
            runCatching { b.setEnabled(enabled) == AudioEffect.SUCCESS }.getOrDefault(false)

        override fun hasControl(): Boolean = runCatching { b.hasControl() }.getOrDefault(false)

        override fun setControlStatusListener(listener: ((Boolean) -> Unit)?) {
            if (listener == null) b.setControlStatusListener(null)
            else {
                val main = Handler(Looper.getMainLooper())
                b.setControlStatusListener { _, has -> main.post { listener(has) } }
            }
        }

        override fun release() { runCatching { b.release() } }
    }

    private class AndroidLoudnessHandle(private val l: LoudnessEnhancer) : LoudnessHandle {
        override fun setTargetGain(gainMilliBel: Int) {
            l.setTargetGain(gainMilliBel)
        }

        override fun setEnabled(enabled: Boolean): Boolean =
            runCatching { l.setEnabled(enabled) == AudioEffect.SUCCESS }.getOrDefault(false)

        override fun hasControl(): Boolean = runCatching { l.hasControl() }.getOrDefault(false)

        override fun setControlStatusListener(listener: ((Boolean) -> Unit)?) {
            if (listener == null) l.setControlStatusListener(null)
            else {
                val main = Handler(Looper.getMainLooper())
                l.setControlStatusListener { _, has -> main.post { listener(has) } }
            }
        }

        override fun release() { runCatching { l.release() } }
    }

    @Suppress("DEPRECATION")
    private class AndroidSpatialHandle(private val v: Virtualizer) : SpatialHandle {
        override fun setStrength(strengthMilliBel: Int) {
            v.setStrength(strengthMilliBel.toShort().coerceIn(0, 1000))
        }

        override fun strengthSupported(): Boolean = v.strengthSupported

        override fun setEnabled(enabled: Boolean): Boolean =
            runCatching { v.setEnabled(enabled) == AudioEffect.SUCCESS }.getOrDefault(false)

        override fun hasControl(): Boolean = runCatching { v.hasControl() }.getOrDefault(false)

        override fun setControlStatusListener(listener: ((Boolean) -> Unit)?) {
            if (listener == null) v.setControlStatusListener(null)
            else {
                val main = Handler(Looper.getMainLooper())
                v.setControlStatusListener { _, has -> main.post { listener(has) } }
            }
        }

        override fun release() { runCatching { v.release() } }
    }
}
