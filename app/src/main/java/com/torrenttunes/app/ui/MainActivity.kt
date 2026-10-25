package com.torrenttunes.app.ui

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.ArtistRow
import com.torrenttunes.app.ui.components.AlbumRow
import com.torrenttunes.app.ui.components.CloseGlyph
import com.torrenttunes.app.ui.components.NextGlyph
import com.torrenttunes.app.ui.components.PauseGlyph
import com.torrenttunes.app.ui.components.PineWindowWidth
import com.torrenttunes.app.ui.components.PlayGlyph
import com.torrenttunes.app.ui.components.PineIconButton
import com.torrenttunes.app.ui.components.SearchGlyph
import com.torrenttunes.app.ui.components.SectionHeader
import com.torrenttunes.app.ui.components.StateMessage
import com.torrenttunes.app.ui.components.TrackActionsSheet
import com.torrenttunes.app.ui.components.Thumbnail
import com.torrenttunes.app.ui.components.TrackRow
import com.torrenttunes.app.ui.components.TrackRowSkeleton
import com.torrenttunes.app.ui.components.Waveform
import com.torrenttunes.app.ui.components.pineWindowWidth
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineBackground
import com.torrenttunes.app.ui.theme.PineError
import com.torrenttunes.app.ui.theme.PineGutter
import com.torrenttunes.app.ui.theme.PineOnAccent
import com.torrenttunes.app.ui.theme.PineRadius
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PineSurface
import com.torrenttunes.app.ui.theme.PineSurfaceHigh
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary
import com.torrenttunes.app.ui.theme.PineappleTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

/**
 * Widest a phone layout may grow before it stops stretching.
 *
 * On a tablet or a folded-out screen the content is centred inside this
 * rather than spread edge to edge — a 1200 dp-wide search row is harder to
 * scan, not easier, and stretching a phone layout is not an adaptive layout.
 */
private val MaxContentWidth = 760.dp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /**
     * Scoped here rather than obtained inside a composable, because Hilt's
     * default factory already knows how to build a `@HiltViewModel` for an
     * `@AndroidEntryPoint` activity — so no navigation-hilt artifact is needed
     * to hand the same instance to Search, the Library tab and NowPlaying.
     */
    private val libraryVm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PineappleTheme {
                Surface(Modifier.fillMaxSize(), color = PineBackground) {
                    PineappleScreen(vm, libraryVm)
                }
            }
        }
    }
}

/**
 * Remembers the tab stack across rotation and process death.
 *
 * Enums are not Bundle-safe on their own, so the stack travels as its names
 * and comes back as the matching [AppTab] values. Anything unrecognisable is
 * dropped rather than crashing a restore — the user simply starts one page
 * shallower than they were.
 */
private val TabHistorySaver = listSaver<List<AppTab>, String>(
    save = { history -> history.map { it.name } },
    restore = { names ->
        names.mapNotNull { name -> AppTab.entries.firstOrNull { it.name == name } }
    },
)

// --------------------------------------------------------------------- screen

