package dev.trinhhg.vapp.engine.fetch

import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.api.BookMetadata
import dev.trinhhg.vapp.engine.api.BookSearchItem
import dev.trinhhg.vapp.engine.api.BrowserEngineFactory
import dev.trinhhg.vapp.engine.api.ChapterContent
import dev.trinhhg.vapp.engine.api.ChapterRef
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.engine.api.ExtensionNavTab
import dev.trinhhg.vapp.engine.api.PagedList
import dev.trinhhg.vapp.engine.js.RhinoSandbox
import dev.trinhhg.vapp.engine.js.api.HttpExecutor
import dev.trinhhg.vapp.engine.js.api.KeyValueStorageApi
import dev.trinhhg.vapp.engine.js.loader.JsApiSetup
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.engine.util.nfc
import dev.trinhhg.vapp.engine.util.normalizeUrl
import dev.trinhhg.vapp.engine.util.resolveUrl
import dev.trinhhg.vapp.engine.vbook.ExtensionManager
import dev.trinhhg.vapp.engine.vbook.InstalledExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes VBook extension lifecycle scripts (`home`, `genre`, `search`, `detail`, `page`, `toc`, `chap`)
 * inside an isolated Rhino sandbox bounded by [Semaphore].
 */
class ExtensionExecutorImpl(
    private val extensionManager: ExtensionManager,
    private val httpExecutor: HttpExecutor,
    private val cookieStore: CookiePersistence,
    private val browserFactory: BrowserEngineFactory?,
    private val log: EngineLogger,
    maxConcurrentScripts: Int = 5,
) : ExtensionExecutor {
    private val scriptSemaphore = Semaphore(maxConcurrentScripts)
    private val localStorages = ConcurrentHashMap<String, KeyValueStorageApi>()
    private val cacheStorages = ConcurrentHashMap<String, KeyValueStorageApi>()

    override suspend fun fetchBookDetail(
        extensionId: String,
        bookUrl: String,
    ): Result<BookMetadata> =
        runCatching {
            val ext = requireExtension(extensionId)
            val cleanUrl = bookUrl.normalizeUrl()
            val scriptName = ext.scriptMap["detail"] ?: "detail.js"
            val parsed = runScript(ext, scriptName, cleanUrl, arrayOf(cleanUrl), "detail")
            val map =
                parsed.data as? Map<*, *>
                    ?: throw EngineException(EngineErrorCode.SCRIPT_ERROR, "detail.js returned non-object data")

            val host = (map["host"] as? String)?.takeIf { it.isNotBlank() } ?: ext.sourceUrl
            val cover = resolveUrl(host, map["cover"]?.toString())
            BookMetadata(
                name =
                    map["name"]
                        ?.toString()
                        ?.trim()
                        ?.nfc()
                        .orEmpty(),
                author =
                    map["author"]
                        ?.toString()
                        ?.trim()
                        ?.nfc()
                        .orEmpty(),
                cover = cover.ifBlank { null },
                description = map["description"]?.toString()?.trim().orEmpty(),
                detail = map["detail"]?.toString()?.trim().orEmpty(),
                ongoing = map["ongoing"] as? Boolean ?: false,
                url = (map["url"] as? String)?.takeIf { it.startsWith("http") } ?: cleanUrl,
                host = host,
                genres = parseNavTabs(map["genres"]),
                suggests = parseNavTabs(map["suggests"]),
            )
        }

    override suspend fun fetchTableOfContents(
        extensionId: String,
        bookUrl: String,
    ): Result<List<ChapterRef>> =
        runCatching {
            val ext = requireExtension(extensionId)
            val cleanUrl = bookUrl.normalizeUrl()
            val pageScript = ext.scriptMap["page"]
            val pageUrls: List<String> =
                if (!pageScript.isNullOrBlank() && hasScript(ext, pageScript)) {
                    val pageResp = runScript(ext, pageScript, cleanUrl, arrayOf(cleanUrl), "page")
                    (pageResp.data as? List<*>)
                        ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                        ?.map { resolveUrl(ext.sourceUrl, it) }
                        ?: listOf(cleanUrl)
                } else {
                    listOf(cleanUrl)
                }

            val tocScript = ext.scriptMap["toc"] ?: "toc.js"
            val chapters = ArrayList<ChapterRef>()
            var idx = 0
            for (pageUrl in pageUrls) {
                val tocResp = runScript(ext, tocScript, pageUrl, arrayOf(pageUrl), "toc")
                val items =
                    tocResp.data as? List<*>
                        ?: throw EngineException(EngineErrorCode.SCRIPT_ERROR, "toc.js returned non-array data")
                for (rawItem in items) {
                    val item = rawItem as? Map<*, *> ?: continue
                    val title =
                        item["name"]
                            ?.toString()
                            ?.trim()
                            ?.nfc()
                            .orEmpty()
                    val rawChapUrl = item["url"]?.toString()?.trim().orEmpty()
                    val host = item["host"]?.toString()?.takeIf { it.isNotBlank() } ?: ext.sourceUrl
                    val fullChapUrl = resolveUrl(host, rawChapUrl)
                    if (title.isNotEmpty() && fullChapUrl.isNotEmpty()) {
                        chapters.add(
                            ChapterRef(
                                index = idx++,
                                name = title,
                                url = fullChapUrl,
                                partName = item["part"]?.toString(),
                            ),
                        )
                    }
                }
            }
            chapters
        }

    override suspend fun fetchChapterContent(
        extensionId: String,
        chapterUrl: String,
    ): Result<ChapterContent> =
        runCatching {
            val ext = requireExtension(extensionId)
            val cleanUrl = chapterUrl.normalizeUrl()
            val chapScript = ext.scriptMap["chap"] ?: "chap.js"
            val parsed = runScript(ext, chapScript, cleanUrl, arrayOf(cleanUrl), "chap")
            val html =
                when (val data = parsed.data) {
                    is String -> data
                    is Map<*, *> ->
                        listOf("body", "content", "text")
                            .firstNotNullOfOrNull { data[it]?.toString() }
                            .orEmpty()
                    else -> data?.toString().orEmpty()
                }
            val title = parsed.data2?.toString()?.takeIf { it.isNotBlank() }
            if (html.isBlank()) {
                throw EngineException(EngineErrorCode.SCRIPT_ERROR, "chap.js returned empty content for $cleanUrl")
            }
            ChapterContent(title = title, html = html)
        }

    override suspend fun search(
        extensionId: String,
        query: String,
        page: String?,
    ): Result<PagedList<BookSearchItem>> =
        runCatching {
            val ext = requireExtension(extensionId)
            val searchScript = ext.scriptMap["search"] ?: "search.js"
            val args: Array<Any?> = arrayOf(query, page ?: "1")
            val parsed = runScript(ext, searchScript, ext.sourceUrl, args, "search")
            parseSearchResult(parsed, ext.sourceUrl)
        }

    override suspend fun browse(
        extensionId: String,
        script: String,
        input: String?,
        page: String?,
    ): Result<PagedList<BookSearchItem>> =
        runCatching {
            val ext = requireExtension(extensionId)
            val args: Array<Any?> = arrayOf(input ?: "", page ?: "1")
            val parsed = runScript(ext, script, input ?: ext.sourceUrl, args, "browse($script)")
            parseSearchResult(parsed, ext.sourceUrl)
        }

    override suspend fun fetchHomeTabs(extensionId: String): Result<List<ExtensionNavTab>> =
        runCatching {
            val ext = requireExtension(extensionId)
            val script = ext.scriptMap["explore"] ?: ext.scriptMap["home"] ?: "home.js"
            val parsed = runScript(ext, script, ext.sourceUrl, emptyArray(), "home")
            parseNavTabs(parsed.data)
        }

    override suspend fun fetchGenres(extensionId: String): Result<List<ExtensionNavTab>> =
        runCatching {
            val ext = requireExtension(extensionId)
            val script = ext.scriptMap["genre"] ?: "genre.js"
            val parsed = runScript(ext, script, ext.sourceUrl, emptyArray(), "genre")
            parseNavTabs(parsed.data)
        }

    private suspend fun runScript(
        ext: InstalledExtension,
        scriptFileName: String,
        targetUrl: String,
        args: Array<Any?>,
        label: String,
    ): ParsedVBookResponse =
        scriptSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                val scripts = extensionManager.loadScripts(ext)
                val scriptCode =
                    scripts[scriptFileName]
                        ?: throw EngineException(
                            EngineErrorCode.SCRIPT_ERROR,
                            "Script '$scriptFileName' missing in extension '${ext.name}'",
                        )

                val localStore = localStorages.getOrPut(ext.id) { KeyValueStorageApi() }
                val cacheStore = cacheStorages.getOrPut(ext.id) { KeyValueStorageApi() }

                RhinoSandbox(log) { cx, scope ->
                    JsApiSetup.setup(
                        cx = cx,
                        scope = scope,
                        httpExecutor = httpExecutor,
                        cookieStore = cookieStore,
                        browserFactory = browserFactory,
                        baseUrl = ext.sourceUrl,
                        targetUrl = targetUrl,
                        pluginSource = ext.sourceUrl,
                        pluginConfig = ext.userConfig,
                        extraScripts = scripts,
                        localStorageApi = localStore,
                        cacheStorageApi = cacheStore,
                        log = log,
                    )
                }.use { sandbox ->
                    sandbox.evaluate(scriptCode, scriptFileName).getOrElse { err ->
                        throw EngineException(
                            EngineErrorCode.SCRIPT_ERROR,
                            "Evaluation failed in ${ext.name}/$scriptFileName: ${err.message}",
                            err,
                        )
                    }
                    val rawResult =
                        sandbox.callFunctionArgs("execute", args).getOrElse { err ->
                            throw EngineException(
                                EngineErrorCode.SCRIPT_ERROR,
                                "execute() failed in ${ext.name}/$scriptFileName: ${err.message}",
                                err,
                            )
                        }
                    ResponseParser.parse(rawResult, "${ext.name}/$label")
                }
            }
        }

    private fun parseSearchResult(
        parsed: ParsedVBookResponse,
        defaultHost: String,
    ): PagedList<BookSearchItem> {
        val list = parsed.data as? List<*> ?: emptyList<Any?>()
        val items =
            list.mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                val name =
                    map["name"]
                        ?.toString()
                        ?.trim()
                        ?.nfc()
                        .orEmpty()
                val rawLink = map["link"]?.toString()?.trim().orEmpty()
                val host = map["host"]?.toString()?.takeIf { it.isNotBlank() } ?: defaultHost
                val link = resolveUrl(host, rawLink)
                if (name.isEmpty() || link.isEmpty()) return@mapNotNull null
                BookSearchItem(
                    name = name,
                    link = link,
                    cover = resolveUrl(host, map["cover"]?.toString()).ifBlank { null },
                    description = map["description"]?.toString()?.trim().orEmpty(),
                    host = host,
                )
            }
        val next = parsed.data2?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        return PagedList(items = items, nextPageToken = next)
    }

    private fun parseNavTabs(raw: Any?): List<ExtensionNavTab> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val title = map["title"]?.toString()?.trim().orEmpty()
            val script = map["script"]?.toString()?.trim().orEmpty()
            val input = map["input"]?.toString()?.trim().orEmpty()
            if (title.isEmpty() || script.isEmpty()) null else ExtensionNavTab(title, input, script)
        }
    }

    private fun hasScript(
        ext: InstalledExtension,
        fileName: String,
    ): Boolean = extensionManager.loadScripts(ext).containsKey(fileName)

    private fun requireExtension(extensionId: String): InstalledExtension =
        extensionManager.getExtension(extensionId)
            ?: throw EngineException(
                EngineErrorCode.EXTENSION_NOT_FOUND,
                "Extension not found: $extensionId",
            )
}
