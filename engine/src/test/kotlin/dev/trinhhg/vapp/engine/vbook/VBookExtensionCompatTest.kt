package dev.trinhhg.vapp.engine.vbook

import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.api.BrowserEngineFactory
import dev.trinhhg.vapp.engine.api.BrowserSession
import dev.trinhhg.vapp.engine.fetch.ExtensionExecutorImpl
import dev.trinhhg.vapp.engine.js.api.HttpExecutor
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.OkioJsonCookieStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VBookExtensionCompatTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `PluginZipExtractor rejects encrypted extension with explicit ENCRYPTED_UNSUPPORTED code`() {
        val zipFile = File(tempDir, "encrypted.zip")
        writeZip(
            zipFile,
            mapOf(
                "plugin.json" to
                    """
                    {
                      "metadata": {
                        "name": "MTC",
                        "author": "vBook",
                        "version": 51,
                        "source": "https://metruyencv.com",
                        "regexp": "metruyencv\\.com/truyen/[^/]+/?$",
                        "encrypt": true,
                        "type": "novel"
                      },
                      "script": { "chap": "chap.js" }
                    }
                    """.trimIndent(),
                "src/chap.js" to "Onw3eQoI5Gcty2Slx0P1Xx",
            ),
        )

        val log = EngineLogger()
        val cookies = OkioJsonCookieStore()
        val extractor = PluginZipExtractor(HttpExecutor(cookies, log), log)
        val ex =
            assertThrows(EngineException::class.java) {
                extractor.extractFromZip(zipFile.toOkioPath())
            }
        assertEquals(EngineErrorCode.ENCRYPTED_UNSUPPORTED, ex.code)
        assertTrue(ex.message.contains("encrypt: true"))
    }

    @Test
    fun `PluginZipExtractor blocks Zip-Slip traversal entry`() {
        val zipFile = File(tempDir, "zipslip.zip")
        writeZip(zipFile, mapOf("../../evil.txt" to "pwn"))

        val log = EngineLogger()
        val extractor = PluginZipExtractor(HttpExecutor(OkioJsonCookieStore(), log), log)
        val ex =
            assertThrows(EngineException::class.java) {
                extractor.extractFromZip(zipFile.toOkioPath())
            }
        assertEquals(EngineErrorCode.VALIDATION_ERROR, ex.code)
    }

    @Test
    fun `full VBook search-detail-toc-chap pipeline works with load(config), response_ok, and Engine_newBrowser`() =
        runTest {
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path?.startsWith("/search") == true ->
                                MockResponse().setBody(
                                    """
                                    <div class="list">
                                      <div class="item">
                                        <a href="/truyen/pham-nhan-tu-tien">Phàm Nhân Tu Tiên</a>
                                        <img src="/cover.jpg"/>
                                      </div>
                                    </div>
                                    """.trimIndent(),
                                )
                            request.path == "/truyen/pham-nhan-tu-tien" ->
                                MockResponse().setBody(
                                    """
                                    <h3 class="title">Phàm Nhân Tu Tiên</h3>
                                    <div class="info"><a itemprop="author">Vong Ngữ</a><span>Đang ra</span></div>
                                    <div class="desc-text">Hàn Lập tu tiên truyện.</div>
                                    <div class="book"><img src="/cover.jpg"/></div>
                                    """.trimIndent(),
                                )
                            request.path == "/truyen/pham-nhan-tu-tien/toc" ->
                                MockResponse().setBody(
                                    """
                                    <ul class="chapters">
                                      <li><a href="/truyen/pham-nhan-tu-tien/chuong-1">Chương 1: Thất Huyền Môn</a></li>
                                      <li><a href="/truyen/pham-nhan-tu-tien/chuong-2">Chương 2: Mặc Đại Phu</a></li>
                                    </ul>
                                    """.trimIndent(),
                                )
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                val baseUrl = server.url("").toString().trimEnd('/')
                val zipFile = File(tempDir, "sample-novel.zip")
                writeZip(
                    zipFile,
                    mapOf(
                        "plugin.json" to
                            """
                            {
                              "metadata": {
                                "name": "Sample Novel",
                                "author": "vapp",
                                "version": 1,
                                "source": "$baseUrl",
                                "regexp": ".*\\/truyen\\/[^\\/]+/?$",
                                "type": "novel",
                                "locale": "vi_VN"
                              },
                              "script": {
                                "search": "search.js",
                                "detail": "detail.js",
                                "toc": "toc.js",
                                "chap": "chap.js"
                              }
                            }
                            """.trimIndent(),
                        "src/config.js" to "const BASE_URL = '$baseUrl';",
                        "src/search.js" to
                            """
                            load('config.js');
                            function execute(q, page) {
                                let res = fetch(BASE_URL + '/search', { queries: { q: q, page: page } });
                                if (!res.ok) return Response.error('HTTP ' + res.status);
                                let doc = res.html();
                                let items = doc.select('.item').map(el => ({
                                    name: el.select('a').text(),
                                    link: el.select('a').attr('href'),
                                    cover: el.select('img').attr('src'),
                                    host: BASE_URL
                                }));
                                return Response.success(items);
                            }
                            """.trimIndent(),
                        "src/detail.js" to
                            """
                            load('config.js');
                            function execute(url) {
                                let res = fetch(url);
                                if (!res.ok) return Response.error('Detail HTTP ' + res.status);
                                let doc = res.html();
                                return Response.success({
                                    name: doc.select('h3.title').text(),
                                    author: doc.select('a[itemprop=author]').text(),
                                    cover: doc.select('div.book img').attr('src'),
                                    description: doc.select('div.desc-text').text(),
                                    ongoing: doc.select('.info').text().indexOf('Đang ra') >= 0,
                                    host: BASE_URL
                                });
                            }
                            """.trimIndent(),
                        "src/toc.js" to
                            """
                            load('config.js');
                            function execute(url) {
                                let doc = fetch(url + '/toc').html();
                                let list = doc.select('ul.chapters li a').map(el => ({
                                    name: el.text(),
                                    url: el.attr('href'),
                                    host: BASE_URL
                                }));
                                return Response.success(list);
                            }
                            """.trimIndent(),
                        "src/chap.js" to
                            """
                            function execute(url) {
                                var browser = Engine.newBrowser();
                                var doc = browser.launch(url, 5000);
                                browser.close();
                                return Response.success(doc.select('#content').html(), doc.select('h1').text());
                            }
                            """.trimIndent(),
                    ),
                )

                val rootDir = File(tempDir, "vapp-root").toOkioPath()
                val log = EngineLogger()
                val cookies = OkioJsonCookieStore(rootDir / "cookies" / "cookies.json")
                val http = HttpExecutor(cookies, log)
                val repoClient = VBookRepositoryClient(http, log)
                val extMgr = ExtensionManager(rootDir, repoClient, PluginZipExtractor(http, log), log)
                val fakeBrowserFactory =
                    object : BrowserEngineFactory {
                        override fun createSession(): BrowserSession =
                            object : BrowserSession {
                                override fun launch(
                                    url: String,
                                    timeoutMs: Long,
                                ): String =
                                    "<html><body><h1>Chương 1</h1>" +
                                        "<div id='content'><p>Nội dung từ WebView cho $url</p></div></body></html>"

                                override fun launchAsync(url: String) = Unit

                                override fun loadHtml(
                                    baseUrl: String,
                                    html: String,
                                ) = Unit

                                override fun waitUrl(
                                    patterns: List<String>,
                                    timeoutMs: Long,
                                ) = Unit

                                override fun block(patterns: List<String>) = Unit

                                override fun urls(): List<String> = emptyList()

                                override fun callJs(
                                    script: String,
                                    timeoutMs: Long,
                                ): String = ""

                                override fun getVariable(name: String): String? = null

                                override fun setUserAgent(userAgent: String) = Unit

                                override fun html(timeoutMs: Long): String = ""

                                override fun close() = Unit
                            }
                    }
                val executor = ExtensionExecutorImpl(extMgr, http, cookies, fakeBrowserFactory, log)

                val ext = extMgr.installFromLocalZip(zipFile.toOkioPath())
                val search = executor.search(ext.id, "pham nhan", "1").getOrThrow()
                assertEquals(1, search.items.size)
                assertEquals("Phàm Nhân Tu Tiên", search.items[0].name)

                val bookUrl = search.items[0].link
                val matched = extMgr.matchByUrl(bookUrl)
                assertNotNull(matched)

                val detail = executor.fetchBookDetail(ext.id, bookUrl).getOrThrow()
                assertEquals("Vong Ngữ", detail.author)
                assertTrue(detail.ongoing)
                assertEquals("$baseUrl/cover.jpg", detail.cover)

                val toc = executor.fetchTableOfContents(ext.id, bookUrl).getOrThrow()
                assertEquals(2, toc.size)
                assertEquals("$baseUrl/truyen/pham-nhan-tu-tien/chuong-1", toc[0].url)

                val chap = executor.fetchChapterContent(ext.id, toc[0].url).getOrThrow()
                assertEquals("Chương 1", chap.title)
                assertTrue(chap.html.contains("Nội dung từ WebView"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `LIVE E2E VBook extensions smoke test - search, detail, toc, chap and encrypted reject`() =
        runTest {
            assumeTrue(System.getProperty("vapp.liveTests") == "true", "Skipped unless -Dvapp.liveTests=true")

            val rootDir = File(tempDir, "vapp-live").toOkioPath()
            val log = EngineLogger()
            val cookies = OkioJsonCookieStore(rootDir / "cookies" / "cookies.json")
            val http = HttpExecutor(cookies, log)
            val repoClient = VBookRepositoryClient(http, log)
            val extractor = PluginZipExtractor(http, log)
            val extMgr = ExtensionManager(rootDir, repoClient, extractor, log)
            val executor = ExtensionExecutorImpl(extMgr, http, cookies, null, log)

            // 1. Fetch live Darkrai9x/vbook-extensions plugin.json
            val items = repoClient.fetchExtensions(VBookRepositoryClient.DEFAULT_PLUGIN_INDEX_URL)
            assertTrue(items.isNotEmpty(), "Expected live plugin.json entries")
            println("[LIVE] Fetched ${items.size} extensions from vbook-extensions")

            // 2. Verify encrypted plugin rejection on metruyenchu
            val mtcItem = items.firstOrNull { it.path?.contains("/metruyenchu/") == true }
            assertNotNull(mtcItem, "Expected metruyenchu in registry")
            val mtcEx =
                assertThrows(EngineException::class.java) {
                    extMgr.installFromRegistryItem(mtcItem!!)
                }
            assertEquals(EngineErrorCode.ENCRYPTED_UNSUPPORTED, mtcEx.code)
            println("[LIVE] Verified encrypted reject: ${mtcEx.message}")

            // 3. Test real unencrypted extensions end-to-end (search -> detail -> toc -> chap)
            val targetSlugs = listOf("/truyenfull/", "/tangthuvien/", "/bachngocsach/", "/isach/")
            var succeeded = 0
            for (slug in targetSlugs) {
                val item = items.firstOrNull { it.path?.contains(slug) == true } ?: continue
                val ext = extMgr.installFromRegistryItem(item)
                println("[LIVE] Testing ${ext.name} (v${ext.version}) from ${ext.sourceUrl}")

                val searchRes = executor.search(ext.id, "tien", "1")
                if (searchRes.isFailure) {
                    println("[LIVE]   search failed (site may block/change): ${searchRes.exceptionOrNull()?.message}")
                    continue
                }
                val found = searchRes.getOrThrow().items
                if (found.isEmpty()) {
                    println("[LIVE]   search returned 0 items")
                    continue
                }
                val firstBook = found.first()
                println("[LIVE]   search hit: ${firstBook.name} -> ${firstBook.link}")

                val detailRes = executor.fetchBookDetail(ext.id, firstBook.link)
                if (detailRes.isFailure) {
                    println("[LIVE]   detail failed: ${detailRes.exceptionOrNull()?.message}")
                    continue
                }
                val detail = detailRes.getOrThrow()
                println("[LIVE]   detail: name='${detail.name}', author='${detail.author}'")

                val tocRes = executor.fetchTableOfContents(ext.id, firstBook.link)
                if (tocRes.isFailure) {
                    println("[LIVE]   toc failed: ${tocRes.exceptionOrNull()?.message}")
                    continue
                }
                val toc = tocRes.getOrThrow()
                if (toc.isEmpty()) {
                    println("[LIVE]   toc empty")
                    continue
                }
                val firstChap = toc.first()
                println("[LIVE]   toc count=${toc.size}, first='${firstChap.name}' -> ${firstChap.url}")

                val chapRes = executor.fetchChapterContent(ext.id, firstChap.url)
                if (chapRes.isFailure) {
                    println("[LIVE]   chap failed: ${chapRes.exceptionOrNull()?.message}")
                    continue
                }
                val chap = chapRes.getOrThrow()
                println("[LIVE]   chap chars=${chap.html.length}, preview='${chap.html.take(80).replace("\n", " ")}'")
                assertTrue(chap.html.isNotBlank())
                succeeded++
            }
            assertTrue(succeeded >= 1, "Expected at least 1 live unencrypted VBook extension to pass full search->detail->toc->chap")
        }

    private fun writeZip(
        dest: File,
        entries: Map<String, String>,
    ) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }
}
