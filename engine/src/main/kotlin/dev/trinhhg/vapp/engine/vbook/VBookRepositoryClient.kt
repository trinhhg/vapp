package dev.trinhhg.vapp.engine.vbook

import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.js.api.HttpExecutor
import dev.trinhhg.vapp.engine.js.api.HttpSpec
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CharsetDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Fetches and parses VBook `repository.json` (array of repository links) and `plugin.json`
 * (extension catalog with `{ metadata, data }`).
 */
class VBookRepositoryClient(
    private val httpExecutor: HttpExecutor,
    private val log: EngineLogger,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val lock = Any()
    private val cache = HashMap<String, Pair<List<VBookExtensionItem>, Long>>()

    fun fetchExtensions(
        registryUrl: String,
        forceRefresh: Boolean = false,
    ): List<VBookExtensionItem> {
        if (!forceRefresh) {
            synchronized(lock) {
                cache[registryUrl]?.let { (items, ts) ->
                    if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return items
                }
            }
        }

        val rawText = fetchText(registryUrl)
        val trimmed = rawText.trim()
        val result =
            when {
                trimmed.startsWith("[") -> {
                    val repos =
                        runCatching {
                            json.decodeFromString<List<VBookRepositoryEntry>>(trimmed)
                        }.getOrElse { err ->
                            throw EngineException(
                                EngineErrorCode.VALIDATION_ERROR,
                                "Invalid repository.json format from $registryUrl: ${err.message}",
                                err,
                            )
                        }
                    aggregateRepositories(repos, forceRefresh)
                }
                trimmed.startsWith("{") -> parsePluginRegistry(trimmed, registryUrl)
                else -> throw EngineException(
                    EngineErrorCode.VALIDATION_ERROR,
                    "Unexpected registry payload from $registryUrl",
                )
            }

        synchronized(lock) {
            cache[registryUrl] = result to System.currentTimeMillis()
        }
        return result
    }

    fun aggregateRepositories(
        repos: List<VBookRepositoryEntry>,
        forceRefresh: Boolean = false,
    ): List<VBookExtensionItem> {
        val all = ArrayList<VBookExtensionItem>()
        for (repo in repos) {
            val link = repo.link?.takeIf { it.isNotBlank() } ?: continue
            if (!repo.enabled) continue
            runCatching { fetchExtensions(link, forceRefresh) }
                .onSuccess { all.addAll(it) }
                .onFailure { err ->
                    log.warn("Failed to fetch repository $link: ${err.message}", tag = "Registry")
                }
        }
        return all
    }

    private fun parsePluginRegistry(
        rawJson: String,
        sourceUrl: String,
    ): List<VBookExtensionItem> =
        try {
            val root =
                json.parseToJsonElement(rawJson) as? JsonObject
                    ?: throw IllegalArgumentException("Root is not a JSON object")
            val dataArray = root["data"] as? JsonArray ?: return emptyList()
            json.decodeFromJsonElement<List<VBookExtensionItem>>(dataArray)
        } catch (e: Exception) {
            throw EngineException(
                EngineErrorCode.VALIDATION_ERROR,
                "Failed to parse plugin.json from $sourceUrl: ${e.message}",
                e,
            )
        }

    private fun fetchText(url: String): String {
        val res = httpExecutor.execute(HttpSpec(method = "GET", url = url))
        if (res.code !in 200..299) {
            throw EngineException(
                EngineErrorCode.HTTP_ERROR,
                "HTTP ${res.code} fetching registry $url: ${res.message}",
            )
        }
        val text = CharsetDecoder.decode(res.body, contentTypeHeader = res.headers["content-type"])
        if (text.isBlank()) {
            throw EngineException(EngineErrorCode.NETWORK_ERROR, "Empty response from registry $url")
        }
        return text
    }

    fun clearCache() {
        synchronized(lock) { cache.clear() }
    }

    companion object {
        const val DEFAULT_REPOSITORY_URL =
            "https://raw.githubusercontent.com/Darkrai9x/vbook-extensions/master/repository.json"
        const val DEFAULT_PLUGIN_INDEX_URL =
            "https://raw.githubusercontent.com/Darkrai9x/vbook-extensions/master/plugin.json"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
