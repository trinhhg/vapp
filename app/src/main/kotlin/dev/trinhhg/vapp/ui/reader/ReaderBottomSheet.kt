package dev.trinhhg.vapp.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.ReadMode
import dev.trinhhg.vapp.settings.ReaderTextAlign
import kotlin.math.roundToInt

/**
 * Reader appearance & layout customization bottom sheet matching reference screenshot #3:
 * quick day/night toggle, brightness slider, circular background swatches + custom picker,
 * `A-`/`A+`/font selector (with `.ttf`/`.otf` import), scroll/slide/curl turn mode,
 * paragraph indent, 4-way alignment, and reset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderBottomSheet(
    settings: AppSettings,
    availableFonts: List<ReaderFontOption>,
    chapterProgressPercent: Int,
    autoScrollActive: Boolean,
    onDismiss: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onToggleAutoScroll: () -> Unit,
    onImportFontClick: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    var showFontDialog by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showBrightnessRow by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBrightnessAndModeRow(
                settings = settings,
                chapterProgressPercent = chapterProgressPercent,
                autoScrollActive = autoScrollActive,
                onToggleDayNight = {
                    val isCurrentlyDark = settings.themePresetId == "dark" || settings.themePresetId == "oled"
                    onUpdateSettings { it.copy(themePresetId = if (isCurrentlyDark) "light" else "dark") }
                },
                onToggleBrightnessSlider = { showBrightnessRow = !showBrightnessRow },
                onToggleAutoScroll = onToggleAutoScroll,
            )

            if (showBrightnessRow) {
                BrightnessSliderRow(
                    brightness = settings.brightness,
                    onBrightnessChange = { v -> onUpdateSettings { it.copy(brightness = v) } },
                )
            }

            ColorSwatchesRow(
                selectedPresetId = settings.themePresetId,
                onSelectPreset = { id -> onUpdateSettings { it.copy(themePresetId = id) } },
                onOpenCustomColor = { showCustomColorDialog = true },
            )

            FontSizeAndFamilyRow(
                fontSizeSp = settings.fontSizeSp,
                currentFontLabel =
                    availableFonts.firstOrNull { it.key == settings.fontKey }?.label ?: "Literata",
                onDecrease = { onUpdateSettings { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(12)) } },
                onIncrease = { onUpdateSettings { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(32)) } },
                onOpenFonts = { showFontDialog = true },
            )

            ReadModeRow(
                readMode = settings.readMode,
                autoScrollSpeed = settings.autoScrollSpeed,
                onSelectMode = { mode -> onUpdateSettings { it.copy(readMode = mode) } },
                onCycleSpeed = {
                    onUpdateSettings { s -> s.copy(autoScrollSpeed = if (s.autoScrollSpeed >= 10) 1 else s.autoScrollSpeed + 1) }
                },
            )

            IndentSliderRow(
                indentEm = settings.paragraphIndentEm,
                onChange = { v -> onUpdateSettings { it.copy(paragraphIndentEm = v) } },
            )

            AlignmentAndResetRow(
                selectedAlign = settings.textAlign,
                onSelectAlign = { align -> onUpdateSettings { it.copy(textAlign = align) } },
                onReset = onResetDefaults,
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showFontDialog) {
        FontPickerDialog(
            fonts = availableFonts,
            selectedKey = settings.fontKey,
            onSelect = { key ->
                onUpdateSettings { it.copy(fontKey = key) }
                showFontDialog = false
            },
            onImportClick = {
                showFontDialog = false
                onImportFontClick()
            },
            onDismiss = { showFontDialog = false },
        )
    }

    if (showCustomColorDialog) {
        CustomColorPickerDialog(
            initialBg = settings.customBgHex,
            initialFg = settings.customFgHex,
            onApply = { bgHex, fgHex ->
                onUpdateSettings {
                    it.copy(
                        themePresetId = "custom",
                        customBgHex = bgHex,
                        customFgHex = fgHex,
                    )
                }
                showCustomColorDialog = false
            },
            onDismiss = { showCustomColorDialog = false },
        )
    }
}

@Composable
private fun TopBrightnessAndModeRow(
    settings: AppSettings,
    chapterProgressPercent: Int,
    autoScrollActive: Boolean,
    onToggleDayNight: () -> Unit,
    onToggleBrightnessSlider: () -> Unit,
    onToggleAutoScroll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onToggleDayNight) {
            Icon(Icons.Default.Contrast, contentDescription = "Sáng / Tối")
        }
        Surface(
            modifier =
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clickable(onClick = onToggleBrightnessSlider),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("$chapterProgressPercent%", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Brightness6, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (settings.brightness < 0f) "Hệ thống" else "${(settings.brightness * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        IconButton(onClick = onToggleAutoScroll) {
            Icon(
                Icons.Default.Visibility,
                contentDescription = "Tự cuộn",
                tint = if (autoScrollActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BrightnessSliderRow(
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Độ sáng", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = if (brightness < 0f) 0.5f else brightness,
            onValueChange = { onBrightnessChange(it.coerceIn(0.05f, 1f)) },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onBrightnessChange(-1f) }) { Text("Auto") }
    }
}

@Composable
private fun ColorSwatchesRow(
    selectedPresetId: String,
    onSelectPreset: (String) -> Unit,
    onOpenCustomColor: () -> Unit,
) {
    val swatchIds = listOf("light", "sepia", "blue", "pink", "green")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(20.dp))
        swatchIds.forEach { id ->
            val preset = ReaderThemePresets.presets.first { it.id == id }
            val selected = selectedPresetId == id
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(preset.background)
                        .border(
                            width = if (selected) 2.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.45f),
                            shape = CircleShape,
                        ).clickable { onSelectPreset(id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Aa",
                    color = preset.foreground,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        IconButton(onClick = onOpenCustomColor) {
            Icon(Icons.Default.Colorize, contentDescription = "Màu tùy chỉnh")
        }
    }
}

@Composable
private fun FontSizeAndFamilyRow(
    fontSizeSp: Int,
    currentFontLabel: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onOpenFonts: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Aa", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onDecrease, modifier = Modifier.height(36.dp)) {
            Text("A-")
        }
        Text(
            text = "$fontSizeSp.0",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        OutlinedButton(onClick = onIncrease, modifier = Modifier.height(36.dp)) {
            Text("A+")
        }
        OutlinedButton(
            onClick = onOpenFonts,
            modifier =
                Modifier
                    .weight(1f)
                    .height(36.dp),
        ) {
            Text(currentFontLabel, maxLines = 1)
        }
    }
}

@Composable
private fun ReadModeRow(
    readMode: ReadMode,
    autoScrollSpeed: Int,
    onSelectMode: (ReadMode) -> Unit,
    onCycleSpeed: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onCycleSpeed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapVert, contentDescription = "Tốc độ tự cuộn", modifier = Modifier.size(18.dp))
                Text("$autoScrollSpeed", style = MaterialTheme.typography.labelSmall)
            }
        }
        FilterChip(
            selected = readMode == ReadMode.PAGED_SLIDE,
            onClick = { onSelectMode(ReadMode.PAGED_SLIDE) },
            label = { Text("Trượt") },
        )
        FilterChip(
            selected = readMode == ReadMode.PAGED_CURL,
            onClick = { onSelectMode(ReadMode.PAGED_CURL) },
            label = { Text("Mô phỏng") },
        )
        FilterChip(
            selected = readMode == ReadMode.VERTICAL_SCROLL,
            onClick = { onSelectMode(ReadMode.VERTICAL_SCROLL) },
            label = { Text("Cuộn dọc") },
        )
    }
}

@Composable
private fun IndentSliderRow(
    indentEm: Float,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Thụt lề", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(4.dp))
        Text("%.1f".format(java.util.Locale.US, indentEm), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = indentEm,
            onValueChange = onChange,
            valueRange = 0f..3f,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange((indentEm - 0.5f).coerceAtLeast(0f)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "Giảm thụt lề", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { onChange((indentEm + 0.5f).coerceAtMost(3f)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Tăng thụt lề", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AlignmentAndResetRow(
    selectedAlign: ReaderTextAlign,
    onSelectAlign: (ReaderTextAlign) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Căn lề", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val buttons =
                listOf(
                    ReaderTextAlign.LEFT to Icons.AutoMirrored.Filled.FormatAlignLeft,
                    ReaderTextAlign.CENTER to Icons.Default.FormatAlignCenter,
                    ReaderTextAlign.RIGHT to Icons.AutoMirrored.Filled.FormatAlignRight,
                    ReaderTextAlign.JUSTIFY to Icons.Default.FormatAlignJustify,
                )
            buttons.forEach { (align, icon) ->
                FilterChip(
                    selected = selectedAlign == align,
                    onClick = { onSelectAlign(align) },
                    label = { Icon(icon, contentDescription = align.name, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        TextButton(onClick = onReset) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Đặt lại")
        }
    }
}

@Composable
private fun FontPickerDialog(
    fonts: List<ReaderFontOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onImportClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chọn phông chữ đọc") },
        text = {
            LazyColumn(modifier = Modifier.height(260.dp)) {
                items(fonts) { font ->
                    val isSelected = font.key == selectedKey
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(font.key) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = font.label + if (font.isCustom) " (.ttf/.otf)" else "",
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (isSelected) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImportClick) { Text("Nạp .ttf / .otf") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        },
    )
}

@Composable
private fun CustomColorPickerDialog(
    initialBg: String,
    initialFg: String,
    onApply: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var bgHex by remember { mutableStateOf(initialBg) }
    var fgHex by remember { mutableStateOf(initialFg) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Màu nền & chữ tùy chỉnh") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = bgHex,
                    onValueChange = { bgHex = it },
                    label = { Text("Mã màu nền (#RRGGBB)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = fgHex,
                    onValueChange = { fgHex = it },
                    label = { Text("Mã màu chữ (#RRGGBB)") },
                    singleLine = true,
                )
                val bg = ReaderThemePresets.parseHexColor(bgHex, Color(0xFFF6F1E7))
                val fg = ReaderThemePresets.parseHexColor(fgHex, Color(0xFF1F1B16))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(bg, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                ) {
                    Text("Xem trước chữ tiếng Việt — Hàn Lập", color = fg)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(bgHex.trim(), fgHex.trim()) }) { Text("Áp dụng") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        },
    )
}
