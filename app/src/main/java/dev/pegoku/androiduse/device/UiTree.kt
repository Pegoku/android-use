package dev.pegoku.androiduse.device

import android.graphics.Rect
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class UiElement(
    val id: Int,
    val className: String,
    val text: String,
    val description: String,
    val resourceId: String,
    val label: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
)

class UiTree(val packageName: String?, val elements: List<UiElement>) {

    operator fun get(id: Int): UiElement? = elements.firstOrNull { it.id == id }

    /** Cheap fingerprint of what is on screen, used to detect that scrolling made no progress. */
    fun signature(): Int = elements.fold(packageName.hashCode()) { acc, e -> 31 * acc + (e.text + e.description + e.bounds).hashCode() }

    /** Compact one-line-per-element listing, with coordinates scaled into screenshot space. */
    fun describe(scale: Float): String = buildString {
        for (e in elements) {
            append('[').append(e.id).append("] ").append(e.className)
            if (e.text.isNotEmpty()) append(" \"").append(e.text.clip(80)).append('"')
            if (e.description.isNotEmpty() && e.description != e.text) append(" desc=\"").append(e.description.clip(80)).append('"')
            if (e.text.isEmpty() && e.description.isEmpty() && e.label.isNotEmpty()) append(" label=\"").append(e.label.clip(80)).append('"')
            if (e.resourceId.isNotEmpty()) append(" id=").append(e.resourceId)
            val flags = buildList {
                if (e.clickable) add("click")
                if (e.longClickable) add("longclick")
                if (e.scrollable) add("scroll")
                if (e.editable) add("edit")
                if (e.checkable) add(if (e.checked) "checked" else "unchecked")
                if (e.selected) add("selected")
                if (e.focused) add("focused")
                if (!e.enabled) add("disabled")
            }
            if (flags.isNotEmpty()) append(' ').append(flags.joinToString(","))
            val b = e.bounds
            append(" @(").append((b.centerX() * scale).toInt()).append(',').append((b.centerY() * scale).toInt()).append(')')
            append(" box=").append((b.left * scale).toInt()).append(',').append((b.top * scale).toInt())
                .append('-').append((b.right * scale).toInt()).append(',').append((b.bottom * scale).toInt())
            append('\n')
        }
    }

    companion object {
        private const val MAX_ELEMENTS = 250
        private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        private class Node(val attrs: Map<String, String>, val children: MutableList<Node> = mutableListOf())

        fun parse(xml: String, screenWidth: Int, screenHeight: Int): UiTree {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            val roots = mutableListOf<Node>()
            val stack = ArrayDeque<Node>()
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> if (parser.name == "node") {
                        val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                        val node = Node(attrs)
                        (stack.lastOrNull()?.children ?: roots).add(node)
                        stack.addLast(node)
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "node") stack.removeLastOrNull()
                    XmlPullParser.END_DOCUMENT -> break
                }
            }

            val screen = Rect(0, 0, screenWidth, screenHeight)
            val elements = mutableListOf<UiElement>()
            fun visit(node: Node) {
                val a = node.attrs
                val bounds = BOUNDS.find(a["bounds"].orEmpty())?.destructured?.let { (l, t, r, b) ->
                    Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
                } ?: Rect()
                val visible = Rect(bounds).let { it.intersect(screen) && it.width() > 1 && it.height() > 1 }
                val cls = a["class"].orEmpty().substringAfterLast('.')
                val text = a["text"].orEmpty().trim()
                val desc = a["content-desc"].orEmpty().trim()
                val clickable = a["clickable"] == "true"
                val longClickable = a["long-clickable"] == "true"
                val scrollable = a["scrollable"] == "true"
                val editable = cls.contains("EditText") || cls.contains("AutoCompleteTextView")
                val checkable = a["checkable"] == "true"
                val interesting = text.isNotEmpty() || desc.isNotEmpty() || clickable || longClickable || scrollable || editable || checkable
                if (visible && interesting && elements.size < MAX_ELEMENTS) {
                    val label = if (text.isEmpty() && desc.isEmpty()) descendantText(node).joinToString(" | ").take(120) else ""
                    elements += UiElement(
                        id = elements.size + 1,
                        className = cls,
                        text = text,
                        description = desc,
                        resourceId = a["resource-id"].orEmpty().substringAfter(":id/"),
                        label = label,
                        bounds = Rect().apply { setIntersect(bounds, screen) },
                        clickable = clickable,
                        longClickable = longClickable,
                        scrollable = scrollable,
                        editable = editable,
                        checkable = checkable,
                        checked = a["checked"] == "true",
                        selected = a["selected"] == "true",
                        focused = a["focused"] == "true",
                        enabled = a["enabled"] != "false",
                    )
                }
                node.children.forEach(::visit)
            }
            roots.forEach(::visit)
            val pkg = roots.firstNotNullOfOrNull { it.attrs["package"] }
            return UiTree(pkg, elements)
        }

        private fun descendantText(node: Node): List<String> = node.children.flatMap { child ->
            val own = listOf(child.attrs["text"], child.attrs["content-desc"]).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
            own + descendantText(child)
        }.take(6)
    }
}

private fun String.clip(max: Int) = replace('\n', ' ').let { if (it.length > max) it.take(max) + "…" else it }
