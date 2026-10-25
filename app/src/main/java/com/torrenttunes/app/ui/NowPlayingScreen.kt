package com.torrenttunes.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.ChevronDownGlyph
import com.torrenttunes.app.ui.components.NextGlyph
import com.torrenttunes.app.ui.components.PauseGlyph
import com.torrenttunes.app.ui.components.PinGlyph
import com.torrenttunes.app.ui.components.PineIconButton
import com.torrenttunes.app.ui.components.PlayGlyph
import com.torrenttunes.app.ui.components.PreviousGlyph
import com.torrenttunes.app.ui.components.QueueGlyph
import com.torrenttunes.app.ui.components.RepeatGlyph
import com.torrenttunes.app.ui.components.ShuffleGlyph
import com.torrenttunes.app.ui.components.ThumbnailFill
import com.torrenttunes.app.ui.components.playerGroundColors
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineOnAccent
import com.torrenttunes.app.ui.theme.PinePlayerBottom
import com.torrenttunes.app.ui.theme.PinePlayerTop
import com.torrenttunes.app.ui.theme.PineDivider
import com.torrenttunes.app.ui.theme.PineRadius
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PinePlayerBottom
import com.torrenttunes.app.ui.theme.PinePlayerTop
import com.torrenttunes.app.ui.theme.PineSurface
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary

/**
 * The full-screen player — the visual centrepiece.
 *
 * A per-track gradient ground derived from the artwork's palette, so the
 * screen visibly belongs to the song being played. Everything here is wired to
 * real ExoPlayer state: the slider reads the actual position, shuffle and
 * repeat read the player's own flags, and the queue lists the player's actual
 * media items. Nothing is decorative.
 */
