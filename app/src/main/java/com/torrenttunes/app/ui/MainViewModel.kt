package com.torrenttunes.app.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.torrenttunes.app.library.LibraryStore
import com.torrenttunes.app.audio.AudioEffectsManager
import com.torrenttunes.app.player.PlayerController
import com.torrenttunes.app.search.Connectivity
import com.torrenttunes.app.search.ConnectionNotice
import com.torrenttunes.app.search.DiscoveryContent
import com.torrenttunes.app.search.OfflineException
import com.torrenttunes.app.search.onlineRequest
import com.torrenttunes.app.search.ArtistDetail
import com.torrenttunes.app.search.AlbumDetail
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.util.CrashLogger
import com.torrenttunes.app.youtube.InnerTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class UiState(
    val query: String = "",
    val searching: Boolean = false,
    val searchOffline: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    /** videoId whose stream URL is being resolved right now. */
    val loadingVideoId: String? = null,
    /**
     * Track the user just tapped, shown in the player bar immediately while its
     * stream is still being fetched — so the tap *feels* answered before audio
     * can possibly start.
     */
    val pending: SearchResult? = null,
    val error: String? = null,
    /** Short human status, e.g. "19 results · 0.4s". */
    val status: String? = null,
    val queueSize: Int = 0,
)

/**
 * The artist catalogue page being read, if any.
 *
 * [seed] is the row the user tapped: it already carries the name, the
 * audience line and the avatar, so the header shows something true while
 * `/browse` is still in flight. [detail] then supplies the page's own banner
 * and Top songs.
 */
