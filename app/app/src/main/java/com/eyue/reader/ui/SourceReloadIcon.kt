package com.eyue.reader.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun SourceReloadIcon(bookId: String) {
    val transition = rememberInfiniteTransition(label = "source reload")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "reload rotation")
    AppIcon(Icons.Outlined.Sync, "正在重载书籍", Modifier.size(19.dp)
        .graphicsLayer { rotationZ = angle }.testTag("source_reload_progress_$bookId"), tint = Olive)
}
