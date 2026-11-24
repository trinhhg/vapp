package dev.trinhhg.vapp.engine.js.api

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import dev.trinhhg.vapp.engine.js.JsValueBridge

/**
 * VBook `Html` API wrapper over Ksoup (`Html.parse(text)`).
 */
class HtmlApi {
    fun parse(text: String?): JSElement {
        val doc = Ksoup.parse(text ?: "")
        return JSElement(doc)
    }

    fun parse(
        text: String?,
        baseUri: String?,
    ): JSElement {
        val doc = Ksoup.parse(text ?: "", baseUri ?: "")
        return JSElement(doc)
    }
}

class JSElement(
    val element: Element?,
) {
    fun select(selector: String?): JSElements {
        val el = element
        if (el == null || selector.isNullOrBlank()) return JSElements()
        return runCatching { JSElements(el.select(selector)) }.getOrDefault(JSElements())
    }

    fun text(): String = element?.text() ?: ""

    fun ownText(): String = element?.ownText() ?: ""

    fun html(): String = element?.html() ?: ""

    fun outerHtml(): String = element?.outerHtml() ?: ""

    fun attr(key: String?): String {
        if (element == null || key.isNullOrBlank()) return ""
        return element.attr(key)
    }

    fun attr(
        key: String?,
        value: String?,
    ): JSElement {
        if (element != null && !key.isNullOrBlank()) {
            element.attr(key, value ?: "")
        }
        return this
    }

    fun attributes(): Any {
        val pairs = element?.attributes()?.map { it.key to (it.value as Any?) } ?: emptyList()
        return JsValueBridge.objectOf(pairs)
    }

    fun hasClass(className: String?): Boolean {
        if (element == null || className.isNullOrBlank()) return false
        return element.hasClass(className)
    }

    fun tagName(): String = element?.tagName() ?: ""

    fun tagName(newTagName: String?): JSElement {
        if (element != null && !newTagName.isNullOrBlank()) {
            element.tagName(newTagName)
        }
        return this
    }

    fun id(): String = element?.id() ?: ""

    fun className(): String = element?.className() ?: ""

    fun first(): JSElement = JSElement(element?.firstElementChild())

    fun last(): JSElement = JSElement(element?.lastElementChild())

    fun children(): JSElements = if (element == null) JSElements() else JSElements(Elements(element.children()))

    fun parent(): JSElement = JSElement(element?.parent())

    fun next(): JSElement = JSElement(element?.nextElementSibling())

    fun prev(): JSElement = JSElement(element?.previousElementSibling())

    fun isEmpty(): Boolean = element == null

    fun childrenSize(): Int = element?.children()?.size ?: 0

    fun remove() {
        element?.remove()
    }

    fun remove(selector: String?): JSElement {
        if (element != null && !selector.isNullOrBlank()) {
            element.select(selector).forEach { it.remove() }
        }
        return this
    }

    fun addClass(className: String?): JSElement {
        if (element != null && !className.isNullOrBlank()) element.addClass(className)
        return this
    }

    fun removeClass(className: String?): JSElement {
        if (element != null && !className.isNullOrBlank()) element.removeClass(className)
        return this
    }

    fun toggleClass(className: String?): JSElement {
        if (element != null && !className.isNullOrBlank()) element.toggleClass(className)
        return this
    }

    override fun toString(): String = html()
}

class JSElements(
    val elements: Elements = Elements(arrayListOf()),
) {
    @JvmField
    val length: Int = elements.size

    fun size(): Int = elements.size

    fun isEmpty(): Boolean = elements.isEmpty()

    fun select(selector: String?): JSElements {
        if (selector.isNullOrBlank()) return JSElements()
        return runCatching { JSElements(elements.select(selector)) }.getOrDefault(JSElements())
    }

    fun text(): String = elements.text()

    fun html(): String = elements.html()

    fun outerHtml(): String = elements.outerHtml()

    fun get(index: Int): JSElement = if (index in 0 until elements.size) JSElement(elements[index]) else JSElement(null)

    fun eq(index: Int): JSElement = get(index)

    fun first(): JSElement = JSElement(elements.firstOrNull())

    fun last(): JSElement = JSElement(elements.lastOrNull())

    fun attr(key: String?): String {
        if (key.isNullOrBlank()) return ""
        for (el in elements) {
            val v = el.attr(key)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    fun tagName(newTagName: String?): JSElements {
        if (!newTagName.isNullOrBlank()) {
            elements.forEach { it.tagName(newTagName) }
        }
        return this
    }

    fun forEach(callback: Any?) {
        if (JsValueBridge.isFunction(callback)) {
            for (i in 0 until elements.size) {
                JsValueBridge.call(callback, listOf(JSElement(elements[i]), i))
            }
        }
    }

    fun each(callback: Any?) {
        if (JsValueBridge.isFunction(callback)) {
            for (i in 0 until elements.size) {
                JsValueBridge.call(callback, listOf(i, JSElement(elements[i])))
            }
        }
    }

    fun map(callback: Any?): Any {
        val mapped = ArrayList<Any?>(elements.size)
        if (JsValueBridge.isFunction(callback)) {
            for (i in 0 until elements.size) {
                mapped.add(JsValueBridge.call(callback, listOf(JSElement(elements[i]), i)))
            }
        }
        return JsValueBridge.arrayOf(mapped)
    }

    fun filter(predicate: Any?): JSElements {
        if (!JsValueBridge.isFunction(predicate)) return JSElements()
        val matched = Elements(arrayListOf())
        for (i in 0 until elements.size) {
            val res = JsValueBridge.call(predicate, listOf(JSElement(elements[i]), i))
            if (res == true) matched.add(elements[i])
        }
        return JSElements(matched)
    }

    fun find(selector: String?): JSElements = select(selector)

    fun children(): JSElements {
        val out = Elements(arrayListOf())
        for (el in elements) out.addAll(el.children())
        return JSElements(out)
    }

    fun parents(): JSElements {
        val out = Elements(arrayListOf())
        for (el in elements) {
            el.parent()?.let { p -> if (!out.contains(p)) out.add(p) }
        }
        return JSElements(out)
    }

    fun next(): JSElements {
        val out = Elements(arrayListOf())
        for (el in elements) el.nextElementSibling()?.let { out.add(it) }
        return JSElements(out)
    }

    fun prev(): JSElements {
        val out = Elements(arrayListOf())
        for (el in elements) el.previousElementSibling()?.let { out.add(it) }
        return JSElements(out)
    }

    fun addClass(className: String?): JSElements {
        if (!className.isNullOrBlank()) elements.forEach { it.addClass(className) }
        return this
    }

    fun removeClass(className: String?): JSElements {
        if (!className.isNullOrBlank()) elements.forEach { it.removeClass(className) }
        return this
    }

    fun remove(): JSElements {
        elements.forEach { it.remove() }
        return this
    }

    fun remove(selector: String?): JSElements {
        if (!selector.isNullOrBlank()) elements.select(selector).forEach { it.remove() }
        return this
    }

    override fun toString(): String = html()
}
