package dev.trinhhg.vapp.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.trinhhg.vapp.engine.api.ChallengeSolver
import dev.trinhhg.vapp.engine.js.api.UserAgent
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.engine.security.NetworkSecurityValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Visible WebView activity used when an extension hits a browser challenge (Cloudflare/bot gate)
 * or requires user-driven manual authentication (`EXTRA_MANUAL_LOGIN = true`).
 */
class ChallengeSolverActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var requestId: Long = -1L
    private var manualLogin: Boolean = false
    private var settledCleanCount: Int = 0
    private var finished: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val timeoutRunnable =
        Runnable {
            if (!finished && !manualLogin) {
                completeAndFinish(extractCurrentHtmlOrNull = false)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, -1L)
        manualLogin = intent.getBooleanExtra(EXTRA_MANUAL_LOGIN, false)
        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty().ifBlank { UserAgent.chrome() }
        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)

        if (targetUrl.isBlank()) {
            completeResult(null)
            finish()
            return
        }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

        val topBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 24, 24, 16)
            }
        val titleView =
            TextView(this).apply {
                text = if (manualLogin) "Đăng nhập / Xác minh thủ công" else "Đang xác minh trình duyệt..."
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        val cancelBtn =
            Button(this).apply {
                text = "Hủy"
                setOnClickListener {
                    completeResult(null)
                    finish()
                }
            }
        val doneBtn =
            Button(this).apply {
                text = "Xong"
                visibility = if (manualLogin) View.VISIBLE else View.GONE
                setOnClickListener {
                    completeAndFinish(extractCurrentHtmlOrNull = true)
                }
            }
        topBar.addView(titleView)
        topBar.addView(doneBtn)
        topBar.addView(cancelBtn)

        val progressBar =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            }

        val wv =
            WebView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                configureWebViewSettings(settings, userAgent)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient =
                    object : WebChromeClient() {
                        override fun onProgressChanged(
                            view: WebView?,
                            newProgress: Int,
                        ) {
                            progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                        }
                    }

                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url?.toString().orEmpty()
                            return uri.isNotBlank() && !uri.startsWith("http://") && !uri.startsWith("https://")
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) {
                            super.onPageFinished(view, url)
                            if (!manualLogin && !finished) {
                                scheduleAutoVerify(view)
                            }
                        }
                    }
            }
        webView = wv
        root.addView(topBar)
        root.addView(progressBar)
        root.addView(wv)
        setContentView(root)

        if (!manualLogin) {
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)
        }
        wv.loadUrl(targetUrl)
    }

    private fun scheduleAutoVerify(view: WebView?) {
        if (view == null || finished) return
        mainHandler.postDelayed({
            if (finished) return@postDelayed
            view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { raw ->
                val html = decodeJsString(raw)
                val isChallenge = looksLikeChallengePage(html, view.title.orEmpty())
                if (isChallenge) {
                    settledCleanCount = 0
                } else if (html.length > MIN_VALID_HTML_LEN) {
                    settledCleanCount++
                    if (settledCleanCount >= REQUIRED_CLEAN_LOADS) {
                        syncCookiesForUrl(view.url)
                        completeResult(html)
                        finish()
                    } else {
                        scheduleAutoVerify(view)
                    }
                }
            }
        }, VERIFY_POLL_DELAY_MS)
    }

    private fun completeAndFinish(extractCurrentHtmlOrNull: Boolean) {
        if (finished) return
        val wv = webView
        if (!extractCurrentHtmlOrNull || wv == null) {
            completeResult(null)
            finish()
            return
        }
        syncCookiesForUrl(wv.url)
        wv.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { raw ->
            completeResult(decodeJsString(raw).ifBlank { null })
            finish()
        }
    }

    private fun syncCookiesForUrl(currentUrl: String?) {
        val session = pendingSessions[requestId] ?: return
        val cm = CookieManager.getInstance()
        cm.flush()
        listOfNotNull(session.url, currentUrl).distinct().forEach { u ->
            val rawCookie = cm.getCookie(u)
            if (!rawCookie.isNullOrBlank()) {
                session.cookieStore.setRawCookie(u, rawCookie)
            }
        }
    }

    private fun completeResult(html: String?) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacks(timeoutRunnable)
        pendingSessions.remove(requestId)?.deferred?.complete(html)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeoutRunnable)
        if (!finished) {
            completeResult(null)
        }
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private data class PendingSession(
        val url: String,
        val cookieStore: CookiePersistence,
        val deferred: CompletableDeferred<String?>,
    )

    companion object {
        const val EXTRA_REQUEST_ID = "vapp.extra.request_id"
        const val EXTRA_URL = "vapp.extra.url"
        const val EXTRA_USER_AGENT = "vapp.extra.user_agent"
        const val EXTRA_TIMEOUT_MS = "vapp.extra.timeout_ms"
        const val EXTRA_MANUAL_LOGIN = "vapp.extra.manual_login"

        private const val DEFAULT_TIMEOUT_MS = 120_000L
        private const val VERIFY_POLL_DELAY_MS = 900L
        private const val REQUIRED_CLEAN_LOADS = 2
        private const val MIN_VALID_HTML_LEN = 256

        private val nextRequestId = AtomicLong(1L)
        private val pendingSessions = ConcurrentHashMap<Long, PendingSession>()

        internal fun registerSession(
            url: String,
            cookieStore: CookiePersistence,
            deferred: CompletableDeferred<String?>,
        ): Long {
            val id = nextRequestId.getAndIncrement()
            pendingSessions[id] = PendingSession(url, cookieStore, deferred)
            return id
        }

        internal fun cancelSession(id: Long) {
            pendingSessions.remove(id)?.deferred?.cancel()
        }

        @SuppressLint("SetJavaScriptEnabled")
        internal fun configureWebViewSettings(
            settings: WebSettings,
            userAgent: String,
        ) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = userAgent
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        internal fun decodeJsString(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "null") return ""
            return runCatching {
                val parsed = JSONTokener(raw).nextValue()
                if (parsed == null || parsed == org.json.JSONObject.NULL) "" else parsed.toString()
            }.getOrDefault(raw)
        }

        internal fun looksLikeChallengePage(
            html: String,
            title: String,
        ): Boolean {
            val combined = "$title\n${html.take(4096)}".lowercase()
            return combined.contains("just a moment") ||
                combined.contains("checking your browser") ||
                combined.contains("cf-browser-verification") ||
                combined.contains("/cdn-cgi/challenge-platform") ||
                combined.contains("attention required! | cloudflare")
        }
    }
}

