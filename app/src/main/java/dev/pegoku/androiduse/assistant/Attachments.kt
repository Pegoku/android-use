package dev.pegoku.androiduse.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A picture or file attached to a message: a name and preview for the chat, and the content
 * parts the model gets. [parts] is empty for attachments of a chat reopened from disk.
 */
class Attachment(
    val name: String,
    val mime: String,
    val thumbnail: Bitmap?,
    val parts: List<JsonElement> = emptyList(),
) {
    val isImage get() = mime.startsWith("image/")
}

/** Reads attachments into something the model can take: images as images, PDFs as page images, text as text. */
object Attachments {
    private const val MAX_IMAGE_SIDE = 1600
    private const val MAX_PDF_PAGES = 8
    private const val MAX_TEXT_CHARS = 60_000
    private const val MAX_TEXT_BYTES = 512 * 1024

    /** Loads [uri]; does file and bitmap work, so call it off the main thread. */
    fun load(context: Context, uri: Uri): Attachment {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        val mime = resolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val sizeText = if (size >= 0) ", ${Formatter.formatShortFileSize(context, size)}" else ""
        return when {
            mime.startsWith("image/") -> {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_IMAGE_SIDE) {
                        val scale = MAX_IMAGE_SIDE / longest.toFloat()
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }
                Attachment(name, mime, thumbnail(bitmap), listOf(text("[Attached image: $name]"), image(bitmap)))
            }
            mime == "application/pdf" -> pdf(context, uri, name, sizeText)
            isText(mime, name) -> {
                val bytes = resolver.openInputStream(uri)!!.use { input ->
                    val buffer = ByteArray(MAX_TEXT_BYTES)
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
                val content = bytes.decodeToString()
                val clipped = content.length > MAX_TEXT_CHARS || bytes.size == MAX_TEXT_BYTES
                Attachment(name, mime, null, listOf(text(buildString {
                    append("[Attached file: $name ($mime$sizeText)")
                    append(if (clipped) "; only the beginning is included]\n" else "]\n")
                    append("```\n").append(content.take(MAX_TEXT_CHARS)).append("\n```")
                })))
            }
            else -> Attachment(name, mime, null, listOf(text("[Attached file: $name ($mime$sizeText). Its contents can't be read here.]")))
        }
    }

    /** Renders the first pages of a PDF, since the model reads pages as images. */
    private fun pdf(context: Context, uri: Uri, name: String, sizeText: String): Attachment {
        // PdfRenderer needs a seekable file, which not every provider gives; fall back to a copy in the cache.
        val copy = File(context.cacheDir, "attachment-${System.nanoTime()}.pdf")
        val renderer = runCatching { PdfRenderer(context.contentResolver.openFileDescriptor(uri, "r")!!) }.getOrElse {
            context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            PdfRenderer(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
        }
        try {
            renderer.use {
                val pages = (0 until minOf(renderer.pageCount, MAX_PDF_PAGES)).map { index ->
                    renderer.openPage(index).use { page ->
                        val width = 1240
                        val height = (page.height * width / page.width.toFloat()).toInt().coerceIn(1, 4 * width)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
                val header = "[Attached PDF: $name (${renderer.pageCount} pages$sizeText)" +
                    if (renderer.pageCount > pages.size) "; the first ${pages.size} pages follow as images]" else "; its pages follow as images]"
                return Attachment(name, "application/pdf", pages.firstOrNull()?.let(::thumbnail), listOf(text(header)) + pages.map(::image))
            }
        } finally {
            copy.delete()
        }
    }

    private val TEXT_TYPES = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-sh", "application/yaml",
        "application/x-yaml", "application/toml", "application/sql", "application/x-subrip", "application/csv",
    )
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "yaml", "yml", "toml", "ini", "conf", "cfg", "log",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "rs", "go", "rb", "sh", "sql", "srt", "vtt", "ics", "vcf",
    )

    private fun isText(mime: String, name: String) =
        mime.startsWith("text/") || mime in TEXT_TYPES || mime.endsWith("+json") || mime.endsWith("+xml") ||
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun text(value: String) = buildJsonObject { put("type", "input_text"); put("text", value) }

    private fun image(bitmap: Bitmap): JsonElement {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return buildJsonObject {
            put("type", "input_image")
            put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            put("detail", "high")
        }
    }

    private fun thumbnail(bitmap: Bitmap): Bitmap {
        val height = 192
        val width = (bitmap.width * height / bitmap.height.toFloat()).toInt().coerceIn(1, 4 * height)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}

/** What's picked for the next message. Each file loads in the background as it is added. */
@Stable
class PendingAttachments(private val context: Context, private val scope: CoroutineScope) {
    val items = mutableStateListOf<Attachment>()
    var loading by mutableIntStateOf(0)
        private set

    fun add(uris: List<Uri>) {
        uris.forEach { uri ->
            loading++
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Attachments.load(context, uri) } }
                    .onSuccess { items += it }
                    .onFailure { android.widget.Toast.makeText(context, "Couldn't attach that file: ${it.message}", android.widget.Toast.LENGTH_SHORT).show() }
                loading--
            }
        }
    }

    fun remove(attachment: Attachment) {
        items.remove(attachment)
    }

    /** Hands over everything attached and clears the composer. */
    fun take(): List<Attachment> = items.toList().also { items.clear() }
}

@Composable
fun rememberPendingAttachments(): PendingAttachments {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember { PendingAttachments(context, scope) }
}
