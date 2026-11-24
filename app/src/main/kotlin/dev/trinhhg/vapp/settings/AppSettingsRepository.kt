package dev.trinhhg.vapp.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.trinhhg.vapp.downloader.api.DownloaderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.vappDataStore: DataStore<Preferences> by preferencesDataStore(name = "vapp_settings")

enum class ReaderTextAlign { LEFT, CENTER, RIGHT, JUSTIFY }

enum class ReadMode { VERTICAL_SCROLL, PAGED_SLIDE, PAGED_CURL }

enum class DefaultExportFormat { EPUB3, EPUB2, TXT }

data class AppSettings(
    val fontKey: String = "literata",
    val fontSizeSp: Int = 20,
    val lineHeightMultiplier: Float = 1.6f,
    val paragraphIndentEm: Float = 1.5f,
    val textAlign: ReaderTextAlign = ReaderTextAlign.JUSTIFY,
    val marginHorizontalDp: Int = 18,
    val themePresetId: String = "light",
    val customBgHex: String = "#F6F1E7",
    val customFgHex: String = "#1F1B16",
    val brightness: Float = -1f,
    val readMode: ReadMode = ReadMode.VERTICAL_SCROLL,
    val autoScrollSpeed: Int = 4,
    val ttsVoiceName: String = "",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsSleepTimerMinutes: Int = 0,
    val ttsAmbienceEnabled: Boolean = false,
    val ttsAmbienceTrack: String = "rain",
    val ttsAmbienceVolume: Float = 0.25f,
    val maxConcurrency: Int = 3,
    val requestDelayMs: Int = 0,
    val requestTimeoutMs: Int = 30_000,
    val maxRetriesPerChapter: Int = 2,
    val circuitBreakerThreshold: Int = 8,
    val defaultExportFormat: DefaultExportFormat = DefaultExportFormat.EPUB3,
    val includeCover: Boolean = true,
    val includeImages: Boolean = true,
    val autoSplitVolumes: Boolean = false,
    val zipCompressionLevel: Int = 6,
    val uiLanguage: String = "vi",
) {
    fun toDownloaderConfig(): DownloaderConfig =
        DownloaderConfig(
            maxConcurrency = maxConcurrency.coerceIn(1, 6),
            requestDelayMs = requestDelayMs.coerceIn(0, 5_000).toLong(),
            requestTimeoutMs = requestTimeoutMs.coerceIn(5_000, 120_000).toLong(),
            maxRetriesPerChapter = maxRetriesPerChapter.coerceIn(0, 5),
            circuitBreakerThreshold = circuitBreakerThreshold.coerceIn(3, 20),
        )
}

/**
 * DataStore-backed global application settings enforcing all `data-model.md` validation ranges.
 */