@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    library: LibraryViewModel,
    currentTrack: SearchResult?,
    onClose: () -> Unit,
    onOpenSound: () -> Unit = {},
) {
    val nowPlaying by vm.nowPlaying.collectAsState()
    val artist by vm.artist.collectAsState()
    val artworkUrl by vm.artworkUrl.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val positionMs by vm.positionMs.collectAsState()
    val durationMs by vm.durationMs.collectAsState()
    // ExoPlayer exposes no generic "buffering" flag through this ViewModel, and
    // the mini-player already distinguishes "still fetching a stream" from
    // "playing". A pending resolve is the state the user actually waits on, so
    // that is what the play control reflects — labelled as starting, not as
    // buffering, because that is what is true.
    val pendingResolve = vm.state.collectAsState().value.loadingVideoId != null
    val loading = pendingResolve && !isPlaying
    val shuffleEnabled by vm.shuffleEnabled.collectAsState()
    val repeatAll by vm.repeatAll.collectAsState()
    val queue by vm.playerController.queue.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val libraryState by library.state.collectAsState()

    val title = nowPlaying.ifEmpty { currentTrack?.title.orEmpty() }
    val artistText = artist.ifEmpty { currentTrack?.channel.orEmpty() }
    val seed = title.ifEmpty { "pineapple" }

    var showQueue by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Captured: `maxWidth`/`maxHeight` come from BoxWithConstraintsScope,
        // which is shadowed once we enter a nested Column/Row scope — reading
        // them deeper in fails to resolve.
        val windowWidth = maxWidth
        val windowHeight = maxHeight

        // Background: an intentional, single-source dark ground. The poster
        // bleed looked like a wallpaper; classic Apple Music / old Spotify stays
        // on a near-black wash so the artwork reads as the one thing that
        // matters. Nothing here competes with the cover.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(PinePlayerTop, PinePlayerBottom))),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ------------------------------------------------------- top row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PineSpace.Md, vertical = PineSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PineIconButton(
                    onClick = onClose,
                    glyph = { ChevronDownGlyph(24.dp, PineTextPrimary) },
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        color = PineTextSecondary,
                    )
                }
                PineIconButton(
                    onClick = onOpenSound,
                    glyph = {
                        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                            val h = size.height
                            val w = size.width
                            val barW = w / 6f
                            val xs = listOf(0f, barW * 1.7f, barW * 3.4f)
                            val frac = listOf(0.45f, 0.95f, 0.65f)
                            xs.zip(frac).forEach { (x, f) ->
                                val hh = h * f
                                drawRoundRect(
                                    color = PineTextPrimary,
                                    topLeft = androidx.compose.ui.geometry.Offset(x, (h - hh) / 2),
                                    size = androidx.compose.ui.geometry.Size(barW, hh),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2, barW / 2),
                                )
                            }
                        }
                    },
                )
                PineIconButton(
                    onClick = { showQueue = true },
                    glyph = { QueueGlyph(23.dp, PineTextPrimary) },
                )
            }

            // ------------------------------------------------------ artwork
            //
            // Capped by *height* as well as width. A square sized purely from
            // the screen width is taller than a short window (landscape, a
            // small phone, or large font settings), and would push the
            // transport controls off the bottom — which is the one thing the
            // player cannot lose.
            val artWidth = windowWidth - PineSpace.Xxl * 2
            // Top bar + title + slider + transport + queue hint + paddings.
            val reserved = 300.dp
            val artSize = minOf(artWidth, windowHeight - reserved).coerceAtLeast(140.dp)
    // Entrance: artwork scales in on mount (cheap, runs on UI thread only).
    val enterScaleState = remember { androidx.compose.animation.core.Animatable(0.92f) }
    LaunchedEffect(Unit) { enterScaleState.animateTo(1f, animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f)) }
    val enterScale = enterScaleState.value

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(artSize).scale(enterScale).shadow(28.dp, RoundedCornerShape(PineRadius.Lg), ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .35f))) {
                    ThumbnailFill(
                        url = artworkUrl,
                        seed = seed,
                        corner = PineRadius.Lg,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // -------------------------------------------------- title + like
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PineSpace.Xxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifEmpty { "Nothing playing" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = PineTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 28.sp,
                    )
                    if (artistText.isNotEmpty()) {
                        Spacer(Modifier.height(PineSpace.Xs))
                        Text(
                            artistText,
                            style = MaterialTheme.typography.titleMedium,
                            color = PineTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (currentTrack != null) {
                    Spacer(Modifier.width(PineSpace.Md))
                    LikeButton(
                        // Derived from the collected state, not library.isLiked():
                        // that reads store.state.value, which registers no Compose
                        // snapshot read, so while paused nothing was emitting and the
                        // heart stayed hollow until audio resumed.
                        liked = libraryState.liked.any { it.videoId == currentTrack.videoId },
                        onToggle = { library.toggleLike(currentTrack) },
                    )
                }
            }

            Spacer(Modifier.height(PineSpace.Lg))

            // ------------------------------------------------------- slider
            PlayerSlider(
                positionMs = positionMs,
                durationMs = durationMs,
                onSeek = { ms -> vm.playerController.seekTo(ms) },
            )

            // ---------------------------------------------------- transport
            TransportRow(
                isPlaying = isPlaying,
                loading = loading,
                shuffleEnabled = shuffleEnabled,
                repeatAll = repeatAll,
                onShuffle = { vm.playerController.toggleShuffle() },
                onPrevious = { vm.playerController.previous() },
                onToggle = { vm.playerController.togglePlayPause() },
                onNext = { vm.playerController.next() },
                onRepeat = { vm.playerController.toggleRepeat() },
            )

            // ---------------------------------------------------- queue hint
            if (queue.isNotEmpty()) {
                Spacer(Modifier.height(PineSpace.Lg))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showQueue = true }
                        .padding(horizontal = PineSpace.Xxl, vertical = PineSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (queue.size > 1) "Up next · ${queue.size - 1} more" else "Queue",
                        style = MaterialTheme.typography.labelMedium,
                        color = PineTextSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    QueueGlyph(18.dp, PineTextSecondary)
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }

    if (showQueue) {
        QueueSheet(
            queue = queue,
            currentIndex = currentIndex,
            onPick = { index ->
                vm.playerController.seekToIndex(index)
                showQueue = false
            },
            onDismiss = { showQueue = false },
        )
    }
}

// ---------------------------------------------------------------- like

/**
 * The like control, sharing the Library's heart silhouette so "saved" looks
 * identical wherever it appears.
 *
 * Filled accent when liked, outline otherwise — the fill is the state, so it
 * is legible without relying on colour alone.
 */
@Composable
private fun LikeButton(liked: Boolean, onToggle: () -> Unit) {
    // A spring on the scale is what makes the tap feel like it did something
    // rather than like a colour swapped.
    val scale by animateFloatAsState(
        targetValue = if (liked) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "like-scale",
    )
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Scaled rather than re-laid-out, so the spring does not reflow the row.
        com.torrenttunes.app.ui.components.HeartGlyph(
            glyphSize = 27.dp * scale,
            color = if (liked) PineAccent else PineTextSecondary,
            filled = liked,
        )
    }
}

// --------------------------------------------------------------- slider

@Composable
private fun PlayerSlider(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var pressing by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(1) }
    val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = (dragging ?: fraction)
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging != null || pressing) 1.4f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 850f),
        label = "thumb",
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = PineSpace.Xxl)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        pressing = true
                        val f = (offset.x / widthPx).coerceIn(0f, 1f)
                        dragging = f
                        onSeek((f * durationMs).toLong())
                        dragging = null
                        pressing = false
                    }
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val curr = dragging ?: fraction
                        dragging = (curr + delta / widthPx).coerceIn(0f, 1f)
                        pressing = true
                    },
                    onDragStarted = {
                        pressing = true
                        dragging = fraction
                    },
                    onDragStopped = {
                        dragging?.let { f -> onSeek((f * durationMs).toLong()) }
                        dragging = null
                        pressing = false
                    },
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackH = 7.dp.toPx()
                val cy = size.height / 2f
                val w = size.width
                val corner = CornerRadius(trackH / 2f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.14f),
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(w, trackH),
                    cornerRadius = corner,
                )
                val played = shown * w
                drawRoundRect(
                    color = PineAccent,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(played, trackH),
                    cornerRadius = corner,
                )
                drawCircle(
                    color = PineAccent,
                    radius = 10.dp.toPx() * thumbScale,
                    center = Offset(played, cy),
                )
                drawCircle(
                    color = PineOnAccent,
                    radius = 3.5.dp.toPx() * thumbScale,
                    center = Offset(played, cy),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtMs(positionMs), style = MaterialTheme.typography.labelSmall, color = PineTextSecondary)
            Text(
                if (durationMs > 0) "-${fmtMs(durationMs - positionMs)}" else "0:00",
                style = MaterialTheme.typography.labelSmall,
                color = PineTextSecondary,
            )
        }
    }
}

