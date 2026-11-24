package dev.trinhhg.vapp.engine.js.api

import dev.trinhhg.vapp.engine.api.BrowserSession
import dev.trinhhg.vapp.engine.js.JsValueBridge
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CookiePersistence
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object UserAgent {
    private const val ANDROID_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val IOS_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1_1 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"

    fun system(): String = ANDROID_UA

    fun chrome(): String = CHROME_UA

    fun android(): String = ANDROID_UA

    fun ios(): String = IOS_UA

    fun iOS(): String = IOS_UA

    fun desktop(): String = CHROME_UA

    fun mobile(): String = ANDROID_UA
}

class JsBlob private constructor(
    private val dataBase64: String,
    @JvmField val type: String,
) {
    @Suppress("ktlint:standard:backing-property-naming")
    @JvmField
    val _isBlob: Boolean = true

    @JvmField
    val size: Int = runCatching { Base64.getDecoder().decode(dataBase64).size }.getOrDefault(0)

    fun base64(): String = dataBase64

    override fun toString(): String = dataBase64

    companion object {
        fun fromBase64(
            base64: String?,
            type: String? = "",
        ): JsBlob = JsBlob(base64 ?: "", type ?: "")
    }
}

object BlobApi {
    fun fromBase64(
        base64: String?,
        type: String?,
    ): JsBlob = JsBlob.fromBase64(base64, type)

    fun fromBase64(base64: String?): JsBlob = JsBlob.fromBase64(base64, "")
}

object ResponseApi {
    fun success(data: Any?): String = buildEnvelope(code = 0, data = data, data2 = null, hasData2 = false)

    fun success(
        data: Any?,
        data2: Any?,
    ): String = buildEnvelope(code = 0, data = data, data2 = data2, hasData2 = data2 != null)

    fun error(message: Any?): String = buildEnvelope(code = 1, data = message, data2 = null, hasData2 = false)

    private fun buildEnvelope(
        code: Int,
        data: Any?,
        data2: Any?,
        hasData2: Boolean,
    ): String {
        val normData = JsValueBridge.normalize(data)
        val normData2 = JsValueBridge.normalize(data2)
        val sb = StringBuilder()
        sb.append("{\"code\":").append(code)
        sb.append(",\"data\":").append(toJsonLiteral(normData))
        if (hasData2) {
            sb.append(",\"data2\":").append(toJsonLiteral(normData2))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun toJsonLiteral(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean, is Number -> value.toString()
            is String -> quoteJson(value)
            is Map<*, *> ->
                value.entries.joinToString(",", "{", "}") { (k, v) ->
                    "${quoteJson(k?.toString() ?: "")}:${toJsonLiteral(v)}"
                }
            is Iterable<*> -> value.joinToString(",", "[", "]") { toJsonLiteral(it) }
            else -> quoteJson(value.toString())
        }

    private fun quoteJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c.code < 32) {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

class ConsoleApi(
    private val log: EngineLogger,
) {
    fun log(vararg args: Any?) {
        val msg = args.joinToString(" ") { JsValueBridge.toJsString(it) ?: "null" }
        log.add(msg, tag = "PluginConsole")
    }

    fun warn(vararg args: Any?) {
        val msg = args.joinToString(" ") { JsValueBridge.toJsString(it) ?: "null" }
        log.warn(msg, tag = "PluginConsole")
    }

    fun error(vararg args: Any?) {
        val msg = args.joinToString(" ") { JsValueBridge.toJsString(it) ?: "null" }
        log.error(msg, tag = "PluginConsole")
    }
}

class KeyValueStorageApi {
    private val store = ConcurrentHashMap<String, String>()

    fun setItem(
        key: String?,
        value: Any?,
    ) {
        if (!key.isNullOrEmpty()) {
            store[key] = JsValueBridge.toJsString(value) ?: ""
        }
    }

    fun getItem(key: String?): String? = if (key.isNullOrEmpty()) null else store[key]

    fun removeItem(key: String?) {
        if (!key.isNullOrEmpty()) store.remove(key)
    }

    fun clear() {
        store.clear()
    }
}

class LocalCookieApi(
    private val extensionSourceUrl: String,
    private val cookieStore: CookiePersistence,
) {
    fun setCookie(value: String?) {
        if (!value.isNullOrBlank() && extensionSourceUrl.isNotBlank()) {
            cookieStore.setRawCookie(extensionSourceUrl, value)
        }
    }

    fun getCookie(): String = if (extensionSourceUrl.isBlank()) "" else cookieStore.getCookieHeader(extensionSourceUrl).orEmpty()
}

class LocalConfigApi(
    private val configValues: Map<String, String>,
) {
    fun getItem(key: String?): String? = if (key.isNullOrEmpty()) null else configValues[key]

    fun getItem(
        key: String?,
        defaultValue: String?,
    ): String? {
        if (key.isNullOrEmpty()) return defaultValue
        return configValues[key] ?: defaultValue
    }
}

/**
 * JS wrapper around platform [BrowserSession] backing `Engine.newBrowser()`.
 */
class JsBrowser(
    private val session: BrowserSession,
    private val htmlApi: HtmlApi,
) {
    fun launch(url: String): JSElement = launch(url, 30_000)

    fun launch(
        url: String,
        timeoutMs: Int,
    ): JSElement = htmlApi.parse(session.launch(url, timeoutMs.toLong()), url)

    fun launchAsync(url: String) {
        session.launchAsync(url)
    }

    fun loadHtml(
        baseUrl: String,
        html: String,
    ) {
        session.loadHtml(baseUrl, html)
    }

    fun waitUrl(
        patterns: Any?,
        timeoutMs: Int = 15_000,
    ) {
        val list = toStringList(patterns)
        session.waitUrl(list, timeoutMs.toLong())
    }

    fun block(patterns: Any?) {
        session.block(toStringList(patterns))
    }

    fun urls(): Any = JsValueBridge.arrayOf(session.urls())

    fun callJs(
        script: String,
        timeoutMs: Int = 5_000,
    ): JSElement = htmlApi.parse(session.callJs(script, timeoutMs.toLong()))

    fun getVariable(name: String): String? = session.getVariable(name)

    fun setUserAgent(userAgent: String) {
        session.setUserAgent(userAgent)
    }

    fun html(timeoutMs: Int = 2_000): JSElement = htmlApi.parse(session.html(timeoutMs.toLong()))

    fun close() {
        session.close()
    }

    private fun toStringList(value: Any?): List<String> =
        when (val norm = JsValueBridge.normalize(value)) {
            is List<*> -> norm.mapNotNull { it?.toString() }
            is String -> listOf(norm)
            else -> emptyList()
        }
}

class EngineApi(
    private val browserFactory: dev.trinhhg.vapp.engine.api.BrowserEngineFactory?,
    private val htmlApi: HtmlApi,
) {
    fun newBrowser(): JsBrowser {
        val factory =
            browserFactory
                ?: throw UnsupportedOperationException("Engine.newBrowser() requires an Android WebView BrowserEngineFactory")
        return JsBrowser(factory.createSession(), htmlApi).apply {
            setUserAgent(UserAgent.system())
        }
    }

    fun newBrowser(
        @Suppress("UNUSED_PARAMETER") context: Any?,
        @Suppress("UNUSED_PARAMETER") scope: Any?,
    ): JsBrowser = newBrowser()
}
