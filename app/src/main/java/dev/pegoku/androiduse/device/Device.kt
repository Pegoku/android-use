package dev.pegoku.androiduse.device

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import android.view.KeyEvent
import dev.pegoku.androiduse.shell.ShellResult
import dev.pegoku.androiduse.shell.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /**
     * The tree as it is now. Starts as the observed tree and is refreshed between batched actions,
     * so element ids the model saw can be mapped onto a screen that has shifted slightly.
     */
    private var currentTree: UiTree? = null

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

    /** Waits for the UI to stop changing after an action, instead of sleeping a fixed time. */
    suspend fun settle(idleMs: Long = 300, timeoutMs: Long = 2_500) {
        val idle = runCatching { ShizukuShell.call { waitForIdle(idleMs, timeoutMs) } }.getOrDefault(false)
        if (!idle) delay(idleMs)
        awaitStableLayout()
    }

    /**
     * Accessibility goes idle before animations finish, so also wait until two consecutive dumps
     * have the same layout. Otherwise the model sees a half-transitioned screen and repeats taps.
     */
    private suspend fun awaitStableLayout(maxMs: Long = 1_500) {
        val start = System.currentTimeMillis()
        refreshTree()
        var previous = currentTree?.signature()
        while (System.currentTimeMillis() - start < maxMs) {
            delay(150)
            refreshTree()
            val signature = currentTree?.signature()
            if (signature == previous) return
            previous = signature
        }
    }

    suspend fun observe(): Observation = coroutineScope {
        val shotJob = async { screenshot() }
        val treeJob = async { runCatching { dumpUi() } }
        var full = shotJob.await()
        var treeResult = treeJob.await()
        // A window that is still appearing (popup menu, dialog) briefly reports an empty tree.
        // Give it a moment, then retake the screenshot so both describe the same state.
        var retries = 0
        while (treeResult.getOrNull()?.elements.isNullOrEmpty() && retries++ < 3) {
            delay(350)
            treeResult = runCatching { dumpUi() }
            if (!treeResult.getOrNull()?.elements.isNullOrEmpty()) {
                full.recycle()
                full = screenshot()
            }
        }
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
        val tree = treeResult.getOrNull()
        currentTree = tree
        Observation(shot, jpeg, scale, tree, treeResult.exceptionOrNull()?.message).also { lastObservation = it }
    }

    val foregroundPackage: String? get() = currentTree?.packageName

    /** After launching something, waits (up to [maxMs]) for another app to come to the front, then for it to settle. */
    suspend fun awaitForegroundChange(previous: String?, maxMs: Long = 5_000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            refreshTree()
            if (foregroundPackage != null && foregroundPackage != previous) break
            delay(250)
        }
        settle(400, 3_000)
    }

    /** Polls the UI tree until it stops changing (at least ~1s), up to [maxMs]. */
    suspend fun waitForStable(maxMs: Long): Long {
        val start = System.currentTimeMillis()
        var last = currentTree?.signature()
        var stablePolls = 0
        while (System.currentTimeMillis() - start < maxMs) {
            delay(400)
            refreshTree()
            val signature = currentTree?.signature()
            if (signature == last) {
                if (++stablePolls >= 2 && System.currentTimeMillis() - start >= 1_000) break
            } else {
                stablePolls = 0
                last = signature
            }
        }
        return System.currentTimeMillis() - start
    }

    /** Re-reads the UI tree without a screenshot (fast), used between batched actions. */
    suspend fun refreshTree() {
        currentTree = runCatching { dumpUi() }.getOrNull() ?: currentTree
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
        if (screenWidth == 0) {
            shell("wm size").output.let { Regex("""(\d+)x(\d+)""").findAll(it).lastOrNull() }?.destructured?.let { (w, h) ->
                screenWidth = w.toInt()
                screenHeight = h.toInt()
            }
        }
        val xml = runCatching { ShizukuShell.dumpUi() }.getOrNull() ?: run {
            val path = "/data/local/tmp/android-use-ui.xml"
            val result = shell("rm -f $path; timeout 15 uiautomator dump $path >/dev/null 2>&1; cat $path", 20_000)
            result.output.substringAfter("<?xml", "").takeIf { it.isNotEmpty() }?.let { "<?xml$it" }
        } ?: error("UI hierarchy unavailable (screen may be animating or secure)")
        return UiTree.parse(xml, screenWidth, screenHeight)
    }

    private fun toDevice(x: Number, y: Number): Pair<Int, Int> {
        val scale = lastObservation?.scale ?: 1f
        return (x.toFloat() / scale).roundToInt() to (y.toFloat() / scale).roundToInt()
    }

    /** Resolves an id from the screen the model saw to the matching element on the current screen. */
    fun element(id: Int): UiElement {
        val seen = lastObservation?.tree?.get(id) ?: error("No element [$id] on the current screen")
        val now = currentTree ?: return seen
        if (now === lastObservation?.tree) return seen
        val sameKind = now.elements.filter { it.className == seen.className && it.resourceId == seen.resourceId }
        return sameKind.firstOrNull { it.bounds == seen.bounds }
            ?: sameKind.filter {
                (seen.text.isNotEmpty() && it.text == seen.text) || (seen.description.isNotEmpty() && it.description == seen.description)
            }.singleOrNull()
            // Containers resize when the keyboard opens or closes; fall back to the best overlapping one.
            ?: sameKind.filter { seen.text.isEmpty() || it.text.isEmpty() }
                .map { it to overlap(it.bounds, seen.bounds) }
                .filter { it.second > 0.5f }
                .maxByOrNull { it.second }?.first
            ?: error("Element [$id] is no longer on screen; the screen changed")
    }

    /** Intersection area relative to the smaller rectangle. */
    private fun overlap(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val smaller = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return i.width() * i.height().toFloat() / smaller
    }

    suspend fun tapElement(id: Int, long: Boolean = false): String {
        val b = element(id).bounds
        return tapDevice(b.centerX(), b.centerY(), long)
    }

    suspend fun tapPoint(x: Number, y: Number, long: Boolean = false): String {
        val (dx, dy) = toDevice(x, y)
        return tapDevice(dx, dy, long)
    }

    private suspend fun tapDevice(x: Int, y: Int, long: Boolean): String {
        val verb = if (long) "Long-pressed" else "Tapped"
        if (ShizukuShell.call { tap(x, y, if (long) 700L else 0L) }) return verb
        val cmd = if (long) "input swipe $x $y $x $y 700" else "input tap $x $y"
        return shell(cmd).describe(verb)
    }

    private suspend fun swipeDevice(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val duration = durationMs.coerceIn(50, 5000)
        if (ShizukuShell.call { swipe(x1, y1, x2, y2, duration.toLong()) }) return true
        return shell("input swipe $x1 $y1 $x2 $y2 $duration").ok
    }

    suspend fun swipe(x1: Number, y1: Number, x2: Number, y2: Number, durationMs: Int): String {
        val (a, b) = toDevice(x1, y1)
        val (c, d) = toDevice(x2, y2)
        return if (swipeDevice(a, b, c, d, durationMs)) "Swiped" else "Swipe failed"
    }

    private fun scrollArea(elementId: Int?): Rect =
        elementId?.let { element(it).bounds }
            ?: Rect(0, (screenHeight * 0.15).toInt(), screenWidth, (screenHeight * 0.85).toInt())

    /** One swipe moving the content in [direction]; [fraction] of the area is travelled. */
    private suspend fun swipeArea(area: Rect, direction: String, fraction: Float, durationMs: Int): Boolean {
        val cx = area.centerX()
        val cy = area.centerY()
        val dy = (area.height() * fraction / 2).toInt()
        val dx = (area.width() * fraction / 2).toInt()
        // Direction is where the content moves into view, so the finger travels the opposite way.
        return when (direction) {
            "down" -> swipeDevice(cx, cy + dy, cx, cy - dy, durationMs)
            "up" -> swipeDevice(cx, cy - dy, cx, cy + dy, durationMs)
            "right" -> swipeDevice(cx + dx, cy, cx - dx, cy, durationMs)
            "left" -> swipeDevice(cx - dx, cy, cx + dx, cy, durationMs)
            else -> error("Unknown scroll direction: $direction")
        }
    }

    suspend fun scroll(direction: String, elementId: Int?, pages: Int): String {
        val area = scrollArea(elementId)
        val count = pages.coerceIn(1, 10)
        repeat(count) { i ->
            if (!swipeArea(area, direction, 0.7f, 300)) return "Scroll failed"
            if (i < count - 1) settle(150, 1_500)
        }
        return "Scrolled $direction" + if (count > 1) " $count pages" else ""
    }

    /**
     * Scrolls until an element containing [query] is fully visible, re-reading only the UI tree
     * between swipes (no model round trips). Stops when the content stops moving.
     */
    suspend fun scrollTo(query: String, direction: String, elementId: Int?, maxSwipes: Int = 15): String {
        val area = scrollArea(elementId)
        val needle = query.trim().lowercase()
        fun visibleMatch(tree: UiTree?) = tree?.elements?.firstOrNull { e ->
            listOf(e.text, e.description, e.label).any { needle in it.lowercase() } && area.contains(e.bounds)
        }
        visibleMatch(currentTree)?.let { return "\"$query\" is already visible" }
        var previous = currentTree?.signature()
        for (swipes in 1..maxSwipes) {
            if (!swipeArea(area, direction, 0.6f, 250)) return "Scroll failed"
            settle(200, 1_500)
            refreshTree()
            if (visibleMatch(currentTree) != null) return "Scrolled $direction $swipes time(s); \"$query\" is now visible"
            val signature = currentTree?.signature()
            if (signature == previous) return "Reached the end after $swipes swipe(s) without finding \"$query\""
            previous = signature
        }
        return "Scrolled $direction $maxSwipes times without finding \"$query\""
    }

    suspend fun typeText(text: String, clearFirst: Boolean, elementId: Int?, submit: Boolean): String {
        val notes = mutableListOf<String>()
        if (elementId != null) {
            tapElement(elementId)
            settle(150, 1_200)
        }
        val direct = runCatching { ShizukuShell.call { setText(text, !clearFirst) } }.getOrDefault(false)
        if (!direct) {
            if (clearFirst) {
                val existing = elementId?.let { runCatching { element(it).text.length }.getOrNull() } ?: 0
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
                    pressKeyCode(KeyEvent.KEYCODE_PASTE)
                    notes += "text contained non-ASCII characters so it was pasted from the clipboard; verify it appeared"
                }
            }
        }
        if (submit) pressKeyCode(KeyEvent.KEYCODE_ENTER)
        return (listOf("Typed ${text.length} characters" + if (submit) " and pressed Enter" else "") + notes).joinToString("; ")
    }

    /** Fills several fields in one go: tap each, replace its content, move on. */
    suspend fun fillForm(fields: List<Pair<Int, String>>): String {
        val results = mutableListOf<String>()
        for ((index, field) in fields.withIndex()) {
            val (id, text) = field
            val result = runCatching { typeText(text, clearFirst = true, elementId = id, submit = false) }
                .getOrElse { "Error: ${it.message}" }
            results += "[$id] $result"
            if (result.startsWith("Error") || result.startsWith("Typing failed")) {
                results += "Stopped; ${fields.size - index - 1} field(s) not filled"
                break
            }
            if (index < fields.size - 1) refreshTree()
        }
        return results.joinToString("\n")
    }

    private suspend fun pressKeyCode(code: Int): Boolean =
        ShizukuShell.call { key(code) } || shell("input keyevent $code").ok

    suspend fun pressKey(key: String): String {
        val normalized = key.trim().lowercase()
        if (normalized == "notifications") return shell("cmd statusbar expand-notifications").describe("Opened notifications")
        if (normalized == "quick_settings") return shell("cmd statusbar expand-settings").describe("Opened quick settings")
        val name = KEYS[normalized] ?: normalized.removePrefix("keycode_").uppercase().let { "KEYCODE_$it" }
        val code = KeyEvent.keyCodeFromString(name)
        if (code == KeyEvent.KEYCODE_UNKNOWN) return "Unknown key: $key"
        return if (pressKeyCode(code)) "Pressed $key" else "Pressing $key failed"
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