// ------------------------------------------------------------- transport

/**
 * Shuffle, previous, play/pause, next, repeat.
 *
 * Play/pause is deliberately much larger than the rest — it is the one control
 * a user reaches for without looking. Shuffle and repeat read ExoPlayer's own
 * flags, so their lit state cannot drift from what the queue is actually doing.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    loading: Boolean,
    shuffleEnabled: Boolean,
    repeatAll: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PineSpace.Lg, vertical = PineSpace.Md),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PineIconButton(
            onClick = onShuffle,
            glyph = { ShuffleGlyph(23.dp, if (shuffleEnabled) PineAccent else PineTextSecondary) },
        )
        PineIconButton(onClick = onPrevious, glyph = { PreviousGlyph(30.dp, PineTextPrimary) })

        // The dominant control: a filled accent circle.
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(PineAccent)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> androidx.compose.material3.CircularProgressIndicator(
                    Modifier.size(26.dp),
                    strokeWidth = 2.6.dp,
                    color = Color(0xFF140A00),
                )
                isPlaying -> PauseGlyph(28.dp, Color(0xFF140A00))
                else -> PlayGlyph(28.dp, Color(0xFF140A00))
            }
        }

        PineIconButton(onClick = onNext, glyph = { NextGlyph(30.dp, PineTextPrimary) })
        PineIconButton(
            onClick = onRepeat,
            glyph = { RepeatGlyph(23.dp, if (repeatAll) PineAccent else PineTextSecondary, once = false) },
        )
    }
}

// ---------------------------------------------------------------- queue

/**
 * The real queue.
 *
 * Driven by the player's own media items, so it cannot claim a track is
 * queued when it is not. Tapping an entry jumps to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: List<androidx.media3.common.MediaItem>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PineSurface,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = PineSpace.Md)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PineDivider),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = PineSpace.Lg),
        ) {
            Text(
                "Up next",
                style = MaterialTheme.typography.titleLarge,
                color = PineTextPrimary,
                modifier = Modifier.padding(horizontal = PineSpace.Xl, vertical = PineSpace.Md),
            )
            HorizontalDivider(color = PineDivider)
            queue.forEachIndexed { index, item ->
                val meta = item.mediaMetadata
                val title = meta.title?.toString().orEmpty()
                val sub = meta.artist?.toString().orEmpty()
                val art = meta.artworkUri?.toString().orEmpty()
                val isCurrent = index == currentIndex

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index) }
                        .padding(horizontal = PineSpace.Xl, vertical = PineSpace.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.torrenttunes.app.ui.components.Thumbnail(
                        art, title, size = 44.dp, corner = PineRadius.Sm,
                    )
                    Spacer(Modifier.width(PineSpace.Md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title.ifEmpty { "Unknown" },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isCurrent) PineAccent else PineTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sub.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelMedium,
                                color = PineTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isCurrent) {
                        PinGlyph(16.dp, PineAccent)
                    }
                }
            }
        }
    }
}