@Composable
fun PineappleScreen(vm: MainViewModel, library: LibraryViewModel) {
    val state by vm.state.collectAsState()
    val crash by vm.lastCrash.collectAsState()
    val connection by vm.connection.collectAsState()
    val trending by vm.trending.collectAsState()
    // Collected, not read: NowPlaying must follow a track change without
    // needing to be closed and reopened first.
    val currentTrack by vm.currentTrack.collectAsState()
    val nowPlaying by vm.nowPlaying.collectAsState()
    val artistPage by vm.artistPage.collectAsState()
    val albumPage by vm.albumPage.collectAsState()

    // Media3 posts the lockscreen/notification player from PlaybackService,
    // but Android 13+ gates it behind POST_NOTIFICATIONS. Ask once per
    // session when the user actually starts listening (first play). If denied,
    // playback keeps working in-app; the lockscreen controls just stay hidden.
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted/denied: nothing further to act on */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    rememberCoroutineScope()

    // Show a remediation if notifications are disabled at the OS level for
    // Pineapple, which is the only thing that can defeat the
    // media-posting path on Android 12 and on Android 13+.
    LaunchedEffect(currentTrack) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (currentTrack != null && !nm.areNotificationsEnabled()) {
            val result = snackbarHostState.showSnackbar(
                message = "Notifications are disabled for Pineapple; the lock-screen player won't appear.",
                actionLabel = "Settings",
            )
            if (result == SnackbarResult.ActionPerformed) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }
        }
    }

    // rememberSaveable: a rotation must not dump you back on Search, and must
    // not silently close the player over a track that is still playing.
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var soundOpen by rememberSaveable { mutableStateOf(false) }

    // Every tab opened so far, in order.
    //
    // The system back button walks back through this rather than leaving the
    // app, so it returns to the page you were actually on. Held as a list
    // rather than a single current-tab because "go back" has no meaning until
    // there is somewhere to go back *to* — and once the list holds only its
    // root there is nothing left, which is exactly when the app should close.
    var tabHistory by rememberSaveable(stateSaver = TabHistorySaver) {
        mutableStateOf(listOf(AppTab.SEARCH))
    }
    val tab = tabHistory.last()

    /** Moves to [next], recording where it came from so back can return. */
    fun goTab(next: AppTab) {
        // Compare against the live history rather than against `tab`, the
        // composition-time snapshot above. The nav bar holds a reference to
        // this local function across recompositions, and that reference keeps
        // its original capture — so `tab` went stale at the first navigation
        // and never moved off SEARCH.
        //
        // The effect was that tapping Search looked dead: the guard saw
        // next == tab and returned, while Discovery and Library only worked
        // because the stale SEARCH differs from them. `tabHistory` stays
        // correct here because it is read through the saveable delegate, which
        // is the same object every time.
        if (next != tabHistory.last()) tabHistory = tabHistory + next
        // The nav bar and the search field are exits from whatever page is
        // showing, so leaving for another tab closes an open artist or album
        // catalogue. A no-op when nothing is open.
        vm.closeArtist()
        vm.closeAlbum()
    }

    // Registered while the player is open so the mini-player cannot swallow
    // the gesture: back closes the player first, then unwinds the pages.
    // Back walks: player, then an open artist page, then an open album page,
    // then the tab stack (an artist opened from an album returns to it).
    BackHandler(
        enabled = soundOpen || playerOpen || artistPage != null || albumPage != null || tabHistory.size > 1,
    ) {
        when {
            // Sound settings over the player: closes before the player.
            soundOpen -> soundOpen = false
            // The player is mounted over everything, including a catalogue
            // page opened underneath it, so it closes first.
            playerOpen -> playerOpen = false
            artistPage != null -> vm.closeArtist()
            albumPage != null -> vm.closeAlbum()
            else -> tabHistory = tabHistory.dropLast(1)
        }
    }

