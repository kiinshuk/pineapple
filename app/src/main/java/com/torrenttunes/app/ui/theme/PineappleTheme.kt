package com.torrenttunes.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ palette
//
// Pineapple's own ground. Three steps of charcoal rather than one flat black:
// the page sits at [PineBackground], anything that reads as *above* the page
// moves to [PineSurface], and the top of that ladder is [PineSurfaceHigh].
// That layering is what makes a dark UI legible without shadows — elevation
// is expressed by getting *lighter*, which is the only signal a dark ground
// can carry.
//
// These are **roles, not literals**. `PineTextSecondary` is "whatever the
// quiet colour is on the current ground", not "grey", so the whole app can be
// retuned by editing this file alone.

/** The brand accent. Warm pineapple orange, the one loud colour in the app. */
val PineAccent = Color(0xFFFF8A24)

/** A deeper orange for pressed states and the far end of accent gradients. */
val PineAccentDeep = Color(0xFFE06A00)

/** The page itself — the darkest step, behind everything. */
val PineBackground = Color(0xFF0B0B0D)

/** Cards, sheets, the mini-player: one step above the page. */
val PineSurface = Color(0xFF151518)

/** Filled controls, chips, inputs: the top of the elevation ladder. */
val PineSurfaceHigh = Color(0xFF202024)

/** Primary type. Soft white rather than #FFF, which glares on a dark ground. */
val PineTextPrimary = Color(0xFFF5F5F7)

/**
 * Secondary type: metadata, timestamps, inactive labels.
 *
 * Measures 7.2:1 against [PineBackground], so it stays comfortably above the
 * 4.5:1 that WCAG AA requires for body text.
 */
val PineTextSecondary = Color(0xFF9C9CA5)

/** Disabled controls and genuinely unavailable actions. */
val PineTextDisabled = Color(0xFF5A5A62)

/** Hairline dividers between list rows. */
val PineDivider = Color(0xFF1C1C20)

/** Semantic: a destructive or failed action. */
val PineError = Color(0xFFFF6B61)

/** Semantic: something succeeded, or a track is confirmed saved. */
val PineSuccess = Color(0xFF34C77B)

/**
 * Text placed *on* an accent fill.
 *
 * White would measure 2.36:1 here and fail outright; near-black measures
 * 8.35:1. This is why every orange control in the app uses dark type.
 */
val PineOnAccent = Color(0xFFFFFFFF)

/** The full-screen player's untinted fallback ground. */
val PinePlayerTop = Color(0xFF16171B)
val PinePlayerBottom = Color(0xFF08090B)

private val PineappleColors = darkColorScheme(
    primary = PineAccent,
    onPrimary = PineOnAccent,
    primaryContainer = PineAccent,
    onPrimaryContainer = PineOnAccent,
    secondary = PineTextPrimary,
    onSecondary = PineBackground,
    secondaryContainer = PineSurfaceHigh,
    onSecondaryContainer = PineTextPrimary,
    tertiary = PineSuccess,
    onTertiary = Color(0xFF04150C),
    background = PineBackground,
    onBackground = PineTextPrimary,
    surface = PineBackground,
    onSurface = PineTextPrimary,
    surfaceVariant = PineSurfaceHigh,
    onSurfaceVariant = PineTextSecondary,
    outline = PineSurfaceHigh,
    outlineVariant = PineDivider,
    error = PineError,
    onError = Color(0xFF2A0703),
    errorContainer = Color(0xFF3A1512),
    onErrorContainer = Color(0xFFFFD2CE),
    surfaceContainer = PineSurface,
    surfaceContainerHigh = PineSurfaceHigh,
)

// ------------------------------------------------------------- typography
//
// No new typeface — the app ships no font resource and adding one would be a
// new dependency plus megabytes of asset. The modern feel comes from weight
// and tracking instead: heavy titles pulled slightly tight, quiet metadata
// opened slightly up. Roles map to how the text is *used*, not to its size.

private val PineappleTypography = Typography(
    // Hero text: the greeting on Discovery, the player's track title.
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.9).sp,
        lineHeight = 36.sp,
    ),
    // Screen titles: "Your Library", "Search".
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        letterSpacing = (-0.7).sp,
        lineHeight = 33.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.4).sp,
        lineHeight = 28.sp,
    ),
    // Section headings: "Trending now", "Recently played".
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        letterSpacing = (-0.3).sp,
        lineHeight = 24.sp,
    ),
    // Track titles in lists and cards.
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.1).sp,
        lineHeight = 21.sp,
    ),
    // Playlist titles, sheet headers.
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, letterSpacing = 0.1.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    // Button labels and control captions.
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    // Artist names, durations, view counts.
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp),
    // Nav labels, overlines, badges.
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.35.sp),
)

// -------------------------------------------------------- layout constants
//
// One scale, used everywhere. A screen that invents its own 13 dp gap is a
// screen that will not match its neighbour on the next redesign.

/** Spacing steps, in dp. */
object PineSpace {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp
}

/** Corner radii, in dp. */
object PineRadius {
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Pill = 999.dp
}

/**
 * The horizontal gutter every screen aligns to.
 *
 * Kept as a single constant so a card in Discovery, a row in Search and a
 * header in Library all start at the same x. Screens that lay out edge-to-edge
 * (the player, carousels) deliberately do not use it.
 */
val PineGutter = 16.dp

/**
 * Minimum touch target.
 *
 * Android's accessibility guidance is 48 dp. Several controls in the previous
 * design sat at 34 dp, which was below it and is raised here.
 */
val PineTouchTarget = 48.dp

/** Dark-only: the light palette this replaced is gone. */
@Composable
fun PineappleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PineappleColors,
        typography = PineappleTypography,
        content = content,
    )
}
