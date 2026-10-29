package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import kotlin.math.roundToInt

@Composable
internal fun ReaderFloatingActions(autoActive: Boolean, speechActive: Boolean, onAuto: () -> Unit, onSpeech: () -> Unit,
    modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("自动阅读", Icons.Outlined.Timer, onAuto), Triple("朗读", Icons.Outlined.Headphones, onSpeech)).forEachIndexed { index, item ->
            Surface(shape = appShape(24.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                IconButton(onClick = item.third, modifier = Modifier.size(46.dp)
                    .testTag(if (index == 0) "reader_auto_floating_action" else "reader_speech_floating_action")) {
                    AppIcon(item.second, item.first, Modifier.size(23.dp), tint = if (index == 1 && speechActive) Color(0xFFE35858)
                        else if (index == 0 && autoActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
internal fun ReaderAutoControls(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit, enabled: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        val scroll = settings.readingMode == ReadingMode.SCROLL
        Text(if (scroll) "自动滚动" else "自动翻页", style = MaterialTheme.typography.titleSmall)
        if (scroll) ReaderAdjustableSlider("速度", "自动滚动速度", settings.autoScrollSpeed, 8f..160f, 4f,
            { onSettings(settings.copy(autoScrollSpeed = it)) }, { "${it.roundToInt()}/秒" })
        else ReaderAdjustableSlider("间隔", "自动翻页间隔", settings.autoPageSeconds, 2f..120f, 1f,
            { onSettings(settings.copy(autoPageSeconds = it)) }, { "${it.roundToInt()}秒" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("关闭") }
            if (enabled) TextButton(onClick = onStop, modifier = Modifier.testTag("reader_auto_stop")) { Text("停止") }
            TextButton(onClick = onStart, modifier = Modifier.testTag("reader_auto_start")) { Text(if (enabled) "继续" else "开始") }
        }
    }
}