if (soundOpen) {
        SoundSettingsScreen(manager = vm.audioEffects, onClose = { soundOpen = false })
        return
    }

    // The full-screen player is mounted *over* the scaffold rather than
    // beside it, so opening it cannot reflow the list underneath — audio is
    // owned by MainViewModel and keeps playing either way.
    if (playerOpen) {
        NowPlayingScreen(
            vm = vm,
            library = library,
            currentTrack = currentTrack,
            onClose = { playerOpen = false },
            onOpenSound = { soundOpen = true },
        )
        return
    }

    val searchFocus = remember { FocusRequester() }

    Scaffold(
        topBar = {
            PineappleTopBar(
                vm = vm,
                focusRequester = searchFocus,
                onSubmit = { goTab(AppTab.SEARCH) },
            )
        },
        bottomBar = {
            // Mini-player stacked directly above the nav bar so the two read
            // as one pinned unit, the way Spotify stacks them.
            Column {
                AnimatedVisibility(
                    visible = state.pending != null || nowPlaying.isNotEmpty(),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    PlayerBar(vm, onExpand = { playerOpen = true })
                }
                AppNavBar(current = tab, onSelect = ::goTab)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = PineBackground,
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // On a wide window the phone layout is centred at a readable width
            // rather than stretched edge to edge — a 1200 dp-wide search row is
            // harder to scan, not easier.
            val constrained =
                if (maxWidth > MaxContentWidth) Modifier.widthIn(max = MaxContentWidth)
                else Modifier.fillMaxWidth()

            Column(Modifier.fillMaxSize().then(constrained).align(Alignment.TopCenter)) {
                crash?.let { trace ->
                    Spacer(Modifier.height(PineSpace.Sm))
                    Box(Modifier.padding(horizontal = PineGutter)) {
                        CrashCard(trace = trace, onDismiss = vm::dismissCrash)
                    }
                }

                state.error?.takeUnless { state.searchOffline && connection.online }?.let {
                    Spacer(Modifier.height(PineSpace.Sm))
                    ErrorBanner(message = it)
                }

                connection.message(
                    artistPage == null && when (tab) {
                        AppTab.SEARCH -> state.results.isNotEmpty()
                        AppTab.DISCOVERY -> trending.isNotEmpty()
                        AppTab.LIBRARY -> false
                    },
                )
                    ?.takeIf { !state.searchOffline || connection.online }?.let {
                        Spacer(Modifier.height(PineSpace.Sm))
                        ErrorBanner(
                            message = it,
                            color = if (connection.online) PineAccent else PineError,
                            onRetry = if (tab == AppTab.SEARCH && state.searchOffline) vm::search else null,
                        )
                    }

                // A catalogue (artist or album) replaces the tab content in
                // place rather than overlaying it, so the nav bar, mini-player
                // and search field keep working around it. An artist opened
                // from an album renders above it; back reveals the album again.
                val artist = artistPage
                val album = albumPage
                if (artist != null) {
                    ArtistScreen(
                        vm = vm,
                        library = library,
                        state = artist,
                        onClose = vm::closeArtist,
                        onOpenAlbum = { vm.openAlbum(it) },
                        onOpenArtist = { vm.openArtist(it) },
                    )
                } else if (album != null) {
                    AlbumScreen(
                        vm = vm,
                        library = library,
                        state = album,
                        onClose = vm::closeAlbum,
                        onOpenArtist = { vm.openArtist(it) },
                    )
                } else when (tab) {
                    AppTab.SEARCH -> SearchTab(vm, library)
                    AppTab.LIBRARY -> LibraryScreen(
                        library = library,
                        onPlay = vm::playResult,
                        onAddToQueue = vm::addToQueue,
                        onOpenPlayer = { playerOpen = true },
                    )
                    AppTab.DISCOVERY -> DiscoveryScreen(
                        vm = vm,
                        library = library,
                        onPlay = vm::playResult,
                        onOpenSearch = { goTab(AppTab.SEARCH) },
                        onOpenPlayer = { playerOpen = true },
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ top bar

/**
 * Branding plus the search field, pinned above every tab.
 *
 * Keeping search on every page is deliberate: typing anywhere should land you
 * on results, rather than making you navigate to Search first.
 */
@Composable
private fun PineappleTopBar(
    vm: MainViewModel,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val focusManager = LocalFocusManager.current

    Surface(color = PineBackground) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = PineGutter, end = PineGutter, top = PineSpace.Md, bottom = PineSpace.Sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pineapple",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PineAccent,
                    letterSpacing = (-0.6).sp,
                    modifier = Modifier.semantics { contentDescription = "Pineapple" },
                )
            }

            Spacer(Modifier.height(PineSpace.Md))

            TextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        "Search songs, artists, albums",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PineTextSecondary,
                    )
                },
                leadingIcon = {
                    SearchGlyph(19.dp, PineTextSecondary)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        PineIconButton(
                            onClick = {
                                vm.onQueryChange("")
                                focusManager.clearFocus()
                            },
                            targetSize = 40.dp,
                            glyph = { CloseGlyph(17.dp, PineTextSecondary) },
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(PineRadius.Pill),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PineSurfaceHigh,
                    unfocusedContainerColor = PineSurfaceHigh,
                    disabledContainerColor = PineSurfaceHigh,
                    errorContainerColor = PineSurfaceHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    focusedTextColor = PineTextPrimary,
                    unfocusedTextColor = PineTextPrimary,
                    cursorColor = PineAccent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        vm.search()
                        focusManager.clearFocus()
                        onSubmit()
                    },
                ),
            )
        }
    }
}

// ----------------------------------------------------------------- search