class AppSettingsRepository(
    context: Context,
) {
    private val store = context.applicationContext.vappDataStore

    val settings: Flow<AppSettings> = store.data.map(::fromPreferences)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = sanitize(transform(fromPreferences(prefs)))
            writeToPreferences(prefs, updated)
        }
    }

    suspend fun resetReaderDefaults() {
        update { cur ->
            val d = AppSettings()
            cur.copy(
                fontKey = d.fontKey,
                fontSizeSp = d.fontSizeSp,
                lineHeightMultiplier = d.lineHeightMultiplier,
                paragraphIndentEm = d.paragraphIndentEm,
                textAlign = d.textAlign,
                marginHorizontalDp = d.marginHorizontalDp,
                themePresetId = d.themePresetId,
                brightness = d.brightness,
                readMode = d.readMode,
                autoScrollSpeed = d.autoScrollSpeed,
            )
        }
    }

    private fun fromPreferences(prefs: Preferences): AppSettings =
        sanitize(
            AppSettings(
                fontKey = prefs[KEY_FONT_KEY] ?: "literata",
                fontSizeSp = prefs[KEY_FONT_SIZE_SP] ?: 20,
                lineHeightMultiplier = prefs[KEY_LINE_HEIGHT] ?: 1.6f,
                paragraphIndentEm = prefs[KEY_PARAGRAPH_INDENT] ?: 1.5f,
                textAlign =
                    prefs[KEY_TEXT_ALIGN]?.let { raw ->
                        runCatching { ReaderTextAlign.valueOf(raw) }.getOrNull()
                    } ?: ReaderTextAlign.JUSTIFY,
                marginHorizontalDp = prefs[KEY_MARGIN_H_DP] ?: 18,
                themePresetId = prefs[KEY_THEME_PRESET] ?: "light",
                customBgHex = prefs[KEY_CUSTOM_BG] ?: "#F6F1E7",
                customFgHex = prefs[KEY_CUSTOM_FG] ?: "#1F1B16",
                brightness = prefs[KEY_BRIGHTNESS] ?: -1f,
                readMode =
                    prefs[KEY_READ_MODE]?.let { raw ->
                        runCatching { ReadMode.valueOf(raw) }.getOrNull()
                    } ?: ReadMode.VERTICAL_SCROLL,
                autoScrollSpeed = prefs[KEY_AUTO_SCROLL_SPEED] ?: 4,
                ttsVoiceName = prefs[KEY_TTS_VOICE] ?: "",
                ttsSpeechRate = prefs[KEY_TTS_RATE] ?: 1.0f,
                ttsPitch = prefs[KEY_TTS_PITCH] ?: 1.0f,
                ttsSleepTimerMinutes = prefs[KEY_TTS_SLEEP_MIN] ?: 0,
                ttsAmbienceEnabled = prefs[KEY_TTS_AMBIENCE_ON] ?: false,
                ttsAmbienceTrack = prefs[KEY_TTS_AMBIENCE_TRACK] ?: "rain",
                ttsAmbienceVolume = prefs[KEY_TTS_AMBIENCE_VOL] ?: 0.25f,
                maxConcurrency = prefs[KEY_MAX_CONCURRENCY] ?: 3,
                requestDelayMs = prefs[KEY_REQUEST_DELAY_MS] ?: 0,
                requestTimeoutMs = prefs[KEY_REQUEST_TIMEOUT_MS] ?: 30_000,
                maxRetriesPerChapter = prefs[KEY_MAX_RETRIES] ?: 2,
                circuitBreakerThreshold = prefs[KEY_CIRCUIT_BREAKER] ?: 8,
                defaultExportFormat =
                    prefs[KEY_EXPORT_FORMAT]?.let { raw ->
                        runCatching { DefaultExportFormat.valueOf(raw) }.getOrNull()
                    } ?: DefaultExportFormat.EPUB3,
                includeCover = prefs[KEY_INCLUDE_COVER] ?: true,
                includeImages = prefs[KEY_INCLUDE_IMAGES] ?: true,
                autoSplitVolumes = prefs[KEY_AUTO_SPLIT] ?: false,
                zipCompressionLevel = prefs[KEY_ZIP_COMPRESSION] ?: 6,
                uiLanguage = prefs[KEY_UI_LANGUAGE] ?: "vi",
            ),
        )

    private fun writeToPreferences(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        s: AppSettings,
    ) {
        prefs[KEY_FONT_KEY] = s.fontKey
        prefs[KEY_FONT_SIZE_SP] = s.fontSizeSp
        prefs[KEY_LINE_HEIGHT] = s.lineHeightMultiplier
        prefs[KEY_PARAGRAPH_INDENT] = s.paragraphIndentEm
        prefs[KEY_TEXT_ALIGN] = s.textAlign.name
        prefs[KEY_MARGIN_H_DP] = s.marginHorizontalDp
        prefs[KEY_THEME_PRESET] = s.themePresetId
        prefs[KEY_CUSTOM_BG] = s.customBgHex
        prefs[KEY_CUSTOM_FG] = s.customFgHex
        prefs[KEY_BRIGHTNESS] = s.brightness
        prefs[KEY_READ_MODE] = s.readMode.name
        prefs[KEY_AUTO_SCROLL_SPEED] = s.autoScrollSpeed
        prefs[KEY_TTS_VOICE] = s.ttsVoiceName
        prefs[KEY_TTS_RATE] = s.ttsSpeechRate
        prefs[KEY_TTS_PITCH] = s.ttsPitch
        prefs[KEY_TTS_SLEEP_MIN] = s.ttsSleepTimerMinutes
        prefs[KEY_TTS_AMBIENCE_ON] = s.ttsAmbienceEnabled
        prefs[KEY_TTS_AMBIENCE_TRACK] = s.ttsAmbienceTrack
        prefs[KEY_TTS_AMBIENCE_VOL] = s.ttsAmbienceVolume
        prefs[KEY_MAX_CONCURRENCY] = s.maxConcurrency
        prefs[KEY_REQUEST_DELAY_MS] = s.requestDelayMs
        prefs[KEY_REQUEST_TIMEOUT_MS] = s.requestTimeoutMs
        prefs[KEY_MAX_RETRIES] = s.maxRetriesPerChapter
        prefs[KEY_CIRCUIT_BREAKER] = s.circuitBreakerThreshold
        prefs[KEY_EXPORT_FORMAT] = s.defaultExportFormat.name
        prefs[KEY_INCLUDE_COVER] = s.includeCover
        prefs[KEY_INCLUDE_IMAGES] = s.includeImages
        prefs[KEY_AUTO_SPLIT] = s.autoSplitVolumes
        prefs[KEY_ZIP_COMPRESSION] = s.zipCompressionLevel
        prefs[KEY_UI_LANGUAGE] = s.uiLanguage
    }

    private fun sanitize(s: AppSettings): AppSettings =
        s.copy(
            fontKey = s.fontKey.ifBlank { "literata" },
            fontSizeSp = s.fontSizeSp.coerceIn(12, 32),
            lineHeightMultiplier = s.lineHeightMultiplier.coerceIn(1.2f, 2.4f),
            paragraphIndentEm = s.paragraphIndentEm.coerceIn(0f, 3f),
            marginHorizontalDp = s.marginHorizontalDp.coerceIn(8, 48),
            brightness = if (s.brightness < 0f) -1f else s.brightness.coerceIn(0.05f, 1f),
            autoScrollSpeed = s.autoScrollSpeed.coerceIn(1, 10),
            ttsSpeechRate = s.ttsSpeechRate.coerceIn(0.5f, 2.5f),
            ttsPitch = s.ttsPitch.coerceIn(0.5f, 2.0f),
            ttsSleepTimerMinutes = if (s.ttsSleepTimerMinutes < 0) 0 else s.ttsSleepTimerMinutes,
            ttsAmbienceVolume = s.ttsAmbienceVolume.coerceIn(0f, 1f),
            maxConcurrency = s.maxConcurrency.coerceIn(1, 6),
            requestDelayMs = s.requestDelayMs.coerceIn(0, 5_000),
            requestTimeoutMs = s.requestTimeoutMs.coerceIn(5_000, 120_000),
            maxRetriesPerChapter = s.maxRetriesPerChapter.coerceIn(0, 5),
            circuitBreakerThreshold = s.circuitBreakerThreshold.coerceIn(3, 20),
            zipCompressionLevel = s.zipCompressionLevel.coerceIn(1, 9),
            uiLanguage = if (s.uiLanguage == "en") "en" else "vi",
        )

    companion object {
        private val KEY_FONT_KEY = stringPreferencesKey("reader_font_key")
        private val KEY_FONT_SIZE_SP = intPreferencesKey("reader_font_size_sp")
        private val KEY_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        private val KEY_PARAGRAPH_INDENT = floatPreferencesKey("reader_paragraph_indent")
        private val KEY_TEXT_ALIGN = stringPreferencesKey("reader_text_align")
        private val KEY_MARGIN_H_DP = intPreferencesKey("reader_margin_h_dp")
        private val KEY_THEME_PRESET = stringPreferencesKey("reader_theme_preset")
        private val KEY_CUSTOM_BG = stringPreferencesKey("reader_custom_bg")
        private val KEY_CUSTOM_FG = stringPreferencesKey("reader_custom_fg")
        private val KEY_BRIGHTNESS = floatPreferencesKey("reader_brightness")
        private val KEY_READ_MODE = stringPreferencesKey("reader_read_mode")
        private val KEY_AUTO_SCROLL_SPEED = intPreferencesKey("reader_auto_scroll_speed")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice_name")
        private val KEY_TTS_RATE = floatPreferencesKey("tts_speech_rate")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val KEY_TTS_SLEEP_MIN = intPreferencesKey("tts_sleep_timer_min")
        private val KEY_TTS_AMBIENCE_ON = booleanPreferencesKey("tts_ambience_enabled")
        private val KEY_TTS_AMBIENCE_TRACK = stringPreferencesKey("tts_ambience_track")
        private val KEY_TTS_AMBIENCE_VOL = floatPreferencesKey("tts_ambience_vol")
        private val KEY_MAX_CONCURRENCY = intPreferencesKey("dl_max_concurrency")
        private val KEY_REQUEST_DELAY_MS = intPreferencesKey("dl_request_delay_ms")
        private val KEY_REQUEST_TIMEOUT_MS = intPreferencesKey("dl_request_timeout_ms")
        private val KEY_MAX_RETRIES = intPreferencesKey("dl_max_retries")
        private val KEY_CIRCUIT_BREAKER = intPreferencesKey("dl_circuit_breaker")
        private val KEY_EXPORT_FORMAT = stringPreferencesKey("export_default_format")
        private val KEY_INCLUDE_COVER = booleanPreferencesKey("export_include_cover")
        private val KEY_INCLUDE_IMAGES = booleanPreferencesKey("export_include_images")
        private val KEY_AUTO_SPLIT = booleanPreferencesKey("export_auto_split")
        private val KEY_ZIP_COMPRESSION = intPreferencesKey("export_zip_compression")
        private val KEY_UI_LANGUAGE = stringPreferencesKey("ui_language")
    }
}
