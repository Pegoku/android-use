package dev.pegoku.androiduse.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The identity pieces: the touch mark (a dot inside a ring, like Android's "show taps" marker),
 * the wordmark, and the small labels and borders every screen shares.
 */

/**
 * The agent's finger. Still, it is the logo. [active], the ring ripples outward again and again
 * like a tap landing: the one motion that means "working" everywhere in the app.
 */
@Composable
fun TouchMark(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = MaterialTheme.colorScheme.tertiary, active: Boolean = false) {
    if (!active) {
        Canvas(modifier.size(size)) {
            val unit = this.size.minDimension
            drawCircle(color, radius = unit * 0.19f)
            drawCircle(color, radius = unit * 0.40f, style = Stroke(unit * 0.085f))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "touch")
    val ripple by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing)),
        label = "ripple",
    )
    val press by transition.animateFloat(
        initialValue = 1f, targetValue = 0.82f,
        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "press",
    )
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension
        drawCircle(color, radius = unit * 0.19f * press)
        // Two rings half a cycle apart, each growing from the dot and fading as it goes.
        for (offset in listOf(0f, 0.5f)) {
            val t = (ripple + offset) % 1f
            drawCircle(
                color.copy(alpha = color.alpha * (1f - t)),
                radius = unit * (0.19f + 0.27f * t),
                style = Stroke(unit * 0.085f * (1f - 0.5f * t)),
            )
        }
    }
}

/** The mark and the name, as the header of the app and the drawer. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, active: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TouchMark(size = 22.dp, active = active)
        Spacer(Modifier.width(8.dp))
        Text("Android Use", style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A small mono label in capitals: names a section or says what state a card is in. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

/** The hairline around a card. */
@Composable
fun hairline(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.hairline)
