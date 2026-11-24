package dev.trinhhg.vapp.ui.audiobook

import androidx.compose.runtime.Composable

/**
 * Reserved P5+ slot for optional open-source local neural voice cloning (kept hidden/disabled in P1-P5).
 */
@Composable
fun VoiceModelManagerPlaceholder(visible: Boolean = false) {
    if (!visible) return
}
