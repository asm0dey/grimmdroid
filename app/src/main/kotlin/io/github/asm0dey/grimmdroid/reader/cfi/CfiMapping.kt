/*
 * Grimmdroid - Android client for the Grimmory book server
 * Copyright (C) 2026 Grimmdroid contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.asm0dey.grimmdroid.reader.cfi

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.Selector

/**
 * A Readium-style position: resource [href] (zip path), the target element, and [progression] —
 * the fraction (0..1) of the resource's body text characters before the target point.
 */
data class PositionTarget(
    val href: String,
    val cssSelector: String?,
    val progression: Double,
)

/**
 * Lands on the deepest element the CFI's element steps reach. A text-node (odd) step or a step past
 * the end stops the walk there; the offset only counts when the text step is the last one.
 * Null only when the spine step is out of range.
 */
fun EpubArchive.resolve(cfi: Cfi): PositionTarget? {
    val href = spine.getOrNull(cfi.spineIndex) ?: return null
    val doc = document(href)
    val root = doc?.firstElementChild()
    if (root == null || cfi.path.isEmpty()) return PositionTarget(href, null, 0.0)
    var el: Element = root
    var inner = 0
    for ((i, step) in cfi.path.withIndex()) {
        if (step % 2 == 0) {
            el = el.children().getOrNull(step / 2 - 1) ?: break
        } else {
            val nodes = el.childNodes().zip(el.childNodes().gapsOf())
            val before = nodes.filter { it.second < step / 2 }.sumOf { textLength(it.first) }
            val gapLength = nodes.filter { it.first !is Element && it.second == step / 2 }.sumOf { textLength(it.first) }
            val offset = if (i == cfi.path.lastIndex) (cfi.offset ?: 0).coerceIn(0, gapLength) else 0
            inner = before + offset
            break
        }
    }
    val body = bodyOf(root)
    val total = textLength(body)
    // html, head and anything outside body sit before the body text
    val inBody = el === body || body in el.parents()
    val before = if (inBody) textLength(body, stop = el) + inner else 0
    val progression = if (total == 0) 0.0 else (before.toDouble() / total).coerceIn(0.0, 1.0)
    return PositionTarget(href, selectorOf(el), progression)
}

/**
 * CFI for [cssSelector] in [href] when it parses and matches (any selector, Readium's included);
 * otherwise for the character at [progression]. Null when [href] isn't in the spine.
 */
fun EpubArchive.cfiFor(
    href: String,
    cssSelector: String?,
    progression: Double,
): String? {
    val index = spineIndexOf(href)
    if (index < 0) return null
    val spineStep = (index + 1) * 2
    val root = document(href)?.firstElementChild() ?: return Cfi(spineStep, emptyList(), null).format()
    val selected =
        cssSelector?.takeIf { it.isNotBlank() }?.let {
            try {
                root.ownerDocument()?.selectFirst(it)
            } catch (_: Selector.SelectorParseException) {
                null
            } catch (_: IllegalArgumentException) {
                null // e.g. a bad regex inside :matches()
            }
        }
    if (selected != null) return Cfi(spineStep, pathOf(selected), null).format()
    return cfiAt(spineStep, root, progression)
}

private fun cfiAt(
    spineStep: Int,
    root: Element,
    progression: Double,
): String {
    val body = bodyOf(root)
    val texts = textNodes(body)
    val total = texts.sumOf { it.wholeText.length }
    if (total == 0) return Cfi(spineStep, pathOf(body), null).format()
    val target = (progression.coerceIn(0.0, 1.0) * total).toInt().coerceAtMost(total - 1)
    var start = 0
    var i = 0
    while (start + texts[i].wholeText.length <= target) start += texts[i++].wholeText.length
    val node = texts[i]
    val parent = node.parent() as Element
    val siblings = parent.childNodes()
    val gaps = siblings.gapsOf()
    val gap = gaps[node.siblingIndex()]
    // a gap may hold several text nodes; the offset counts from the gap's start
    val gapBefore =
        siblings
            .zip(gaps)
            .take(node.siblingIndex())
            .filter {
                it.first !is Element && it.second == gap
            }.sumOf { textLength(it.first) }
    return Cfi(spineStep, pathOf(parent) + (gap * 2 + 1), gapBefore + target - start).format()
}

/** `#id` when the id is unique and a plain CSS identifier, else `:root > :nth-child(i) > …`. */
internal fun selectorOf(el: Element): String {
    val id = el.id()
    val doc: Document? = el.ownerDocument()
    if (CSS_IDENT.matches(id) && doc != null && doc.getElementsByAttributeValue("id", id).size == 1) return "#$id"
    return el.ancestorsBelowRoot().joinToString("", prefix = ":root") { " > :nth-child(${it.elementSiblingIndex() + 1})" }
}

private val CSS_IDENT = Regex("-?[_a-zA-Z][_a-zA-Z0-9-]*")

private fun Element.ancestorsBelowRoot(): List<Element> =
    (listOf(this) + parents())
        .filter {
            it.parent() is Element &&
                it.parent() !is Document
        }.reversed()

private fun pathOf(el: Element): List<Int> = el.ancestorsBelowRoot().map { (it.elementSiblingIndex() + 1) * 2 }

/**
 * For each child node, how many element siblings precede it: text in gap k has CFI step 2k+1,
 * so a node with value < k precedes gap k.
 */
private fun List<Node>.gapsOf(): List<Int> {
    var k = 0
    return map { if (it is Element) k++ else k }
}

private fun bodyOf(root: Element): Element = root.children().firstOrNull { it.normalName() == "body" } ?: root

private fun textNodes(root: Node): List<TextNode> =
    buildList {
        fun walk(n: Node) {
            if (n is TextNode) add(n) else n.childNodes().forEach(::walk)
        }
        walk(root)
    }

/** Text characters under [root], or only those before [stop] in document order. */
private fun textLength(
    root: Node,
    stop: Node? = null,
): Int {
    var n = 0
    var done = false

    fun walk(node: Node) {
        if (done) return
        if (node === stop) {
            done = true
            return
        }
        if (node is TextNode) n += node.wholeText.length
        node.childNodes().forEach(::walk)
    }
    walk(root)
    return n
}
