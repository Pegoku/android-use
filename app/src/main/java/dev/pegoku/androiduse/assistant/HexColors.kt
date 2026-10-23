package dev.pegoku.androiduse.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/*
 * Hex colors (#rgb, #rrggbb, #rrggbbaa) shown as the color they name: a small swatch before the
 * code in messages, and a tinted background behind it while typing.
 */

private val HexColor = Regex("""(?<![\w#&])#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![\w])""")
private const val SWATCH = "swatch:"

/** The color a hex code names, with any alpha last as in CSS. */
private fun parseHex(digits: String): Color {
    val full = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
    val value = full.toLong(16)
    return if (full.length == 8) {
        Color(red = (value shr 24 and 0xFF).toInt(), green = (value shr 16 and 0xFF).toInt(), blue = (value shr 8 and 0xFF).toInt(), alpha = (value and 0xFF).toInt())
    } else {
        Color(0xFF000000 or value)
    }
}

/** Appends [text], with a color swatch in front of each hex code (shown by [Swatched]'s inline content). */
internal fun AnnotatedString.Builder.appendWithSwatches(text: String) {
    var index = 0
    for (match in HexColor.findAll(text)) {
        append(text.substring(index, match.range.first))
        appendInlineContent(SWATCH + match.groupValues[1].lowercase())
        append(match.value)
        index = match.range.last + 1
    }
    append(text.substring(index))
}

internal fun withSwatches(text: String): AnnotatedString = buildAnnotatedString { appendWithSwatches(text) }

/** The swatches [appendWithSwatches] placed in [text], for the inlineContent of a Text. */
@Composable
internal fun swatches(text: AnnotatedString): Map<String, InlineTextContent> {
    val outline = MaterialTheme.colorScheme.outline
    return remember(text, outline) {
        text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, text.length)
            .map { it.item }
            .filter { it.startsWith(SWATCH) }
            .associateWith { id ->
                val color = parseHex(id.removePrefix(SWATCH))
                InlineTextContent(Placeholder(1.1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Box(
                        Modifier
                            .padding(end = 3.dp, top = 1.dp, bottom = 1.dp)
                            .fillMaxSize()
                            .background(color, RoundedCornerShape(3.dp))
                            .border(1.dp, outline.copy(alpha = 0.5f), RoundedCornerShape(3.dp)),
                    )
                }
            }
    }
}

/** Tints each hex code in a text field with its own color, keeping it readable on top. */
internal object HexColorHighlight : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (!text.text.contains('#')) return TransformedText(text, OffsetMapping.Identity)
        val highlighted = buildAnnotatedString {
            append(text)
            for (match in HexColor.findAll(text.text)) {
                val color = parseHex(match.groupValues[1])
                // A mostly transparent color barely shows, so the usual text color stays readable on it.
                val readable = if (color.alpha < 0.5f) Color.Unspecified else if (color.luminance() > 0.4f) Color.Black else Color.White
                addStyle(SpanStyle(background = color, color = readable), match.range.first, match.range.last + 1)
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}
