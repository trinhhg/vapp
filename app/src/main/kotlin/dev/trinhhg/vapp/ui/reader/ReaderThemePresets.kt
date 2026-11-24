package dev.trinhhg.vapp.ui.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.ReaderTextAlign
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ReaderThemePreset(
    val id: String,
    val label: String,
    val background: Color,
    val foreground: Color,
    val isDark: Boolean,
)

data class ReaderFontOption(
    val key: String,
    val label: String,
    val isCustom: Boolean = false,
)

object ReaderThemePresets {
    val presets: List<ReaderThemePreset> =
        listOf(
            ReaderThemePreset("light", "Sáng", Color(0xFFFAFAFA), Color(0xFF1F1B16), isDark = false),
            ReaderThemePreset("sepia", "Giấy ấm", Color(0xFFEAE0C8), Color(0xFF3B3124), isDark = false),
            ReaderThemePreset("blue", "Xanh dịu", Color(0xFFCBE0F4), Color(0xFF162638), isDark = false),
            ReaderThemePreset("pink", "Hồng nhạt", Color(0xFFF5D3DE), Color(0xFF381824), isDark = false),
            ReaderThemePreset("green", "Xanh lá", Color(0xFFD5ECD7), Color(0xFF19301C), isDark = false),
            ReaderThemePreset("dark", "Tối", Color(0xFF1E2023), Color(0xFFD8DCE2), isDark = true),
            ReaderThemePreset("oled", "OLED", Color(0xFF000000), Color(0xFFE6E6E6), isDark = true),
        )

    fun resolve(settings: AppSettings): ReaderThemePreset {
        if (settings.themePresetId == "custom") {
            val bg = parseHexColor(settings.customBgHex, Color(0xFFF6F1E7))
            val fg = parseHexColor(settings.customFgHex, Color(0xFF1F1B16))
            val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
            return ReaderThemePreset("custom", "Tùy chỉnh", bg, fg, isDark = luminance < 0.45f)
        }
        return presets.firstOrNull { it.id == settings.themePresetId } ?: presets.first()
    }

    fun parseHexColor(
        hex: String,
        fallback: Color,
    ): Color =
        runCatching {
            val clean = hex.trim().removePrefix("#")
            val parsed = clean.toLong(16)
            when (clean.length) {
                6 -> Color((0xFF000000L or parsed).toInt())
                8 -> Color(parsed.toInt())
                else -> fallback
            }
        }.getOrDefault(fallback)
}

object ReaderFontRegistry {
    private val bundledMap =
        mapOf(
            "literata" to Pair("Literata", "fonts/literata_400.ttf"),
            "noticia" to Pair("Noticia Text", "fonts/noticia_text_400.ttf"),
            "hanken" to Pair("Hanken Grotesk", "fonts/hanken_grotesk_400.ttf"),
            "roboto" to Pair("Roboto", "fonts/roboto_400.ttf"),
            "mono" to Pair("JetBrains Mono", "fonts/jetbrains_mono_400.ttf"),
        )

    private val cache = ConcurrentHashMap<String, FontFamily>()

    fun fontsDir(context: Context): File {
        val dir = context.filesDir.resolve("vapp/fonts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listAvailableFonts(context: Context): List<ReaderFontOption> {
        val builtIn = bundledMap.map { (k, pair) -> ReaderFontOption(k, pair.first, isCustom = false) }
        val custom =
            fontsDir(context)
                .listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true)) }
                ?.sortedBy { it.name.lowercase() }
                ?.map { f ->
                    ReaderFontOption(
                        key = "file:${f.name}",
                        label = f.nameWithoutExtension,
                        isCustom = true,
                    )
                }.orEmpty()
        return builtIn + custom
    }

    fun resolveFontFamily(
        context: Context,
        fontKey: String,
    ): FontFamily {
        cache[fontKey]?.let { return it }
        val typeface =
            runCatching {
                if (fontKey.startsWith("file:")) {
                    val fileName = fontKey.removePrefix("file:")
                    val target = File(fontsDir(context), fileName)
                    if (target.exists()) Typeface.createFromFile(target) else null
                } else {
                    val assetPath = bundledMap[fontKey]?.second ?: bundledMap["literata"]?.second
                    if (assetPath != null) Typeface.createFromAsset(context.assets, assetPath) else null
                }
            }.getOrNull()
        val family = if (typeface != null) FontFamily(typeface) else FontFamily.Serif
        cache[fontKey] = family
        return family
    }

    fun importFontFromUri(
        context: Context,
        uri: Uri,
    ): Result<ReaderFontOption> =
        runCatching {
            val resolver = context.contentResolver
            var displayName = "custom_font_${System.currentTimeMillis()}.ttf"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val raw = cursor.getString(idx)
                        if (!raw.isNullOrBlank()) displayName = raw
                    }
                }
            }
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(fontsDir(context), safeName)
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: error("Không đọc được file font đã chọn")
            val key = "file:$safeName"
            cache.remove(key)
            ReaderFontOption(key = key, label = dest.nameWithoutExtension, isCustom = true)
        }
}

fun ReaderTextAlign.toComposeTextAlign(): TextAlign =
    when (this) {
        ReaderTextAlign.LEFT -> TextAlign.Start
        ReaderTextAlign.CENTER -> TextAlign.Center
        ReaderTextAlign.RIGHT -> TextAlign.End
        ReaderTextAlign.JUSTIFY -> TextAlign.Justify
    }
