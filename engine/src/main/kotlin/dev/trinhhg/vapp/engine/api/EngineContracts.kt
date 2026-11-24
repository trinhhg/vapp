package dev.trinhhg.vapp.engine.api

/** Platform browser session contract backing VBook `Engine.newBrowser()`. */
interface BrowserSession {
    fun launch(
        url: String,
        timeoutMs: Long = 30_000L,
    ): String

    fun launchAsync(url: String)

    fun loadHtml(
        baseUrl: String,
        html: String,
    )

    fun waitUrl(
        patterns: List<String>,
        timeoutMs: Long = 15_000L,
    )

    fun block(patterns: List<String>)

    fun urls(): List<String>

    fun callJs(
        script: String,
        timeoutMs: Long = 5_000L,
    ): String

    fun getVariable(name: String): String?

    fun setUserAgent(userAgent: String)

    fun html(timeoutMs: Long = 2_000L): String

    fun close()
}

interface BrowserEngineFactory {
    fun createSession(): BrowserSession
}

interface ChallengeSolver {
    suspend fun solveChallenge(
        url: String,
        timeoutMs: Long = 120_000L,
    ): String?
}

data class ExtensionNavTab(
    val title: String,
    val input: String,
    val script: String,
)

data class BookMetadata(
    val name: String,
    val author: String = "",
    val cover: String? = null,
    val description: String = "",
    val detail: String = "",
    val ongoing: Boolean = false,
    val url: String,
    val host: String = "",
    val genres: List<ExtensionNavTab> = emptyList(),
    val suggests: List<ExtensionNavTab> = emptyList(),
)

data class ChapterRef(
    val index: Int,
    val name: String,
    val url: String,
    val partName: String? = null,
)

data class ChapterContent(
    val title: String? = null,
    val html: String,
)

data class BookSearchItem(
    val name: String,
    val link: String,
    val cover: String? = null,
    val description: String = "",
    val host: String = "",
)

data class PagedList<T>(
    val items: List<T>,
    val nextPageToken: String? = null,
) {
    val hasMore: Boolean get() = !nextPageToken.isNullOrBlank()
}

interface ExtensionExecutor {
    suspend fun fetchBookDetail(
        extensionId: String,
        bookUrl: String,
    ): Result<BookMetadata>

    suspend fun fetchTableOfContents(
        extensionId: String,
        bookUrl: String,
    ): Result<List<ChapterRef>>

    suspend fun fetchChapterContent(
        extensionId: String,
        chapterUrl: String,
    ): Result<ChapterContent>

    suspend fun search(
        extensionId: String,
        query: String,
        page: String? = null,
    ): Result<PagedList<BookSearchItem>>

    suspend fun browse(
        extensionId: String,
        script: String,
        input: String? = null,
        page: String? = null,
    ): Result<PagedList<BookSearchItem>>

    suspend fun fetchHomeTabs(extensionId: String): Result<List<ExtensionNavTab>>

    suspend fun fetchGenres(extensionId: String): Result<List<ExtensionNavTab>>
}
