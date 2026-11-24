package dev.trinhhg.vapp.engine.js.api

import dev.trinhhg.vapp.engine.api.ChallengeSolver
import dev.trinhhg.vapp.engine.js.JsValueBridge
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CharsetDecoder
import dev.trinhhg.vapp.engine.net.CookiePersistence
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class HttpSpec(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyContentType: String? = null,
    val timeoutMs: Long = 30_000L,
    val syncCookie: Boolean = true,
)

data class RawHttpResult(
    val code: Int,
    val message: String,
    val finalUrl: String,
    val headers: Map<String, String>,
    val requestHeaders: Map<String, String>,
    val body: ByteArray,
)

/**
 * Shared OkHttp executor with lenient SSL (matching VBook Android), shared cookie jar, and 429/503 retry.
 */
class HttpExecutor(
    private val cookieStore: CookiePersistence,
    private val log: EngineLogger,
    private val challengeSolver: ChallengeSolver? = null,
    private val defaultUserAgent: String = UserAgent.system(),
) {
    private val client: OkHttpClient = buildLenientClient()

    fun execute(spec: HttpSpec): RawHttpResult {
        var result = executeOnce(spec)
        var attempt = 0
        while ((result.code == 429 || result.code == 503) && attempt < 2) {
            attempt++
            Thread.sleep(500L * attempt)
            log.debug("HTTP ${result.code} retry #$attempt for ${spec.url}", tag = "Http")
            result = executeOnce(spec)
        }
        return result
    }

    private fun executeOnce(spec: HttpSpec): RawHttpResult {
        val finalHeaders = LinkedHashMap<String, String>()
        spec.headers.forEach { (k, v) -> finalHeaders[k] = v }
        if (finalHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            finalHeaders["User-Agent"] = defaultUserAgent
        }
        if (spec.syncCookie) {
            val cookieHeader = cookieStore.getCookieHeader(spec.url)
            if (!cookieHeader.isNullOrEmpty() &&
                finalHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }
            ) {
                finalHeaders["Cookie"] = cookieHeader
            }
        }

        val reqBuilder =
            try {
                Request.Builder().url(spec.url)
            } catch (e: Exception) {
                log.warn("Invalid URL: ${spec.url.take(120)}", tag = "Http")
                return RawHttpResult(
                    code = 504,
                    message = "Invalid URL: ${e.message}",
                    finalUrl = "",
                    headers = emptyMap(),
                    requestHeaders = finalHeaders,
                    body = ByteArray(0),
                )
            }

        finalHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        val reqBody =
            when {
                spec.method == "GET" || spec.method == "HEAD" -> null
                spec.body != null ->
                    spec.body.toRequestBody(
                        (spec.bodyContentType ?: "application/octet-stream").toMediaType(),
                    )
                spec.method == "POST" || spec.method == "PUT" || spec.method == "PATCH" ->
                    ByteArray(0).toRequestBody("application/octet-stream".toMediaType())
                else -> null
            }
        reqBuilder.method(spec.method, reqBody)

        val callClient =
            if (spec.timeoutMs != 30_000L && spec.timeoutMs > 0) {
                client
                    .newBuilder()
                    .connectTimeout(spec.timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(spec.timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(spec.timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
            } else {
                client
            }

        return try {
            callClient.newCall(reqBuilder.build()).execute().use { resp ->
                val lowerHeaders = LinkedHashMap<String, String>()
                for (name in resp.headers.names()) {
                    lowerHeaders[name.lowercase()] = resp.header(name) ?: ""
                }
                if (spec.syncCookie) {
                    val setCookies = resp.headers("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        cookieStore.saveSetCookieHeaders(resp.request.url.toString(), setCookies)
                    }
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                RawHttpResult(
                    code = resp.code,
                    message = resp.message,
                    finalUrl = resp.request.url.toString(),
                    headers = lowerHeaders,
                    requestHeaders = finalHeaders,
                    body = bytes,
                )
            }
        } catch (e: Exception) {
            log.warn("Request failed (${spec.method} ${spec.url}): ${e.message}", tag = "Http")
            RawHttpResult(
                code = 504,
                message = e.message ?: "Transport error",
                finalUrl = "",
                headers = emptyMap(),
                requestHeaders = finalHeaders,
                body = ByteArray(0),
            )
        }
    }

    fun trySolveChallenge(url: String): String? {
        val solver = challengeSolver ?: return null
        log.add("Cloudflare/anti-bot challenge detected; invoking ChallengeSolver for $url", tag = "Http")
        return runCatching { runBlocking { solver.solveChallenge(url) } }.getOrNull()
    }

    companion object {
        private fun buildLenientClient(): OkHttpClient {
            val trustAll =
                arrayOf<TrustManager>(
                    object : X509TrustManager {
                        override fun checkClientTrusted(
                            chain: Array<out X509Certificate>?,
                            authType: String?,
                        ) = Unit

                        override fun checkServerTrusted(
                            chain: Array<out X509Certificate>?,
                            authType: String?,
                        ) = Unit

                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    },
                )
            val sslContext =
                SSLContext.getInstance("TLS").apply {
                    init(null, trustAll, SecureRandom())
                }
            return OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }
}

/**
 * VBook `_HttpResponse` exposed to JS from `fetch(url, options)`.
 * Uses `@JvmField` primitives (`ok`, `status`, `statusText`, `url`, `headers`, `request`)
 * so `if (response.ok)` accurately reflects HTTP 2xx status in Rhino.
 */
class JsHttpResponse(
    private val raw: RawHttpResult,
    private val executor: HttpExecutor,
    private val spec: HttpSpec,
    private val log: EngineLogger,
) {
    @JvmField
    val status: Int = raw.code

    @JvmField
    val statusText: String = raw.message

    @JvmField
    val ok: Boolean = raw.code in 200..299

    @JvmField
    val url: String = raw.finalUrl

    @JvmField
    val headers: Any = JsValueBridge.objectOf(raw.headers.entries.map { it.key to (it.value as Any?) })

    @JvmField
    val request: Any =
        JsValueBridge.objectOf(
            listOf(
                "url" to spec.url,
                "headers" to
                    JsValueBridge.objectOf(
                        raw.requestHeaders.entries.map { it.key.lowercase() to (it.value as Any?) },
                    ),
            ),
        )

    fun header(key: String?): String {
        if (key.isNullOrBlank()) return ""
        return raw.headers[key.lowercase()] ?: ""
    }

    fun text(): String = text(null)

    fun text(charset: String?): String = CharsetDecoder.decode(raw.body, charset, raw.headers["content-type"])

    fun string(): String = text(null)

    fun string(charset: String?): String = text(charset)

    fun html(): JSElement = html(null)

    fun html(charset: String?): JSElement {
        var bodyText = text(charset)
        if (isCloudflareChallenge(bodyText)) {
            val solved = executor.trySolveChallenge(spec.url)
            if (!solved.isNullOrEmpty() && !isCloudflareChallenge(solved)) {
                bodyText = solved
            } else {
                val retried = executor.execute(spec)
                val retriedText = CharsetDecoder.decode(retried.body, charset, retried.headers["content-type"])
                if (retriedText.length >= 500 && !isCloudflareChallenge(retriedText)) {
                    bodyText = retriedText
                }
            }
        }
        return HtmlApi().parse(bodyText, raw.finalUrl.ifEmpty { spec.url })
    }

    fun json(): Any? {
        val rawText = text(null)
        if (rawText.isEmpty()) return null
        val trimmed = rawText.trim()
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return JsValueBridge.objectOf(listOf("chap_list" to rawText, "status" to 200))
        }
        return runCatching { JsValueBridge.parseJson(rawText) }
            .onFailure { log.warn("JSON parse error for ${spec.url}: ${it.message}", tag = "Http") }
            .getOrNull()
    }

    fun base64(): String = Base64.getEncoder().encodeToString(raw.body)

    fun blob(): JsBlob = JsBlob.fromBase64(base64(), raw.headers["content-type"] ?: "")

    fun bytes(): ByteArray = raw.body

    private fun isCloudflareChallenge(body: String): Boolean {
        if (body.length < 500) return false
        return body.contains("challenge-platform") ||
            body.contains("cf_chl_opt") ||
            body.contains("Just a moment...") ||
            body.contains("__cf_chl_jschl_tk__")
    }
}

/**
 * Legacy VBook `Http` builder (`Http.get(url)` / `Http.post(url)` / `Http.request(url)`).
 */
class HttpApi(
    private val executor: HttpExecutor,
    private val log: EngineLogger,
) {
    fun get(url: String): HttpRequestBuilder = HttpRequestBuilder(executor, log, url, "GET")

    fun post(url: String): HttpRequestBuilder = HttpRequestBuilder(executor, log, url, "POST")

    fun request(url: String): HttpRequestBuilder = HttpRequestBuilder(executor, log, url, "GET")
}

class HttpRequestBuilder(
    private val executor: HttpExecutor,
    private val log: EngineLogger,
    private var url: String,
    private var method: String = "GET",
) {
    private val headersMap = LinkedHashMap<String, String>()
    private var bodyBytes: ByteArray? = null
    private var bodyContentType: String? = null
    private var timeoutMs: Long = 30_000L
    private var syncCookie: Boolean = true
    private var cachedResponse: JsHttpResponse? = null

    fun method(m: String): HttpRequestBuilder {
        method = m.uppercase()
        cachedResponse = null
        return this
    }

    fun headers(data: Any?): HttpRequestBuilder {
        if (data is Map<*, *>) {
            data.forEach { (k, v) -> if (k is String && v != null) headersMap[k] = v.toString() }
            cachedResponse = null
        }
        return this
    }

    fun header(
        key: String,
        value: String,
    ): HttpRequestBuilder {
        headersMap[key] = value
        cachedResponse = null
        return this
    }

    fun queries(params: Any?): HttpRequestBuilder {
        if (params is Map<*, *>) {
            val pairs =
                params.entries.mapNotNull { (k, v) ->
                    if (k is String) {
                        val encK = URLEncoder.encode(k, "UTF-8")
                        val encV = URLEncoder.encode(v?.toString() ?: "", "UTF-8")
                        "$encK=$encV"
                    } else {
                        null
                    }
                }
            if (pairs.isNotEmpty()) {
                url += (if (url.contains("?")) "&" else "?") + pairs.joinToString("&")
                cachedResponse = null
            }
        }
        return this
    }

    fun params(data: Any?): HttpRequestBuilder = if (method == "GET") queries(data) else form(data)

    fun form(data: Any?): HttpRequestBuilder {
        if (data is Map<*, *>) {
            val encoded =
                data.entries
                    .mapNotNull { (k, v) ->
                        if (k is String) {
                            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v?.toString() ?: "", "UTF-8")}"
                        } else {
                            null
                        }
                    }.joinToString("&")
            bodyBytes = encoded.encodeToByteArray()
            bodyContentType = "application/x-www-form-urlencoded"
            cachedResponse = null
        }
        return this
    }

    fun body(data: Any?): HttpRequestBuilder {
        when (data) {
            is JsBlob -> {
                bodyBytes = Base64.getDecoder().decode(data.base64())
                bodyContentType = data.type.ifEmpty { "application/octet-stream" }
            }
            is Map<*, *> -> form(data)
            else -> {
                bodyBytes = (data?.toString() ?: "").encodeToByteArray()
                bodyContentType = "application/json; charset=utf-8"
            }
        }
        cachedResponse = null
        return this
    }

    fun binary(
        base64Data: String,
        type: String?,
    ): HttpRequestBuilder {
        bodyBytes = Base64.getDecoder().decode(base64Data)
        bodyContentType = type ?: "application/octet-stream"
        cachedResponse = null
        return this
    }

    fun timeout(ms: Int): HttpRequestBuilder {
        if (ms > 0) timeoutMs = ms.toLong()
        return this
    }

    fun syncCookie(sync: Boolean): HttpRequestBuilder {
        syncCookie = sync
        return this
    }

    fun executeResponse(): JsHttpResponse {
        cachedResponse?.let { return it }
        val spec =
            HttpSpec(
                method = method,
                url = url,
                headers = headersMap,
                body = bodyBytes,
                bodyContentType = bodyContentType,
                timeoutMs = timeoutMs,
                syncCookie = syncCookie,
            )
        val raw = executor.execute(spec)
        return JsHttpResponse(raw, executor, spec, log).also { cachedResponse = it }
    }

    fun string(): String = executeResponse().text(null)

    fun string(charset: String?): String = executeResponse().text(charset)

    fun text(): String = executeResponse().text(null)

    fun text(charset: String?): String = executeResponse().text(charset)

    fun html(): JSElement = executeResponse().html(null)

    fun html(charset: String?): JSElement = executeResponse().html(charset)

    fun json(): Any? = executeResponse().json()

    fun blob(): JsBlob = executeResponse().blob()

    fun url(): String = url

    fun ok(): Boolean = executeResponse().ok

    fun statusCode(): Int = executeResponse().status
}
