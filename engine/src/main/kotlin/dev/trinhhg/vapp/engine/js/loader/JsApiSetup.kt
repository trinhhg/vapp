package dev.trinhhg.vapp.engine.js.loader

import dev.trinhhg.vapp.engine.api.BrowserEngineFactory
import dev.trinhhg.vapp.engine.js.JsValueBridge
import dev.trinhhg.vapp.engine.js.api.BlobApi
import dev.trinhhg.vapp.engine.js.api.ConsoleApi
import dev.trinhhg.vapp.engine.js.api.EngineApi
import dev.trinhhg.vapp.engine.js.api.HtmlApi
import dev.trinhhg.vapp.engine.js.api.HttpApi
import dev.trinhhg.vapp.engine.js.api.HttpExecutor
import dev.trinhhg.vapp.engine.js.api.HttpRequestBuilder
import dev.trinhhg.vapp.engine.js.api.JsBlob
import dev.trinhhg.vapp.engine.js.api.KeyValueStorageApi
import dev.trinhhg.vapp.engine.js.api.LocalConfigApi
import dev.trinhhg.vapp.engine.js.api.LocalCookieApi
import dev.trinhhg.vapp.engine.js.api.ResponseApi
import dev.trinhhg.vapp.engine.js.api.UserAgent
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.engine.util.parseBaseUrl
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Configures the full VBook Extension JavaScript runtime inside a Rhino [Scriptable] scope.
 */
object JsApiSetup {
    private const val MAX_SCRIPT_BYTES = 256 * 1024

    fun setup(
        cx: Context,
        scope: Scriptable,
        httpExecutor: HttpExecutor,
        cookieStore: CookiePersistence,
        browserFactory: BrowserEngineFactory?,
        baseUrl: String?,
        targetUrl: String?,
        pluginSource: String?,
        pluginConfig: Map<String, String>,
        extraScripts: Map<String, String>,
        localStorageApi: KeyValueStorageApi,
        cacheStorageApi: KeyValueStorageApi,
        log: EngineLogger,
    ) {
        injectBaseUrl(cx, scope, baseUrl ?: parseBaseUrl(pluginSource), extraScripts)
        injectPluginConfig(cx, scope, pluginConfig)

        val htmlApi = HtmlApi()
        val httpApi = HttpApi(httpExecutor, log)
        val consoleApi = ConsoleApi(log)

        putProp(cx, scope, "Html", htmlApi)
        putProp(cx, scope, "Http", httpApi)
        putProp(cx, scope, "UserAgent", UserAgent)
        putProp(cx, scope, "Engine", EngineApi(browserFactory, htmlApi))
        putProp(cx, scope, "Response", ResponseApi)
        putProp(cx, scope, "Blob", BlobApi)
        putProp(cx, scope, "Console", consoleApi)
        putProp(cx, scope, "console", consoleApi)
        putProp(cx, scope, "Log", consoleApi)
        putProp(cx, scope, "localStorage", localStorageApi)
        putProp(cx, scope, "cacheStorage", cacheStorageApi)
        putProp(cx, scope, "localCookie", LocalCookieApi(pluginSource.orEmpty(), cookieStore))
        putProp(cx, scope, "localConfig", LocalConfigApi(pluginConfig))

        putProp(cx, scope, "fetch", createFetchFunc(httpApi, targetUrl, log))
        putProp(cx, scope, "load", createLoadFunc(extraScripts, log))
        putProp(cx, scope, "sleep", createSleepFunc())
    }

    private fun putProp(
        cx: Context,
        scope: Scriptable,
        name: String,
        value: Any?,
    ) {
        ScriptableObject.putProperty(scope, name, value)
        cx.wrapFactory.isJavaPrimitiveWrap = false
    }

    private fun injectBaseUrl(
        cx: Context,
        scope: Scriptable,
        baseUrl: String?,
        extraScripts: Map<String, String>,
    ) {
        if (extraScripts.containsKey("config.js") || baseUrl.isNullOrBlank()) return
        val escaped =
            baseUrl
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        cx.evaluateString(scope, "var BASE_URL = \"$escaped\";", "baseUrl", 1, null)
    }

