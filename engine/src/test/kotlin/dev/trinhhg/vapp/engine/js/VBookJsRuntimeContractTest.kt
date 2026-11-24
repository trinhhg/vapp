package dev.trinhhg.vapp.engine.js

import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.fetch.ResponseParser
import dev.trinhhg.vapp.engine.js.api.HttpExecutor
import dev.trinhhg.vapp.engine.js.api.KeyValueStorageApi
import dev.trinhhg.vapp.engine.js.loader.JsApiSetup
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.OkioJsonCookieStore
import dev.trinhhg.vapp.engine.security.RegexSecurityValidator
import dev.trinhhg.vapp.engine.security.SecurityValidationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class VBookJsRuntimeContractTest {
    private lateinit var server: MockWebServer
    private lateinit var log: EngineLogger
    private lateinit var cookies: OkioJsonCookieStore
    private lateinit var http: HttpExecutor

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        log = EngineLogger()
        cookies = OkioJsonCookieStore()
        http = HttpExecutor(cookies, log)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetch returns JsHttpResponse with accurate ok boolean on 200 vs 403 and text()`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<div class='box-chap'>Xin chào Việt Nam</div>"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val script =
            """
            function execute(url) {
                let r1 = fetch(url + "/ok", { headers: { "user-agent": UserAgent.chrome() } });
                let r2 = fetch(url + "/fail");
                if (!r1.ok || r1.status !== 200) return Response.error("r1 failed");
                if (r2.ok || r2.status !== 403) return Response.error("r2 ok should be false");
                let doc = Html.parse(r1.text());
                return Response.success(doc.select("div.box-chap").first().text());
            }
            """.trimIndent()

        val result = evalExecute(script, baseUrl, extraScripts = emptyMap())
        val parsed = ResponseParser.parse(result, "test")
        assertEquals("Xin chào Việt Nam", parsed.data)
    }

    @Test
    fun `fetch html decodes explicit GBK charset and meta charset`() {
        val gbkBytes =
            "<html><head><meta charset='gbk'></head><body><div id='txt'>凡人修仙传</div></body></html>"
                .toByteArray(Charset.forName("GBK"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(gbkBytes)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(gbkBytes)))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val script =
            """
            function execute(url) {
                let explicit = fetch(url + "/1").html("gbk").select("#txt").text();
                let autoMeta = fetch(url + "/2").html().select("#txt").text();
                return Response.success({ explicit: explicit, autoMeta: autoMeta });
            }
            """.trimIndent()

        val parsed = ResponseParser.parse(evalExecute(script, baseUrl), "gbk-test")
        val map = parsed.data as Map<*, *>
        assertEquals("凡人修仙传", map["explicit"])
        assertEquals("凡人修仙传", map["autoMeta"])
    }

    @Test
    fun `JSElement supports tagName setter and Html collection methods`() {
        val script =
            """
            function execute(url) {
                let doc = Html.parse("<div id='box'><div id='dropcap'>H</div><p>ello</p><script>bad()</script></div>");
                doc.select("script").remove();
                let drop = doc.select("#dropcap");
                drop.tagName("span");
                let texts = doc.select("span, p").map(function(el) { return el.text(); });
                return Response.success({ html: doc.select("#box").html(), items: texts });
            }
            """.trimIndent()

        val parsed = ResponseParser.parse(evalExecute(script, "https://example.com"), "dom-test")
        val map = parsed.data as Map<*, *>
        assertTrue(map["html"].toString().contains("<span id=\"dropcap\">H</span>"))
        assertFalse(map["html"].toString().contains("bad()"))
        assertEquals(listOf("H", "ello"), map["items"])
    }

    @Test
    fun `sandbox blocks dangerous java Runtime and File classes`() {
        val script =
            """
            function execute(url) {
                return java.lang.Runtime.getRuntime().exec("echo pwned");
            }
            """.trimIndent()

        RhinoSandbox(log) { cx, scope ->
            JsApiSetup.setup(
                cx,
                scope,
                http,
                cookies,
                null,
                "https://example.com",
                "https://example.com",
                "https://example.com",
                emptyMap(),
                emptyMap(),
                KeyValueStorageApi(),
                KeyValueStorageApi(),
                log,
            )
        }.use { sb ->
            assertTrue(sb.evaluate(script, "evil.js").isSuccess)
            val res = sb.callFunction("execute", "https://example.com")
            assertTrue(res.isFailure)
        }
    }

    @Test
    fun `Response error surfaces EngineException instead of fake success`() {
        val script =
            """
            function execute(url) {
                return Response.error("Login required");
            }
            """.trimIndent()

        val raw = evalExecute(script, "https://example.com")
        val ex =
            assertThrows(EngineException::class.java) {
                ResponseParser.parse(raw, "chap")
            }
        assertEquals(EngineErrorCode.SCRIPT_ERROR, ex.code)
        assertTrue(ex.message.contains("Login required"))
    }

    @Test
    fun `RegexSecurityValidator rejects ReDoS pattern and accepts real VBook regexes`() {
        assertThrows(SecurityValidationException::class.java) {
            RegexSecurityValidator.validateRegex("(a+)+$")
        }
        RegexSecurityValidator.validateRegex(
            "(www\\.)?truyenfull\\.(live|today|vn|net|io|bio|vision)/[^/?#]+/?(\\?[^#]*)?(#.*)?$",
        )
    }

    private fun evalExecute(
        script: String,
        url: String,
        extraScripts: Map<String, String> = emptyMap(),
    ): Any? =
        RhinoSandbox(log) { cx, scope ->
            JsApiSetup.setup(
                cx = cx,
                scope = scope,
                httpExecutor = http,
                cookieStore = cookies,
                browserFactory = null,
                baseUrl = url,
                targetUrl = url,
                pluginSource = url,
                pluginConfig = emptyMap(),
                extraScripts = extraScripts,
                localStorageApi = KeyValueStorageApi(),
                cacheStorageApi = KeyValueStorageApi(),
                log = log,
            )
        }.use { sb ->
            assertTrue(sb.evaluate(script, "test.js").isSuccess)
            sb.callFunction("execute", url).getOrThrow()
        }
}
