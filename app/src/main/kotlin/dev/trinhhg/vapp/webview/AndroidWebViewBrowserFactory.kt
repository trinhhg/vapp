package dev.trinhhg.vapp.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.api.BrowserEngineFactory
import dev.trinhhg.vapp.engine.api.BrowserSession
import dev.trinhhg.vapp.engine.js.api.UserAgent
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.engine.security.NetworkSecurityValidator
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android implementation of VBook's `Engine.newBrowser()` using offscreen Android [WebView].
 */
class AndroidWebViewBrowserFactory(
    private val appContext: Context,
    private val cookieStore: CookiePersistence,
    private val log: EngineLogger,
) : BrowserEngineFactory {
    override fun createSession(): BrowserSession = AndroidWebViewBrowserSession(appContext, cookieStore, log)
}

private class AndroidWebViewBrowserSession(
    private val appContext: Context,
    private val cookieStore: CookiePersistence,
    private val log: EngineLogger,
) : BrowserSession {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val capturedUrls = CopyOnWriteArrayList<String>()
    private val blockedPatterns = CopyOnWriteArrayList<Regex>()
    private val variables = ConcurrentHashMap<String, String>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var lastUrl: String = ""

    @Volatile
    private var pageFinishLatch: CountDownLatch = CountDownLatch(0)

    @Volatile
    private var userAgent: String = UserAgent.chrome()

    @Volatile
    private var closed: Boolean = false

    private inner class NativeBridge {
        @JavascriptInterface
        fun putVariable(
            name: String?,
            value: String?,
        ) {
            if (!name.isNullOrBlank() && value != null) {
                variables[name] = value
            }
        }
    }

    init {
        createWebViewOnMain()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun createWebViewOnMain() {
        runOnMainSync(5_000L) {
            val wv =
                WebView(appContext).apply {
                    ChallengeSolverActivity.configureWebViewSettings(settings, userAgent)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(NativeBridge(), "VappBridge")

                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                capturedUrls.add(reqUrl)
                                if (blockedPatterns.any { it.containsMatchIn(reqUrl) }) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?,
                            ) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank()) lastUrl = url
                                pageFinishLatch.countDown()
                            }
                        }
                }
            webView = wv
        }
    }

    override fun launch(
        url: String,
        timeoutMs: Long,
    ): String {
        ensureOpen()
        NetworkSecurityValidator.validateUrl(url)
        pushCookies(url)
        lastUrl = url
        val latch = CountDownLatch(1)
        pageFinishLatch = latch

        runOnMainSync(3_000L) {
            webView?.loadUrl(url)
        }
        latch.await(timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
        syncCookies(lastUrl)
        return html(minOf(timeoutMs, 5_000L))
    }

    override fun launchAsync(url: String) {
        ensureOpen()
        NetworkSecurityValidator.validateUrl(url)
        pushCookies(url)
        lastUrl = url
        pageFinishLatch = CountDownLatch(1)
        mainHandler.post {
            webView?.loadUrl(url)
        }
    }

    override fun loadHtml(
        baseUrl: String,
        html: String,
    ) {
        ensureOpen()
        if (baseUrl.isNotBlank()) {
            NetworkSecurityValidator.validateUrl(baseUrl)
            lastUrl = baseUrl
        }
        val latch = CountDownLatch(1)
        pageFinishLatch = latch
        runOnMainSync(3_000L) {
            webView?.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
        }
        latch.await(5_000L, TimeUnit.MILLISECONDS)
    }

    override fun waitUrl(
        patterns: List<String>,
        timeoutMs: Long,
    ) {
        ensureOpen()
        if (patterns.isEmpty()) return
        val regexes =
            patterns.map { pat ->
                runCatching { Regex(pat) }.getOrElse { Regex(Regex.escape(pat)) }
            }
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(500L)
        while (System.currentTimeMillis() < deadline && !closed) {
            if (capturedUrls.any { u -> regexes.any { r -> r.containsMatchIn(u) } }) {
                return
            }
            Thread.sleep(100L)
        }
    }

    override fun block(patterns: List<String>) {
        patterns.forEach { pat ->
            val regex = runCatching { Regex(pat) }.getOrElse { Regex(Regex.escape(pat)) }
            blockedPatterns.add(regex)
        }
    }

    override fun urls(): List<String> = capturedUrls.toList()

    override fun callJs(
        script: String,
        timeoutMs: Long,
    ): String {
        ensureOpen()
        val resultRef = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = webView
            if (wv == null) {
                latch.countDown()
            } else {
                wv.evaluateJavascript(NATIVE_BRIDGE_SHIM, null)
                wv.evaluateJavascript(script) { raw ->
                    resultRef.set(ChallengeSolverActivity.decodeJsString(raw))
                    latch.countDown()
                }
            }
        }
        latch.await(timeoutMs.coerceAtLeast(500L), TimeUnit.MILLISECONDS)
        return resultRef.get()
    }

    override fun getVariable(name: String): String? {
        variables[name]?.let { return it }
        if (closed) return null
        val fromWindow = callJs("window[${org.json.JSONObject.quote(name)}] ?? ''", 2_000L)
        return fromWindow.ifBlank { null }
    }

    override fun setUserAgent(userAgent: String) {
        this.userAgent = userAgent
        mainHandler.post {
            webView?.settings?.userAgentString = userAgent
        }
    }

    override fun html(timeoutMs: Long): String {
        if (closed) return ""
        return callJs("document.documentElement ? document.documentElement.outerHTML : ''", timeoutMs)
    }

    override fun close() {
        if (closed) return
        closed = true
        syncCookies(lastUrl)
        mainHandler.post {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
        log.debug("Closed WebView session (urls=${capturedUrls.size})", tag = "AndroidBrowserSession")
    }

    private fun ensureOpen() {
        if (closed) {
            throw EngineException(EngineErrorCode.SCRIPT_ERROR, "BrowserSession has already been closed.")
        }
    }

    private fun pushCookies(url: String) {
        val raw = cookieStore.getCookieHeader(url) ?: return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            cm.setCookie(url, pair)
        }
        cm.flush()
    }

    private fun syncCookies(url: String) {
        if (url.isBlank()) return
        val cm = CookieManager.getInstance()
        cm.flush()
        val raw = cm.getCookie(url)
        if (!raw.isNullOrBlank()) {
            cookieStore.setRawCookie(url, raw)
        }
    }

    private fun runOnMainSync(
        timeoutMs: Long,
        block: () -> Unit,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val NATIVE_BRIDGE_SHIM =
            "if(!window._callNativeFunction){window._callNativeFunction=function(n,k,v){" +
                "if(n==='putVariable'&&window.VappBridge){window.VappBridge.putVariable(String(k),v==null?'':String(v));}" +
                "};}"
    }
}