data class ArtistUiState(
    val seed: SearchResult,
    val detail: ArtistDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** An open album page: the tapped row seeds it, `/browse` refines it. */
data class AlbumUiState(
    val seed: SearchResult,
    val detail: AlbumDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val androidContext: android.content.Context,
    val playerController: PlayerController,
val audioEffects: AudioEffectsManager,
    private val scope: CoroutineScope,
    private val library: LibraryStore,
    private val connectivity: Connectivity,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionNotice(connectivity.isOnline()))
    internal val connection: StateFlow<ConnectionNotice> = _connection.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _nowPlaying = MutableStateFlow("")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    private val _artist = MutableStateFlow("")
    val artist: StateFlow<String> = _artist.asStateFlow()

    /** Real album art from YouTube; empty when nothing is loaded. */
    private val _artworkUrl = MutableStateFlow("")
    val artworkUrl: StateFlow<String> = _artworkUrl.asStateFlow()

    /**
     * The open artist page, or null when the reader is on a tab.
     *
     * It lives outside [UiState] on purpose: the nav bar and the back gesture
     * both close it, and neither is a child of the screen that opened it.
     */
    private val _artistPage = MutableStateFlow<ArtistUiState?>(null)
    val artistPage: StateFlow<ArtistUiState?> = _artistPage.asStateFlow()

    /** The `/browse` call for the open page — cancelled when the page closes. */
    private var artistJob: Job? = null

    /**
     * The open album page, or null when none is showing. Null is the signal
     * MainActivity uses to render the tab content instead.
     */
    private val _albumPage = MutableStateFlow<AlbumUiState?>(null)
    val albumPage: StateFlow<AlbumUiState?> = _albumPage.asStateFlow()

    /** The `/browse` call for the open album — cancelled when it closes. */
    private var albumJob: Job? = null

    /**
     * The track the player is actually on, as a full object.
     *
     * [nowPlaying] only carries a display string, which is not enough to like
     * something or file it — the library keys everything off the video id. Set
     * the moment a track is chosen, so it is correct before audio starts.
     */
    private val _currentTrack = MutableStateFlow<SearchResult?>(null)
    val currentTrack: StateFlow<SearchResult?> = _currentTrack.asStateFlow()

    /** Playhead position in ms — drives the mini-player waveform. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** Track duration in ms, or 0 while it is still unknown. */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /**
     * Playback flags, sampled from ExoPlayer rather than tracked locally.
     *
     * The player is the single source of truth: a shuffle button that reports
     * its own idea of the state would drift from what the queue is actually
     * doing the moment a track ended.
     */
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatAll = MutableStateFlow(false)
    val repeatAll: StateFlow<Boolean> = _repeatAll.asStateFlow()

    /** Index of the playing item within the queue — the Up Next anchor. */
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** Stack trace from a previous process, surfaced so it can be read off-screen. */
    private val _lastCrash = MutableStateFlow(CrashLogger.readPersisted(androidContext))
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    // ------------------------------------------------------------- discovery

    /**
     * YouTube Music's trending chart, fetched at most once per process.
     *
     * Lazily rather than in [init]: most sessions open on Search, and paying a
     * browse request for a tab nobody looked at is a wasted round trip.
     */
    private val _trending = MutableStateFlow<List<SearchResult>>(emptyList())
    val trending: StateFlow<List<SearchResult>> = _trending.asStateFlow()


    private val _newReleases = MutableStateFlow<List<SearchResult>>(emptyList())
    val newReleases: StateFlow<List<SearchResult>> = _newReleases.asStateFlow()

    private val _newVideos = MutableStateFlow<List<SearchResult>>(emptyList())
    val newVideos: StateFlow<List<SearchResult>> = _newVideos.asStateFlow()

    private val _trendingLoading = MutableStateFlow(false)
    val trendingLoading: StateFlow<Boolean> = _trendingLoading.asStateFlow()

    private val _trendingError = MutableStateFlow<String?>(null)
    val trendingError: StateFlow<String?> = _trendingError.asStateFlow()

    private val _trendingOffline = MutableStateFlow(false)
    val trendingOffline: StateFlow<Boolean> = _trendingOffline.asStateFlow()

    private var trendingJob: Job? = null
    private var trendingLoaded = false

    /** Fetches the chart on first visit to the Discovery tab. */
    fun ensureTrending() {
        if (trendingLoaded || trendingJob?.isActive == true) return
        trendingLoaded = true
        trendingJob = viewModelScope.launch(Dispatchers.IO) {
            _trendingLoading.value = true
            _trendingError.value = null
            _trendingOffline.value = false
            _connection.update { it.copy(restored = false) }
            try {
                val data = onlineRequest(connectivity::isOnline) { InnerTube.discovery() }
                _trending.value = data?.trending ?: emptyList()
                _newReleases.value = data?.newReleases ?: emptyList()
                _newVideos.value = data?.newVideos ?: emptyList()
                Log.i("Discovery", "discovery: ${data?.trending?.size ?: 0} trending / ${data?.newReleases?.size ?: 0} releases / ${data?.newVideos?.size ?: 0} videos")
            } catch (t: Throwable) {
                // Cancellation is control flow, not a failure. Rethrowing it
                // keeps a superseded request from reporting itself as an error:
                // a newer query cancels the older one (see launchSearch), and
                // swallowing that here surfaced "StandaloneCoroutine was
                // cancelled" in the banner while results sat right below it.
                if (t is CancellationException) throw t
                Log.w("Discovery", "trending failed", t)
                _trendingOffline.value = t is OfflineException
                _trendingError.value = t.message ?: "Could not load trending"
            } finally {
                _trendingLoading.value = false
            }
        }
    }

    /** Retry after a failed load — the Discovery error state's target. */
    fun retryTrending() {
        trendingLoaded = false
        ensureTrending()
    }

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var playJob: Job? = null
    private var prefetchJob: Job? = null

    /**
     * videoId -> already-resolved stream URL.
     *
     * This is what makes a tap start instantly. Resolving costs one round trip
     * to YouTube (~0.5s on mobile), so we do it for the top results the moment
     * a search returns, in the background. By the time the user has read the
     * list and tapped, the URL is waiting and the tap costs nothing.
     *
     * ConcurrentHashMap because the prefetcher writes from Dispatchers.IO while
     * playResult reads from its own IO coroutine.
     */
    private val streamCache = ConcurrentHashMap<String, InnerTube.Resolved>()

    fun dismissCrash() {
        CrashLogger.clearPersisted(androidContext)
        _lastCrash.value = null
    }

    init {
        // viewModelScope cancels this flow and unregisters its callback when
        // this ViewModel is cleared; rotation keeps the same subscription.
        viewModelScope.launch {
            connectivity.changes.collect { online ->
                _connection.update { it.changed(online) }
            }
        }
        // Lightweight transport poll. Compose can't observe Player callbacks
        // directly without a MediaController, so we sample a few times a second.
        viewModelScope.launch {
            while (true) {
                try {
                    val p = playerController.player
                    val item = p.currentMediaItem
                    val title = item?.mediaMetadata?.title?.toString().orEmpty()
                    _isPlaying.value = p.isPlaying
                    _nowPlaying.value = title
                    _artist.value = item?.mediaMetadata?.artist?.toString().orEmpty()
                    _artworkUrl.value = item?.mediaMetadata?.artworkUri?.toString().orEmpty()
                    // The optimistic placeholder stands in until the player has
                    // genuinely taken over the very track we optimistically
                    // showed — not merely *some* track, which could be the
                    // previous one still loaded.
                    if (title.isNotEmpty() && _state.value.pending?.title == title) {
                        _state.update { it.copy(pending = null) }
                    }
                    _positionMs.value = p.currentPosition.coerceAtLeast(0L)
                    // ExoPlayer reports C.TIME_UNSET (a huge negative) when the
                    // duration is not known yet; clamp that to 0 so the waveform
                    // never divides by a negative length.
                    _durationMs.value = p.duration.coerceAtLeast(0L)
                    _state.update { it.copy(queueSize = p.mediaItemCount) }
                    _shuffleEnabled.value = p.shuffleModeEnabled
                    _repeatAll.value = p.repeatMode == Player.REPEAT_MODE_ALL
                    _currentIndex.value = p.currentMediaItemIndex
                } catch (t: Throwable) {
                // Cancellation is control flow, not a failure. Rethrowing it
                // keeps a superseded request from reporting itself as an error:
                // a newer query cancels the older one (see launchSearch), and
                // swallowing that here surfaced "StandaloneCoroutine was
                // cancelled" in the banner while results sat right below it.
                if (t is CancellationException) throw t
                    _isPlaying.value = false
                }
                // 4x a second keeps the waveform moving without feeling like a
                // separate thread of work.
                delay(250)
            }
        }
    }

    // ------------------------------------------------------------- search

    /**
     * Search-as-you-type.
     *
     * Debounced so a query does not fire a request per keystroke, and so the
     * previous results stay on screen while the new ones load — clearing the
     * list on every keystroke is what makes typed search feel broken.
     */
    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        debounceJob?.cancel()
        val trimmed = value.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return
        debounceJob = viewModelScope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MS)
            launchSearch(trimmed, keepResults = true)
        }
    }

    /** Explicit submit — the keyboard's search action. */
    fun search() {
        debounceJob?.cancel()
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        launchSearch(query, keepResults = false)
    }

    private fun launchSearch(query: String, keepResults: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    searching = true,
                    searchOffline = false,
                    error = null,
                    // Only dropped on an explicit submit; a debounced follow-up
                    // keeps what the user is already reading.
                    results = if (keepResults) it.results else emptyList(),
                    status = null,
                )
            }
            _connection.update { it.copy(restored = false) }
            val startedAt = System.currentTimeMillis()
            try {
                val found = onlineRequest(connectivity::isOnline) { InnerTube.search(query) }
                // YouTube already returns relevance order, which for music
                // queries surfaces the real recording first. Deliberately not
                // re-sorted by view count: that would promote a louder cover
                // over the actual track.
                val ranked = found
                val ms = System.currentTimeMillis() - startedAt
                Log.i(
                    "Search",
                    "query='$query' results=${ranked.size} in ${ms}ms " +
                        "first=${ranked.firstOrNull()?.title}",
                )
                // Resolve the head of the list right now, while the user is
                // still reading it. This is the whole trick behind instant
                // taps: the round trip happens *before* the tap, not after.
                startPrefetch(ranked)
                _state.update {
                    it.copy(
                        searching = false,
                        results = ranked,
                        status = "${ranked.size} results · ${"%.2f".format(ms / 1000.0)}s",
                    )
                }
            } catch (t: Throwable) {
                // Cancellation is control flow, not a failure. Rethrowing it
                // keeps a superseded request from reporting itself as an error:
                // a newer query cancels the older one (see launchSearch), and
                // swallowing that here surfaced "StandaloneCoroutine was
                // cancelled" in the banner while results sat right below it.
                if (t is CancellationException) throw t
                Log.e("Search", "search failed", t)
                _state.update {
                    it.copy(searching = false, searchOffline = t is OfflineException, error = t.message ?: "Search failed")
                }
            }
        }
    }

    // -------------------------------------------------------------- artist

    /** Open an artist's catalogue; the tapped row seeds the page instantly. */
    fun openArtist(row: SearchResult) {
        if (row.browseId.isEmpty()) return
        _artistPage.value = ArtistUiState(seed = row)
        // Catalogue pages are mutually exclusive in the renderer; an artist
        // opened from an open album (or anywhere else) must retire the album.
        _albumPage.value = null
        loadArtist(row.browseId)
    }

    /** Leave the catalogue — back gesture, nav bar or the page's own button. */
    fun closeArtist() {
        artistJob?.cancel()
        artistJob = null
        _artistPage.value = null
    }

    fun retryArtist() {
        val seed = _artistPage.value?.seed ?: return
        loadArtist(seed.browseId)
    }

    private fun loadArtist(browseId: String) {
        artistJob?.cancel()
        artistJob = viewModelScope.launch(Dispatchers.IO) {
            _artistPage.update { it?.copy(loading = true, error = null) }
            try {
                val detail = InnerTube.artistDetail(browseId)
                // null means the request itself failed; parse failures throw
                // and land in the catch below. Either way the page says so
                // instead of standing there with a header and no songs.
                if (detail == null) {
                    _artistPage.update {
                        it?.copy(loading = false, error = "Could not load this artist")
                    }
                } else {
                    Log.i("Artist", "loaded: ${detail.topSongs.size} songs, ${detail.albums.size} albums, ${detail.videos.size} videos, ${detail.relatedArtists.size} related, desc=${detail.description.isNotEmpty()}")
                    _artistPage.update { it?.copy(loading = false, detail = detail) }
                }
            } catch (t: Throwable) {
                // Cancellation is control flow, not a failure (see launchSearch).
                if (t is CancellationException) throw t
                _artistPage.update {
                    it?.copy(loading = false, error = t.message ?: "Could not load this artist")
                }
            }
        }
    }

    // -------------------------------------------------------------- album

    /** Open an album's track list; the tapped row seeds the page instantly. */
    fun openAlbum(row: SearchResult) {
        if (row.browseId.isEmpty()) return
        _albumPage.value = AlbumUiState(seed = row)
        // Catalogue pages are mutually exclusive in the renderer; an album
        // opened from the artist page must retire it.
        _artistPage.value = null
        loadAlbum(row.browseId)
    }

    /** Leave the album — back gesture, nav bar or the page's own button. */
    fun closeAlbum() {
        albumJob?.cancel()
        albumJob = null
        _albumPage.value = null
    }

    fun retryAlbum() {
        val seed = _albumPage.value?.seed ?: return
        loadAlbum(seed.browseId)
    }

    private fun loadAlbum(browseId: String) {
        albumJob?.cancel()
        albumJob = viewModelScope.launch(Dispatchers.IO) {
            _albumPage.update { it?.copy(loading = true, error = null) }
            try {
                val detail = InnerTube.albumDetail(browseId)
                // null means the request itself failed; parse failures throw
                // and land in the catch below. Either way the page says so
                // instead of standing there with a cover and no tracks.
                if (detail == null) {
                    _albumPage.update {
                        it?.copy(loading = false, error = "Could not load this album")
                    }
                } else {
                    _albumPage.update { it?.copy(loading = false, detail = detail) }
                }
            } catch (t: Throwable) {
                // Cancellation is control flow, not a failure (see launchSearch).
                if (t is CancellationException) throw t
                _albumPage.update {
                    it?.copy(loading = false, error = t.message ?: "Could not load this album")
                }
            }
        }
    }

    // --------------------------------------------------------------- play

    /**
     * Warm [streamCache] for the results a user is most likely to tap.
     *
     * Capped and cancellable: a new search abandons the old one, and resolving
     * stops at the first gap so a single unplayable video doesn't stall the
     * rest. Failures are fine — a miss just falls back to resolving on tap.
     */
    private fun startPrefetch(results: List<SearchResult>) {
        prefetchJob?.cancel()
        streamCache.clear()
        // A catalogue row has no video id to resolve — skip it rather than
        // burn one request on "" per artist ahead of the real tracks.
        val targets = results.filter { it.videoId.isNotEmpty() }.take(PREFETCH_COUNT)
        if (targets.isEmpty()) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            for (r in targets) {
                if (!isActive) return@launch
                runCatching { InnerTube.resolve(r.videoId) }
                    .onSuccess { streamCache[r.videoId] = it }
                    .onFailure { Log.w("Search", "prefetch miss for ${r.videoId}: ${it.message}") }
            }
            Log.i("Search", "prefetched ${streamCache.size}/${targets.size} stream URLs")
        }
    }

    /**
     * The core flow: get a stream URL and start playing.
     *
     * A prefetched hit costs no network at all; a miss falls back to a normal
     * resolve. Only the tapped track is resolved before playback — everything
     * else happens behind it, so the wait to hear music stays at one round
     * trip in the worst case and zero in the usual one.
     */
    fun playResult(result: SearchResult) {
        if (_state.value.loadingVideoId != null) return

        playJob?.cancel()
        // Optimistic: put the track in the player bar *now*, so the tap is
        // visibly answered before audio can possibly be ready.
        _state.update { it.copy(pending = result, loadingVideoId = result.videoId, error = null, searchOffline = false) }
        // The full object reaches the player screen immediately — the like
        // toggle must know which track it is acting on before audio exists.
        _currentTrack.value = result

        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = streamCache.remove(result.videoId)
                val resolved = cached ?: InnerTube.resolve(result.videoId)
                playerController.play(listOf(mediaItem(result, resolved)))
                // Recorded only once playback was genuinely handed off, so a
                // track that never starts does not pollute "recently played".
                library.recordPlay(result)
                Log.i(
                    "Play",
                    "started '${result.title}' " +
                        "${resolved.stream.bitrate / 1000}kbps ${resolved.stream.container} " +
                        if (cached != null) "(prefetched)" else "(resolved on tap)",
                )
                // Build the continuous mix behind the first track. Failures here
                // are not worth telling the user about — they just get a shorter
                // queue than intended.
                fillQueue(result)
            } catch (t: Throwable) {
                // Cancellation is control flow, not a failure. Rethrowing it
                // keeps a superseded request from reporting itself as an error:
                // a newer query cancels the older one (see launchSearch), and
                // swallowing that here surfaced "StandaloneCoroutine was
                // cancelled" in the banner while results sat right below it.
                if (t is CancellationException) throw t
                Log.e("Play", "could not play '${result.title}'", t)
                // Drop the optimistic placeholder too — the bar must not keep
                // advertising a track that is never going to start.
                _state.update {
                    it.copy(error = t.message ?: "Could not play this track", pending = null, searchOffline = false)
                }
            } finally {
                // Only clear if nothing newer has taken our slot — a cancelled
                // job's finally runs after its successor has already started.
                _state.update {
                    if (it.loadingVideoId == result.videoId) it.copy(loadingVideoId = null) else it
                }
            }
        }
        playJob = job
    }

    /**
     * Append one track to whatever is already queued, leaving the current
     * track alone — this is what the library's long-press menu means by
     * "Add to queue".
     *
     * Deliberately does **not** touch [currentTrack]: queueing is a request to
     * have the song ready later, not a request to start listening now, and the
     * full-screen player's like button must keep pointing at what is playing.
     */
    fun addToQueue(result: SearchResult) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = streamCache.remove(result.videoId)
                val resolved = cached ?: InnerTube.resolve(result.videoId)
                playerController.enqueue(listOf(mediaItem(result, resolved)))
                Log.i("Queue", "queued '${result.title}' (${if (cached != null) "prefetched" else "resolved"})")
            } catch (t: Throwable) {
                // Cancellation is control flow, not a failure. Rethrowing it
                // keeps a superseded request from reporting itself as an error:
                // a newer query cancels the older one (see launchSearch), and
                // swallowing that here surfaced "StandaloneCoroutine was
                // cancelled" in the banner while results sat right below it.
                if (t is CancellationException) throw t
                Log.w("Queue", "could not queue '${result.title}'", t)
                _state.update {
                    it.copy(error = t.message ?: "Could not add that track to the queue", searchOffline = false)
                }
            }
        }
    }

    /**
     * Resolve the related tracks and append them, so tapping one song yields a
     * continuous mix instead of a single track that stops dead at the end.
     */
    private suspend fun fillQueue(seed: SearchResult) {
        val related = runCatching { InnerTube.related(seed.videoId) }
            .getOrDefault(emptyList())
            .filter { it.videoId != seed.videoId }
            .take(RELATED_QUEUE_SIZE)

        val items = mutableListOf<MediaItem>()
        for (r in related) {
            // Cancellation check: the user may have tapped something else.
            if (!coroutineContext.isActive) return
            val resolved = runCatching { InnerTube.resolve(r.videoId) }.getOrNull() ?: continue
            items += mediaItem(r, resolved)
        }
        if (items.isNotEmpty()) playerController.enqueue(items)
    }

    private fun mediaItem(result: SearchResult, resolved: InnerTube.Resolved): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(result.title.ifEmpty { resolved.title })
            .setArtist(result.channel)
        if (result.thumbUrl.isNotEmpty()) {
            meta.setArtworkUri(Uri.parse(result.thumbUrl))
        }
        return MediaItem.Builder()
            .setUri(resolved.stream.url)
            .setMimeType(resolved.stream.container)
            .setMediaMetadata(meta.build())
            .build()
    }

    fun next() = playerController.next()
    fun previous() = playerController.previous()
    fun togglePlayPause() = playerController.togglePlayPause()
    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    private companion object {
        /** Enough for a solid mix, few enough that the queue fills while track 1 plays. */
        const val RELATED_QUEUE_SIZE = 6

        /**
         * How many top results get their stream URL resolved ahead of the tap.
         * Five covers a realistic reading distance down the list without
         * hammering YouTube.
         */
        const val PREFETCH_COUNT = 5

        /** Below this a query is not worth a round trip. */
        const val MIN_QUERY_LENGTH = 2

        /** Long enough to group keystrokes, short enough to feel immediate. */
        const val DEBOUNCE_MS = 350L
    }
}