    private fun injectPluginConfig(
        cx: Context,
        scope: Scriptable,
        pluginConfig: Map<String, String>,
    ) {
        pluginConfig.forEach { (key, value) ->
            if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                putProp(cx, scope, key, value)
            }
        }
    }

    private fun createFetchFunc(
        httpApi: HttpApi,
        targetUrl: String?,
        log: EngineLogger,
    ): BaseFunction =
        object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>,
            ): Any {
                val requestUrl =
                    if (args.isNotEmpty() && args[0] != null && args[0] !== Undefined.instance) {
                        Context.toString(args[0])
                    } else {
                        targetUrl.orEmpty()
                    }
                val builder: HttpRequestBuilder = httpApi.request(requestUrl)
                val opts = args.getOrNull(1) as? NativeObject
                if (opts != null) {
                    val optionKeys = listOf("method", "body", "headers", "queries", "timeout", "params")
                    val hasOptions = optionKeys.any { opts.has(it, opts) }
                    if (hasOptions) {
                        applyOptions(builder, opts)
                    } else {
                        val map = JsValueBridge.normalize(opts) as? Map<*, *>
                        if (map != null) builder.queries(map)
                    }
                }
                log.debug("fetch($requestUrl)", tag = "JsRuntime")
                return builder.executeResponse()
            }

            private fun applyOptions(
                builder: HttpRequestBuilder,
                opts: NativeObject,
            ) {
                val method = opts.get("method", opts)
                if (method is String && method.isNotBlank()) builder.method(method)

                val queries = JsValueBridge.normalize(opts.get("queries", opts))
                if (queries is Map<*, *>) builder.queries(queries)

                val params = JsValueBridge.normalize(opts.get("params", opts))
                if (params is Map<*, *>) builder.queries(params)

                val headers = JsValueBridge.normalize(opts.get("headers", opts))
                if (headers is Map<*, *>) builder.headers(headers)

                val timeout = opts.get("timeout", opts)
                if (timeout is Number) builder.timeout(timeout.toInt())

                val rawBody = opts.get("body", opts)
                if (rawBody != null && rawBody !== Undefined.instance && rawBody !== Scriptable.NOT_FOUND) {
                    val normBody = JsValueBridge.normalize(rawBody)
                    when (normBody) {
                        is JsBlob -> builder.body(normBody)
                        is Map<*, *> -> builder.form(normBody)
                        else -> builder.body(normBody?.toString() ?: "")
                    }
                }
            }

            override fun getDefaultValue(typeHint: Class<*>?): Any = "function fetch() { [native code] }"
        }

    private fun createLoadFunc(
        extraScripts: Map<String, String>,
        log: EngineLogger,
    ): BaseFunction =
        object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>,
            ): Any? {
                val fileName = JsValueBridge.toJsString(args.getOrNull(0))?.trim().orEmpty()
                if (!isValidScriptName(fileName)) {
                    log.warn("Blocked invalid load('$fileName')", tag = "JsRuntime")
                    return null
                }
                val scriptContent = extraScripts[fileName]
                if (scriptContent == null) {
                    log.debug("load('$fileName') not found in src/", tag = "JsRuntime")
                    return null
                }
                if (scriptContent.length > MAX_SCRIPT_BYTES) {
                    log.warn("load('$fileName') exceeds size cap", tag = "JsRuntime")
                    return null
                }
                return runCatching {
                    cx.evaluateString(scope, scriptContent, fileName, 1, null)
                }.onFailure { err ->
                    log.error("load('$fileName') failed", err, tag = "JsRuntime")
                }.getOrNull()
            }

            override fun getDefaultValue(typeHint: Class<*>?): Any = "function load() { [native code] }"
        }

    private fun createSleepFunc(): BaseFunction =
        object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>,
            ): Any? {
                val ms = (args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0L, 60_000L) ?: 0L
                if (ms > 0) {
                    try {
                        Thread.sleep(ms)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                return null
            }

            override fun getDefaultValue(typeHint: Class<*>?): Any = "function sleep() { [native code] }"
        }

    private fun isValidScriptName(name: String): Boolean {
        if (name.isEmpty() || !name.endsWith(".js")) return false
        if (name.contains("..") || name.contains("/") || name.contains("\\")) return false
        return !name.matches(Regex(".*[<>:\"|?*].*"))
    }
}