@Composable
private fun SearchTab(vm: MainViewModel, library: LibraryViewModel) {
    val state by vm.state.collectAsState()
    val connection by vm.connection.collectAsState()
    val currentTrack by vm.currentTrack.collectAsState()
    val libraryState by library.state.collectAsState()

    // Which result's sheet is open. A search result is a track like any other,
    // so it gets the same actions the Library rows do.
    var sheetTrack by remember { mutableStateOf<SearchResult?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Progress under the field rather than a spinner inside it: a thin
        // accent rule reads as "working" without stealing focus from results.
        if (state.searching) {
            LinearProgressRule()
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = PineSpace.Xl),
        ) {
            when {
                state.error != null && state.results.isEmpty() -> {
                    item(key = "error") {
                        StateMessage(
                            title = when {
                                !state.searchOffline -> "Could not search"
                                connection.online -> "Connection restored"
                                else -> "You're offline"
                            },
                            body = if (state.searchOffline && connection.online) {
                                "Try again when you're ready."
                            } else state.error,
                            actionLabel = "Try again",
                            onAction = { vm.search() },
                            glyph = { size, color -> SearchGlyph(size, color, 3.dp) },
                        )
                    }
                }

                state.searching && state.results.isEmpty() -> {
                    items(6) { key -> TrackRowSkeleton() }
                }

                state.results.isEmpty() -> {
                    item(key = "empty") {
                        StateMessage(
                            title = "Search Pineapple",
                            body = "Find a song, artist or album. Tap any result and it starts " +
                                "playing straight away, with a mix building behind it.",
                            glyph = { size, color -> SearchGlyph(size, color, 3.dp) },
                        )
                    }
                }

                else -> {
                    state.status?.let { s ->
                        item(key = "status") {
                            Text(
                                s,
                                style = MaterialTheme.typography.labelMedium,
                                color = PineTextSecondary,
                                modifier = Modifier.padding(
                                    start = PineGutter,
                                    end = PineGutter,
                                    top = PineSpace.Sm,
                                    bottom = PineSpace.Xs,
                                ),
                            )
                        }
                    }

                    items(state.results, key = { it.dedupeKey }) { result ->
                        // A catalogue door, not a track: tapping opens the page
                        // instead of resolving a video id that does not exist.
                        if (result.isArtist) {
                            ArtistRow(artist = result, onClick = { vm.openArtist(result) })
                        } else if (result.isAlbum) {
                            AlbumRow(album = result, onClick = { vm.openAlbum(result) })
                        } else {
                            TrackRow(
                                track = result,
                                onClick = { vm.playResult(result) },
                                isLoading = state.loadingVideoId == result.videoId,
                                isCurrent = currentTrack?.videoId == result.videoId,
                                onLongClick = { sheetTrack = result },
                                onOverflow = { sheetTrack = result },
                            )
                        }
                    }
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            library = libraryState,
            libraryVm = library,
            onPlay = { vm.playResult(track) },
            onAddToQueue = { vm.addToQueue(track) },
            onDismiss = { sheetTrack = null },
            // No source playlist: a search result is not inside one, so
            // "Remove from this playlist" would be a lie.
            sourcePlaylistId = null,
        )
    }
}

/** A thin indeterminate rule shown while a search is in flight. */
@Composable
private fun LinearProgressRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(PineSurfaceHigh),
    ) {
        // A short accent segment sweeping across, drawn as a simple animated
        // bar rather than pulling in a progress component for 2 dp.
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "rule")
        val frac by transition.animateFloat(
            initialValue = 0.05f,
            targetValue = 0.45f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(620),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "rule-frac",
        )
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(2.dp)
                .background(PineAccent),
        )
    }
}

// ---------------------------------------------------------------- empty state

@Composable
private fun ErrorBanner(message: String, color: Color = PineError, onRetry: (() -> Unit)? = null) {
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(PineRadius.Md),
        modifier = Modifier.padding(horizontal = PineGutter),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PineSpace.Lg, vertical = PineSpace.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            onRetry?.let { retry ->
                TextButton(onClick = retry) { Text("Try again", color = color) }
            }
        }
    }
}

// ----------------------------------------------------------------- crash card

