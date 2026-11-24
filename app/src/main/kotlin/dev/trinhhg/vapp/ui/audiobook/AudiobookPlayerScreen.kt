package dev.trinhhg.vapp.ui.audiobook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.trinhhg.vapp.downloader.BookLibraryRepository
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.settings.AppSettingsRepository
import dev.trinhhg.vapp.tts.AudiobookControllerImpl
import dev.trinhhg.vapp.tts.api.AudiobookController
import dev.trinhhg.vapp.tts.api.TtsVoiceInfo
import dev.trinhhg.vapp.tts.audio.AmbienceAudioMixer
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class PlayerSheetType { TOC, SLEEP, SPEED, AMBIENCE, VOICE }

/**
 * Offline TTS Audiobook player screen modeled on reference screenshot #1.
 */
@Composable
fun AudiobookPlayerScreen(
    bookId: String? = null,
    initialChapterIndex: Int? = null,
    initialParagraphIndex: Int = 0,
    controller: AudiobookController = koinInject(),
    libraryRepo: BookLibraryRepository = koinInject(),
    settingsRepo: AppSettingsRepository = koinInject(),
    onCollapse: () -> Unit = {},
    onClose: () -> Unit = {},
    onDownloadBookClick: (String) -> Unit = {},
) {
    val impl = controller as? AudiobookControllerImpl
    val state by controller.playbackState.collectAsState()
    val payload by (impl?.currentPayload?.collectAsState() ?: remember { mutableStateOf(null) })
    val stopAfterChapter by (impl?.stopAfterChapter?.collectAsState() ?: remember { mutableStateOf(false) })
    val selectedVoiceId by (impl?.selectedVoiceId?.collectAsState() ?: remember { mutableStateOf(null) })
    val settings by settingsRepo.settings.collectAsState(
        initial =
            dev.trinhhg.vapp.settings
                .AppSettings(),
    )

    var activeSheet by remember { mutableStateOf<PlayerSheetType?>(null) }
    var chapters by remember { mutableStateOf<List<ChapterEntity>>(emptyList()) }
    var voices by remember { mutableStateOf<List<TtsVoiceInfo>>(emptyList()) }

    LaunchedEffect(bookId, initialChapterIndex) {
        if (bookId != null && (state.bookId != bookId || initialChapterIndex != null)) {
            controller.playChapter(bookId, initialChapterIndex ?: 0, initialParagraphIndex)
        }
        val activeId = bookId ?: state.bookId
        if (activeId != null) {
            chapters = libraryRepo.getChapters(activeId)
        }
    }

    LaunchedEffect(activeSheet) {
        if (activeSheet == PlayerSheetType.VOICE && voices.isEmpty() && impl != null) {
            voices = impl.listVoices()
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val bgBrush =
        Brush.verticalGradient(
            listOf(Color(0xFFF3EFF6), Color(0xFFF6F6F9), Color(0xFFEAEFF4)),
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(bgBrush)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopPlayerBar(
            onCollapse = onCollapse,
            onDownload = {
                val id = bookId ?: state.bookId ?: return@TopPlayerBar
                onDownloadBookClick(id)
            },
            onClose = {
                impl?.stop() ?: controller.pause()
                onClose()
            },
        )

        Spacer(Modifier.weight(0.5f))

        CoverArtCard(
            coverPath = payload?.coverPath,
            bookTitle = state.bookTitle.ifBlank { "Sách nói offline" },
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = state.bookTitle.ifBlank { "Chưa chọn sách" },
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF1F2329),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = payload?.author?.takeIf { it.isNotBlank() } ?: "vapp Offline TTS",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6B7280),
            maxLines = 1,
        )

        Spacer(Modifier.height(16.dp))

        val maxIdx = (state.totalParagraphs - 1).coerceAtLeast(1)
        Slider(
            value = state.currentParagraph.toFloat().coerceIn(0f, maxIdx.toFloat()),
            onValueChange = { pos -> controller.seekToParagraph(pos.toInt()) },
            valueRange = 0f..maxIdx.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text =
                state.chapterTitle.ifBlank { "CHƯƠNG ${state.chapterIndex + 1}" }.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF4B5563),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFE7E8EC),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(14.dp))
                Text("Đọc trực tiếp", style = MaterialTheme.typography.labelMedium)
            }
        }

        state.errorMessage?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(22.dp))

        TransportControlsRow(
            isPlaying = state.isPlaying,
            onRewindParagraphs = { controller.seekToParagraph((state.currentParagraph - 3).coerceAtLeast(0)) },
            onPrevChapter = { controller.skipChapter(-1) },
            onTogglePlay = { if (state.isPlaying) controller.pause() else controller.resume() },
            onNextChapter = { controller.skipChapter(1) },
            onForwardParagraphs = { controller.seekToParagraph(state.currentParagraph + 3) },
        )

        Spacer(Modifier.weight(1f))

        BottomActionDock(
            sleepLabel =
                when {
                    stopAfterChapter -> "Hết ch."
                    state.sleepTimerRemainingMinutes != null -> "${state.sleepTimerRemainingMinutes}p"
                    else -> "Hẹn giờ"
                },
            speedLabel = "%.2fx".format(java.util.Locale.US, state.speechRate),
            onToc = { activeSheet = PlayerSheetType.TOC },
            onSleep = { activeSheet = PlayerSheetType.SLEEP },
            onSpeed = { activeSheet = PlayerSheetType.SPEED },
            onAmbience = { activeSheet = PlayerSheetType.AMBIENCE },
            onVoice = { activeSheet = PlayerSheetType.VOICE },
        )
    }

    when (activeSheet) {
        PlayerSheetType.TOC -> {
            PlayerTocSheet(
                chapters = chapters,
                currentChapterIndex = state.chapterIndex,
                onSelect = { idx ->
                    val id = bookId ?: state.bookId ?: return@PlayerTocSheet
                    activeSheet = null
                    controller.playChapter(id, idx, 0)
                },
                onDismiss = { activeSheet = null },
            )
        }
        PlayerSheetType.SLEEP -> {
            PlayerSleepSheet(
                activeMinutes = state.sleepTimerRemainingMinutes,
                stopAfterChapter = stopAfterChapter,
                onSelect = { mins ->
                    controller.setSleepTimer(mins)
                    scope.launch {
                        settingsRepo.update { it.copy(ttsSleepTimerMinutes = mins ?: 0) }
                    }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        PlayerSheetType.SPEED -> {
            PlayerSpeedPitchSheet(
                speed = state.speechRate,
                pitch = state.pitch,
                onSpeedChange = { rate ->
                    controller.setSpeed(rate)
                    scope.launch { settingsRepo.update { it.copy(ttsSpeechRate = rate) } }
                },
                onPitchChange = { p ->
                    impl?.setPitch(p)
                    scope.launch { settingsRepo.update { it.copy(ttsPitch = p) } }
                },
                onDismiss = { activeSheet = null },
            )
        }
        PlayerSheetType.AMBIENCE -> {
            PlayerAmbienceSheet(
                selectedTrack = state.ambienceTrackId ?: AmbienceAudioMixer.TRACK_NONE,
                volume = state.ambienceVolume,
                onApply = { track, vol ->
                    val resolved = if (track == AmbienceAudioMixer.TRACK_NONE) null else track
                    controller.setAmbience(resolved, vol)
                    scope.launch {
                        settingsRepo.update {
                            it.copy(
                                ttsAmbienceEnabled = resolved != null,
                                ttsAmbienceTrack = track,
                                ttsAmbienceVolume = vol,
                            )
                        }
                    }
                },
                onDismiss = { activeSheet = null },
            )
        }
        PlayerSheetType.VOICE -> {
            PlayerVoiceSheet(
                voices = voices,
                selectedVoiceId = selectedVoiceId ?: settings.ttsVoiceName,
                onSelect = { voiceId ->
                    impl?.setVoice(voiceId)
                    scope.launch { settingsRepo.update { it.copy(ttsVoiceName = voiceId) } }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        null -> Unit
    }
}

@Composable
private fun TopPlayerBar(
    onCollapse: () -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Thu gọn")
        }
        Row {
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Tải truyện")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Đóng")
            }
        }
    }
}

@Composable
private fun CoverArtCard(
    coverPath: String?,
    bookTitle: String,
) {
    Surface(
        modifier = Modifier.size(196.dp),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        color = Color(0xFF28507A),
    ) {
        val file = coverPath?.let { File(it) }?.takeIf { it.exists() }
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = bookTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bookTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TransportControlsRow(
    isPlaying: Boolean,
    onRewindParagraphs: () -> Unit,
    onPrevChapter: () -> Unit,
    onTogglePlay: () -> Unit,
    onNextChapter: () -> Unit,
    onForwardParagraphs: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRewindParagraphs) {
            Icon(Icons.Default.FastRewind, contentDescription = "Lùi đoạn", modifier = Modifier.size(28.dp))
        }
        IconButton(onClick = onPrevChapter) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Chương trước", modifier = Modifier.size(30.dp))
        }
        ScallopedPlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlay)
        IconButton(onClick = onNextChapter) {
            Icon(Icons.Default.SkipNext, contentDescription = "Chương tiếp", modifier = Modifier.size(30.dp))
        }
        IconButton(onClick = onForwardParagraphs) {
            Icon(Icons.Default.FastForward, contentDescription = "Tới đoạn", modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ScallopedPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lobes = 12
            val outerR = size.minDimension / 2f
            val innerR = outerR * 0.88f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val path = Path()
            val steps = lobes * 12
            for (i in 0..steps) {
                val angle = (2.0 * PI * i) / steps
                val wave = (cos(lobes * angle) + 1.0) / 2.0
                val r = innerR + (outerR - innerR) * wave.toFloat()
                val x = cx + r * cos(angle).toFloat()
                val y = cy + r * sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path = path, color = Color(0xFF28507A))
        }
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Tạm dừng" else "Phát",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun BottomActionDock(
    sleepLabel: String,
    speedLabel: String,
    onToc: () -> Unit,
    onSleep: () -> Unit,
    onSpeed: () -> Unit,
    onAmbience: () -> Unit,
    onVoice: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DockActionItem(Icons.AutoMirrored.Filled.QueueMusic, "Mục lục", onToc)
            DockActionItem(Icons.Default.Timer, sleepLabel, onSleep)
            DockActionItem(Icons.Default.Speed, speedLabel, onSpeed)
            DockActionItem(Icons.Default.MusicNote, "Nhạc nền", onAmbience)
            DockActionItem(Icons.Default.Settings, "Cài đặt", onVoice)
        }
    }
}

