package com.torrenttunes.app.di

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.torrenttunes.app.audio.AndroidAudioEffectFactory
import com.torrenttunes.app.audio.AudioEffectFactory
import com.torrenttunes.app.audio.AudioSettingsStore
import com.torrenttunes.app.audio.ExoPlayerSession
import com.torrenttunes.app.audio.OutputRouteMonitor
import com.torrenttunes.app.audio.PlaybackSession
import com.torrenttunes.app.audio.SharedPrefsAudioSettingsStore
import com.torrenttunes.app.audio.AndroidOutputRouteMonitor
import dagger.Module
import kotlinx.coroutines.SupervisorJob
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                // Without this, an uncaught exception in a background scope
                // reaches the default uncaught handler and kills the process.
                kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                    android.util.Log.e("AppScope", "uncaught in scope", t)
                },
        )

    // ExoPlayer instance used by PlaybackService. Media3 owns its lifecycle via
    // the service; we build it here for testability.
    @Provides
    @Singleton
    fun providePlayer(@ApplicationContext context: Context): ExoPlayer {

        // googlevideo must see the identity that the stream URL was issued
        // under, so this mirrors the leading player client rather than
        // impersonating an app. A VISIONOS URL was verified to serve the whole
        // file under this UA.
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(com.torrenttunes.app.youtube.InnerTube.VISIONOS_UA)

        // Start fast, then buffer deep.
        //  - bufferForPlayback 500ms : audio begins almost as soon as the
        //    stream opens. Stream URLs are already prefetched by this point,
        //    so this is now the dominant part of the wait — worth trimming.
        //  - min 30s / max 60s       : once running, a hiccup on mobile data
        //    never reaches the listener's ears.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 30_000,
                /* maxBufferMs */ 60_000,
                /* bufferForPlaybackMs */ 500,
                /* bufferForPlaybackAfterRebufferMs */ 1_500,
            )
            .setBackBuffer(/* backBufferDurationMs */ 30_000, /* retainBackBufferFromKeyframe */ true)
            .build()

        // One normal request is enough — googlevideo answers a plain GET (and
        // `bytes=0-`) with the **entire** file. What it serves depends only on
        // which client issued the URL.
        //
        // Measured 2026-10-20 from one IP, in the same minute, same video
        // (Ky-QenzQD6U, 4.7 MB audio):
        //
        //   range                ANDROID 20.10.38   VISIONOS 1.02
        //   no Range (whole)     403                200  read all 4738365
        //   0-65535              206                206
        //   1048576-2097151      403                206
        //   3000000-4194303      403                206
        //
        // So there is no byte cap and no UA rule to work around — an earlier
        // reading blaming a bot-flagged IP, and a "1 MiB preview" theory, were
        // both wrong. The gate is the client, and the URL is bound to this
        // device's IP anyway (the `ip=` field), which the phone satisfies.
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
    }

    // Dagger matches types exactly, so expose the same instance under the
    // `Player` interface for consumers.
    @Provides
    @Singleton
    fun providePlayerInterface(player: ExoPlayer): Player = player

    // Audio enhancement engine (optional; degrades gracefully when unavailable).
    @Provides
    @Singleton
    fun providePlaybackSession(player: ExoPlayer): PlaybackSession = ExoPlayerSession(player)

    @Provides
    @Singleton
    fun provideAudioEffectFactory(@ApplicationContext context: Context): AudioEffectFactory =
        AndroidAudioEffectFactory(context)

    @Provides
    @Singleton
    fun provideAudioSettingsStore(@ApplicationContext context: Context): AudioSettingsStore =
        SharedPrefsAudioSettingsStore(context)

    @Provides
    @Singleton
    fun provideOutputRouteMonitor(@ApplicationContext context: Context): OutputRouteMonitor =
        AndroidOutputRouteMonitor(context)
}