/**
 * Android [ChallengeSolver] implementation backed by [ChallengeSolverActivity].
 */
class WebViewChallengeSolver(
    private val appContext: Context,
    private val cookieStore: CookiePersistence,
    private val log: EngineLogger,
) : ChallengeSolver {
    private val mutex = Mutex()

    override suspend fun solveChallenge(
        url: String,
        timeoutMs: Long,
    ): String? = launchSolver(url = url, timeoutMs = timeoutMs, manualLogin = false)

    suspend fun openManualLogin(
        url: String,
        timeoutMs: Long = 180_000L,
    ): String? = launchSolver(url = url, timeoutMs = timeoutMs, manualLogin = true)

    private suspend fun launchSolver(
        url: String,
        timeoutMs: Long,
        manualLogin: Boolean,
    ): String? {
        NetworkSecurityValidator.validateUrl(url)
        return mutex.withLock {
            pushCookiesToWebView(url)
            val deferred = CompletableDeferred<String?>()
            val reqId = ChallengeSolverActivity.registerSession(url, cookieStore, deferred)
            val intent =
                Intent(appContext, ChallengeSolverActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(ChallengeSolverActivity.EXTRA_REQUEST_ID, reqId)
                    putExtra(ChallengeSolverActivity.EXTRA_URL, url)
                    putExtra(ChallengeSolverActivity.EXTRA_USER_AGENT, UserAgent.chrome())
                    putExtra(ChallengeSolverActivity.EXTRA_TIMEOUT_MS, timeoutMs)
                    putExtra(ChallengeSolverActivity.EXTRA_MANUAL_LOGIN, manualLogin)
                }
            log.add("Launching solver (manual=$manualLogin) for $url", tag = "WebViewChallengeSolver")
            appContext.startActivity(intent)
            val result =
                withTimeoutOrNull(timeoutMs + 5_000L) {
                    deferred.await()
                }
            if (result == null) {
                ChallengeSolverActivity.cancelSession(reqId)
                log.warn("Challenge solver cancelled or timed out for $url", tag = "WebViewChallengeSolver")
            } else {
                log.add("Challenge solved for $url (${result.length} chars)", tag = "WebViewChallengeSolver")
            }
            result
        }
    }

    private fun pushCookiesToWebView(url: String) {
        val raw = cookieStore.getCookieHeader(url) ?: return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            cm.setCookie(url, pair)
        }
        cm.flush()
    }
}