@Composable
private fun DockActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = Color(0xFF374151))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF374151), maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTocSheet(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Mục lục (${chapters.size} chương)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.height(320.dp)) {
                itemsIndexed(chapters) { idx, ch ->
                    val active = idx == currentChapterIndex
                    Text(
                        text = ch.title,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(idx) }.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSleepSheet(
    activeMinutes: Int?,
    stopAfterChapter: Boolean,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options: List<Pair<String, Int?>> =
        listOf(
            "Tắt hẹn giờ" to null,
            "15 phút" to 15,
            "30 phút" to 30,
            "45 phút" to 45,
            "60 phút" to 60,
            "Hết chương hiện tại" to AudiobookControllerImpl.SLEEP_END_OF_CHAPTER,
        )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hẹn giờ tắt", style = MaterialTheme.typography.titleMedium)
            options.forEach { (label, mins) ->
                val selected =
                    (mins == null && activeMinutes == null && !stopAfterChapter) ||
                        (mins == AudiobookControllerImpl.SLEEP_END_OF_CHAPTER && stopAfterChapter) ||
                        (mins != null && mins > 0 && mins == activeMinutes)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(mins) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSpeedPitchSheet(
    speed: Float,
    pitch: Float,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Tốc độ đọc: ${"%.2fx".format(java.util.Locale.US, speed)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(value = speed, onValueChange = onSpeedChange, valueRange = 0.5f..2.5f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0f, 1.25f, 1.30f, 1.5f, 2.0f).forEach { preset ->
                    FilterChip(
                        selected = kotlin.math.abs(speed - preset) < 0.03f,
                        onClick = { onSpeedChange(preset) },
                        label = { Text("%.2fx".format(java.util.Locale.US, preset)) },
                    )
                }
            }
            Text(
                "Cao độ giọng (Pitch): ${"%.2f".format(java.util.Locale.US, pitch)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(value = pitch, onValueChange = onPitchChange, valueRange = 0.5f..2.0f)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerAmbienceSheet(
    selectedTrack: String,
    volume: Float,
    onApply: (String, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val tracks =
        listOf(
            AmbienceAudioMixer.TRACK_NONE to "Tắt nhạc nền",
            AmbienceAudioMixer.TRACK_RAIN to "Tiếng mưa rơi",
            AmbienceAudioMixer.TRACK_CAFE to "Quán cà phê êm",
            AmbienceAudioMixer.TRACK_FOREST to "Gió rừng ban đêm",
            AmbienceAudioMixer.TRACK_WHITE_NOISE to "White Noise tập trung",
        )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nhạc nền offline", style = MaterialTheme.typography.titleMedium)
            tracks.forEach { (id, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onApply(id, volume) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, fontWeight = if (selectedTrack == id) FontWeight.Bold else FontWeight.Normal)
                    if (selectedTrack == id) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Âm lượng nhạc nền: ${(volume * 100).toInt()}%")
            Slider(value = volume, onValueChange = { v -> onApply(selectedTrack, v) }, valueRange = 0f..1f)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerVoiceSheet(
    voices: List<TtsVoiceInfo>,
    selectedVoiceId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Giọng đọc hệ thống Android (${voices.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (voices.isEmpty()) {
                Text(
                    "Đang tải hoặc thiết bị chưa cài đặt dữ liệu giọng TTS.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(voices) { v ->
                        val active = v.id == selectedVoiceId
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(v.id) }.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(v.name, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                                Text(
                                    text = "${v.localeTag} • " + if (v.isNetworkConnectionRequired) "Cần mạng" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (active) {
                                Spacer(Modifier.width(8.dp))
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
