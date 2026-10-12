package dev.pegoku.androiduse.shell

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * A persistent UiAutomation connection inside the Shizuku shell process. Much faster than
 * spawning `uiautomator dump` / `input` per action, and gives a real "wait until idle".
 */
internal class Automation {
    private val automation: UiAutomation = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("")
        val thread = HandlerThread("ui-automation").apply { start() }
        val connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val automation = UiAutomation::class.java
            .getDeclaredConstructor(Looper::class.java, Class.forName("android.app.IUiAutomationConnection"))
            .newInstance(thread.looper, connection)
        UiAutomation::class.java.getDeclaredMethod("connect", Int::class.javaPrimitiveType)
            .invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        automation
    }

    fun disconnect() = runCatching { UiAutomation::class.java.getDeclaredMethod("disconnect").invoke(automation) }

    fun waitForIdle(idleMs: Long, timeoutMs: Long): Boolean =
        runCatching { automation.waitForIdle(idleMs, timeoutMs) }.isSuccess

    /**
     * Same shape as `uiautomator dump`, but covering every visible window (popup menus, dialogs and
     * the app below them) from the top down. Nodes hidden behind a higher window, such as the
     * keyboard, are left out. The active app window comes first so its package is reported.
     */
    fun dump(): String? {
        val windows = runCatching { automation.windows }.getOrDefault(emptyList())
        val out = StringBuilder(64 * 1024)
        out.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">")
        if (windows.isEmpty()) {
            appendNode(out, automation.rootInActiveWindow ?: return null, 0, 0, emptyList())
        } else {
            val screen = Rect().also { windows.forEach { w -> it.union(Rect().also(w::getBoundsInScreen)) } }
            val covered = mutableListOf<Rect>()
            val roots = mutableListOf<Pair<AccessibilityNodeInfo, List<Rect>>>()
            for (window in windows) {
                val bounds = Rect().also(window::getBoundsInScreen)
                val root = if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                ) null else window.root
                // Skip thin system bars (status / navigation) but keep e.g. the notification shade.
                val systemBar = window.type == AccessibilityWindowInfo.TYPE_SYSTEM && bounds.height() < screen.height() / 6
                if (root != null && !systemBar) roots += root to covered.toList()
                covered += bounds
            }
            // Put the app's active window first so the hierarchy's package is the foreground app.
            val activeIndex = roots.indexOfFirst { (root, _) -> root.windowId == automation.rootInActiveWindow?.windowId }
            val ordered = if (activeIndex > 0) listOf(roots[activeIndex]) + roots.filterIndexed { i, _ -> i != activeIndex } else roots
            ordered.forEachIndexed { index, (root, above) -> appendNode(out, root, index, 0, above) }
        }
        out.append("</hierarchy>")
        return out.toString()
    }

    private fun appendNode(out: StringBuilder, node: AccessibilityNodeInfo, index: Int, depth: Int, coveredBy: List<Rect>) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (coveredBy.any { it.contains(bounds) }) return
        out.append("<node index=\"").append(index).append('"')
        attr(out, "text", node.text)
        attr(out, "resource-id", node.viewIdResourceName)
        attr(out, "class", node.className)
        attr(out, "package", node.packageName)
        attr(out, "content-desc", node.contentDescription)
        out.append(" checkable=\"").append(node.isCheckable).append('"')
        out.append(" checked=\"").append(node.isChecked).append('"')
        out.append(" clickable=\"").append(node.isClickable).append('"')
        out.append(" enabled=\"").append(node.isEnabled).append('"')
        out.append(" focusable=\"").append(node.isFocusable).append('"')
        out.append(" focused=\"").append(node.isFocused).append('"')
        out.append(" scrollable=\"").append(node.isScrollable).append('"')
        out.append(" long-clickable=\"").append(node.isLongClickable).append('"')
        out.append(" password=\"").append(node.isPassword).append('"')
        out.append(" selected=\"").append(node.isSelected).append('"')
        out.append(" bounds=\"[").append(bounds.left).append(',').append(bounds.top).append("][")
            .append(bounds.right).append(',').append(bounds.bottom).append("]\">")
        if (depth < MAX_DEPTH) {
            var childIndex = 0
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isVisibleToUser) appendNode(out, child, childIndex++, depth + 1, coveredBy)
            }
        }
        out.append("</node>")
    }

    private fun attr(out: StringBuilder, name: String, value: CharSequence?) {
        out.append(' ').append(name).append("=\"")
        value?.forEach { c ->
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\n' -> out.append("&#10;")
                else -> if (c >= ' ' || c == '\t') out.append(c)
            }
        }
        out.append('"')
    }

    fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!touch(MotionEvent.ACTION_DOWN, down, down, x, y)) return false
        // A real finger stays down for a moment; zero-length taps get dropped by some views under load.
        SystemClock.sleep(if (durationMs > 0) durationMs else 40)
        return touch(MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis(), x, y)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!touch(MotionEvent.ACTION_DOWN, down, down, x1, y1)) return false
        val steps = (durationMs / 10).coerceIn(2, 200).toInt()
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            SystemClock.sleep(durationMs / steps)
            touch(MotionEvent.ACTION_MOVE, down, SystemClock.uptimeMillis(), (x1 + (x2 - x1) * f).toInt(), (y1 + (y2 - y1) * f).toInt())
        }
        return touch(MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis(), x2, y2)
    }

    private fun touch(action: Int, downTime: Long, time: Long, x: Int, y: Int): Boolean {
        val event = MotionEvent.obtain(downTime, time, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            automation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    fun key(keyCode: Int): Boolean {
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0).apply { source = InputDevice.SOURCE_KEYBOARD }
        val up = KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0).apply { source = InputDevice.SOURCE_KEYBOARD }
        return automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true)
    }

    fun setText(text: String, append: Boolean): Boolean {
        val focused = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        // A field showing only its hint reports the hint as text; don't append to that.
        val existing = focused.text?.toString().orEmpty().takeUnless { focused.isShowingHintText }.orEmpty()
        val value = if (append) existing + text else text
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        // Put the cursor at the end so a following Enter/typing behaves naturally.
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, value.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        return true
    }

    private companion object {
        const val MAX_DEPTH = 60
    }
}