/** Shows the last uncaught exception so the trace can be read off the screen. */
@Composable
private fun CrashCard(trace: String, onDismiss: () -> Unit) {
    val lines = trace.lines().take(24).joinToString("\n")
    Surface(
        color = PineError.copy(alpha = 0.14f),
        contentColor = PineError,
        shape = RoundedCornerShape(PineRadius.Md),
    ) {
        Column(Modifier.fillMaxWidth().padding(PineSpace.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Last crash",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = PineError,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = PineError, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(PineSpace.Xs))
            Text(
                lines,
                style = MaterialTheme.typography.labelSmall,
                color = PineTextSecondary,
                maxLines = 24,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ----------------------------------------------------------------- mini player

/**
 * The persistent mini-player.
 *
 * Slim by design: artwork, identity, a tappable progress waveform and one
 * play/pause. Previous, next, shuffle, repeat and the queue all live in the
 * full-screen player — putting them here as well is what made the old bar
 * read as a control panel rather than as a now-playing strip.
 *
 * Tapping the row opens the full-screen player; the play/pause button stops
 * propagation so it works without opening anything.
 */
@Composable
fun PlayerBar(vm: MainViewModel, onExpand: () -> Unit = {}) {
    val isPlaying by vm.isPlaying.collectAsState()
    val nowPlaying by vm.nowPlaying.collectAsState()
    val artist by vm.artist.collectAsState()
    val artworkUrl by vm.artworkUrl.collectAsState()
    val positionMs by vm.positionMs.collectAsState()
    val durationMs by vm.durationMs.collectAsState()
    val state by vm.state.collectAsState()

    // Optimistic view: while a tap is still fetching its stream, the bar shows
    // the track the user chose rather than the one that happens to be loaded.
    val pending = state.pending
    val starting = nowPlaying.isEmpty() && pending != null
    val shownTitle = nowPlaying.ifEmpty { pending?.title.orEmpty() }
    val shownArtwork = artworkUrl.ifEmpty { pending?.thumbUrl.orEmpty() }

    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(color = PineSurface) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = PineSurfaceHigh)

            // A slim waveform: progress at a glance, and tappable to scrub.
            Waveform(
                seed = nowPlaying.ifEmpty { "pineapple-idle" },
                progress = progress,
                barWidth = 1.6.dp,
                gap = 1.4.dp,
                playedColor = PineAccent,
                unplayedColor = PineSurfaceHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(15.dp)
                    .padding(horizontal = PineSpace.Lg, vertical = 3.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            val total = durationMs
                            if (total > 0 && size.width > 0) {
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                vm.playerController.seekTo((fraction * total).toLong())
                            }
                        }
                    },
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = shownTitle.isNotEmpty(), onClick = onExpand)
                    .padding(start = PineSpace.Lg, end = PineSpace.Md, bottom = PineSpace.Sm, top = PineSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(shownArtwork, shownTitle.ifEmpty { "Pineapple" }, size = 44.dp, corner = PineRadius.Sm)
                Spacer(Modifier.width(PineSpace.Md))

                Column(Modifier.weight(1f)) {
                    Text(
                        shownTitle.ifEmpty { "Nothing playing" },
                        style = MaterialTheme.typography.titleMedium,
                        color = PineTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(PineSpace.Xxs))
                    Text(
                        when {
                            starting -> "Starting…"
                            nowPlaying.isEmpty() -> "Search for a song to start"
                            else -> artist.ifEmpty {
                                if (state.queueSize > 1) "${state.queueSize} tracks queued" else "Streaming"
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = PineTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (nowPlaying.isNotEmpty()) {
                    Text(
                        "${fmtMs(positionMs)} / ${fmtMs(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PineTextSecondary,
                        modifier = Modifier.padding(end = PineSpace.Sm),
                    )
                }

                PineIconButton(
                    onClick = { vm.playerController.togglePlayPause() },
                    background = PineAccent,
                    targetSize = 44.dp,
                    glyph = {
                        when {
                            starting -> CircularProgressIndicator(
                                Modifier.size(19.dp),
                                strokeWidth = 2.dp,
                                color = PineOnAccent,
                            )
                            isPlaying -> PauseGlyph(19.dp, PineOnAccent)
                            else -> PlayGlyph(19.dp, PineOnAccent)
                        }
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------- helpers

internal fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
}
