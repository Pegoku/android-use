package dev.pegoku.androiduse.device

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.pegoku.androiduse.shell.ShellResult
import dev.pegoku.androiduse.shell.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** A captured screen: downscaled JPEG for the model plus the parsed accessibility tree. */
class Observation(
    val bitmap: Bitmap,
    val jpegBase64: String,
    /** Multiply device pixels by this to get screenshot pixels. */
    val scale: Float,
    val tree: UiTree?,
    val treeError: String?,
)

data class AppInfo(val label: String, val packageName: String, val activity: String)

class Device(private val context: Context) {

    private var screenWidth = 0
    private var screenHeight = 0
    var lastObservation: Observation? = null
        private set

    suspend fun shell(command: String, timeoutMs: Long = 30_000): ShellResult = ShizukuShell.run(command, timeoutMs)

    /** Wakes the screen and dismisses an insecure keyguard. Returns an error if the device is locked. */
    suspend fun prepare(): String? {
        shell("input keyevent KEYCODE_WAKEUP")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            shell("wm dismiss-keyguard")
            delay(800)
            if (keyguard.isKeyguardLocked) return "The device is locked. Unlock it before running a task."
        }
        return null
    }

    suspend fun observe(): Observation {
        shell("input keyevent KEYCODE_WAKEUP")
        val full = screenshot()
        screenWidth = full.width
        screenHeight = full.height
        val scale = minOf(1f, MAX_SHOT_EDGE.toFloat() / max(full.width, full.height))
        val shot = if (scale < 1f) {
            Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt(), (full.height * scale).roundToInt(), true)
                .also { full.recycle() }
        } else full
        val jpeg = ByteArrayOutputStream().use { out ->
            shot.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        var treeError: String? = null
        val tree = runCatching { dumpUi() }.onFailure { treeError = it.message }.getOrNull()
        return Observation(shot, jpeg, scale, tree, treeError).also { lastObservation = it }
    }

    private suspend fun screenshot(): Bitmap = withContext(Dispatchers.Default) {
        val raw = ShizukuShell.bytes("screencap")
        decodeRaw(raw) ?: run {
            val png = ShizukuShell.bytes("screencap -p")
            BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("Could not capture the screen")
        }
    }

    /** Parses `screencap` raw output: little-endian width, height, format[, colorspace] then RGBA pixels. */
    private fun decodeRaw(raw: ByteArray): Bitmap? {
        if (raw.size < 16) return null
        val header = ByteBuffer.wrap(raw, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        val format = header.int
        if (width <= 0 || height <= 0 || format != 1) return null
        val pixels = width.toLong() * height * 4
        val offset = raw.size - pixels
        if (offset !in 12..16) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(raw, offset.toInt(), pixels.toInt()))
        }
    }

    private suspend fun dumpUi(): UiTree {
        val path = "/data/local/tmp/android-use-ui.xml"
        val result = shell("rm -f $path; timeout 15 uiautomator dump $path >/dev/null 2>&1; cat $path", 20_000)
        val xml = result.output.substringAfter("<?xml", "")
        if (xml.isEmpty()) error("UI hierarchy unavailable (screen may be animating or secure)")
        return UiTree.parse("<?xml$xml", screenWidth, screenHeight)
    }

    private fun toDevice(x: Number, y: Number): Pair<Int, Int> {
        val scale = lastObservation?.scale ?: 1f
        return (x.toFloat() / scale).roundToInt() to (y.toFloat() / scale).roundToInt()
    }

    fun element(id: Int): UiElement =
        lastObservation?.tree?.get(id) ?: error("No element [$id] on the current screen")

    suspend fun tapElement(id: Int, long: Boolean = false): String {
        val b = element(id).bounds
        return tapDevice(b.centerX(), b.centerY(), long)
    }

    suspend fun tapPoint(x: Number, y: Number, long: Boolean = false): String {
        val (dx, dy) = toDevice(x, y)
        return tapDevice(dx, dy, long)
    }

    private suspend fun tapDevice(x: Int, y: Int, long: Boolean): String {
        val cmd = if (long) "input swipe $x $y $x $y 700" else "input tap $x $y"
        return shell(cmd).describe(if (long) "Long-pressed" else "Tapped")
    }

    suspend fun swipe(x1: Number, y1: Number, x2: Number, y2: Number, durationMs: Int): String {
        val (a, b) = toDevice(x1, y1)
        val (c, d) = toDevice(x2, y2)
        return shell("input swipe $a $b $c $d ${durationMs.coerceIn(50, 5000)}").describe("Swiped")
    }

    suspend fun scroll(direction: String, elementId: Int?): String {
        val area = elementId?.let { element(it).bounds }
            ?: android.graphics.Rect(0, (screenHeight * 0.15).toInt(), screenWidth, (screenHeight * 0.85).toInt())
        val cx = area.centerX()
        val cy = area.centerY()
        val dy = (area.height() * 0.3).toInt()
        val dx = (area.width() * 0.3).toInt()
        // Direction is where the content moves into view, so the finger travels the opposite way.
        val cmd = when (direction) {
            "down" -> "input swipe $cx ${cy + dy} $cx ${cy - dy} 400"
            "up" -> "input swipe $cx ${cy - dy} $cx ${cy + dy} 400"
            "right" -> "input swipe ${cx + dx} $cy ${cx - dx} $cy 400"
            "left" -> "input swipe ${cx - dx} $cy ${cx + dx} $cy 400"
            else -> error("Unknown scroll direction: $direction")
        }
        return shell(cmd).describe("Scrolled $direction")
    }

    suspend fun typeText(text: String, clearFirst: Boolean, elementId: Int?, submit: Boolean): String {
        val notes = mutableListOf<String>()
        if (elementId != null) {
            tapElement(elementId)
            delay(400)
        }
        if (clearFirst) {
            val existing = elementId?.let { element(it).text.length } ?: 0
            val deletes = (existing + 10).coerceAtMost(200)
            shell("input keyevent KEYCODE_MOVE_END; input keyevent ${List(deletes) { "67" }.joinToString(" ")}")
        }
        if (text.isNotEmpty()) {
            if (text.all { it.code in 32..126 } && '%' !in text) {
                val result = shell("input text ${quote(text.replace(" ", "%s"))}", 60_000)
                if (!result.ok) return "Typing failed: ${result.output.take(300)}"
            } else {
                withContext(Dispatchers.Main) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("android-use", text))
                }
                delay(200)
                shell("input keyevent KEYCODE_PASTE")
                notes += "text contained non-ASCII characters so it was pasted from the clipboard; verify it appeared"
            }
        }
        if (submit) shell("input keyevent KEYCODE_ENTER")
        return (listOf("Typed ${text.length} characters" + if (submit) " and pressed Enter" else "") + notes).joinToString("; ")
    }

    suspend fun pressKey(key: String): String {
        val normalized = key.trim().lowercase()
        if (normalized == "notifications") return shell("cmd statusbar expand-notifications").describe("Opened notifications")
        if (normalized == "quick_settings") return shell("cmd statusbar expand-settings").describe("Opened quick settings")
        val code = KEYS[normalized] ?: normalized.removePrefix("keycode_").uppercase().let { "KEYCODE_$it" }
        return shell("input keyevent $code").describe("Pressed $key")
    }

    fun installedApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name)
        }.distinctBy { it.packageName to it.activity }.sortedBy { it.label.lowercase() }
    }

    fun findApp(query: String): AppInfo? {
        val apps = installedApps()
        val q = query.trim().lowercase()
        return apps.firstOrNull { it.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(q) }
    }

    suspend fun launchApp(query: String): String {
        val app = findApp(query) ?: return "No installed app matches \"$query\". Use list_apps to see what is installed."
        val result = shell("am start -n ${quote("${app.packageName}/${app.activity}")} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000")
        return if (result.ok && "Error" !in result.output) "Launched ${app.label} (${app.packageName})"
        else "Failed to launch ${app.label}: ${result.output.take(300)}"
    }

    suspend fun openUri(uri: String, packageName: String?): String {
        val pkg = packageName?.takeIf { it.isNotBlank() }?.let { " -p ${quote(it)}" }.orEmpty()
        val result = shell("am start -a android.intent.action.VIEW -d ${quote(uri)}$pkg")
        return if (result.ok && "Error" !in result.output) "Opened $uri" else "Failed to open $uri: ${result.output.take(300)}"
    }

    suspend fun composeEmail(to: String, subject: String, body: String, packageName: String?): String {
        val uri = "mailto:" + Uri.encode(to, "@,") + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body)
        val pkg = packageName?.takeIf { it.isNotBlank() }?.let { " -p ${quote(it)}" }.orEmpty()
        val result = shell("am start -a android.intent.action.SENDTO -d ${quote(uri)}$pkg")
        return if (result.ok && "Error" !in result.output) {
            "Opened an email draft to $to. Check the draft on screen and tap Send to send it."
        } else "Failed to open email composer: ${result.output.take(300)}"
    }

    private fun ShellResult.describe(action: String) =
        if (ok) action else "$action failed (exit $exitCode): ${output.take(300)}"

    companion object {
        private const val MAX_SHOT_EDGE = 1280

        private val KEYS = mapOf(
            "back" to "KEYCODE_BACK",
            "home" to "KEYCODE_HOME",
            "enter" to "KEYCODE_ENTER",
            "recents" to "KEYCODE_APP_SWITCH",
            "app_switch" to "KEYCODE_APP_SWITCH",
            "delete" to "KEYCODE_DEL",
            "backspace" to "KEYCODE_DEL",
            "search" to "KEYCODE_SEARCH",
            "tab" to "KEYCODE_TAB",
            "escape" to "KEYCODE_ESCAPE",
            "space" to "KEYCODE_SPACE",
            "page_up" to "KEYCODE_PAGE_UP",
            "page_down" to "KEYCODE_PAGE_DOWN",
            "volume_up" to "KEYCODE_VOLUME_UP",
            "volume_down" to "KEYCODE_VOLUME_DOWN",
            "menu" to "KEYCODE_MENU",
        )

        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
