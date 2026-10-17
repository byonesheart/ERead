package com.eyue.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.BitmapLoader
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReaderThemeProfile
import com.eyue.reader.data.appearanceSnapshot
import com.eyue.reader.data.applyAppearanceProfile
import com.eyue.reader.data.saveAppearanceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ReaderThemeProfiles(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("主题", Modifier.width(34.dp), fontSize = 12.sp)
        LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            items(settings.appearanceProfiles, key = { it.id }) { profile ->
                ReaderThemeThumbnail(profile, settings.activeAppearanceProfileId == profile.id &&
                    settings.appearanceSnapshot() == profile.appearance) { onSettings(settings.applyAppearanceProfile(profile)) }
            }
        }
        IconButton(onClick = { onSettings(settings.saveAppearanceProfile()) }, modifier = Modifier.size(44.dp)
            .testTag("reader_save_theme")) { Icon(Icons.Outlined.Add, "保存当前界面为主题") }
    }
}

@Composable
private fun ReaderThemeThumbnail(profile: ReaderThemeProfile, selected: Boolean, onClick: () -> Unit) {
    val background = profile.appearance.background
    val path = profile.appearance.backgroundImagePath.takeIf { background == ReaderBackground.IMAGE }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { path?.let { runCatching {
            BitmapLoader.decodeBounded(File(it), 144, 144, 20_736)?.asImageBitmap()
        }.getOrNull() } }
    }
    val color = when (background) {
        ReaderBackground.WHITE -> Color.White
        ReaderBackground.GREEN -> Color(0xFFE5EBD9)
        ReaderBackground.DARK -> Color(0xFF141B15)
        else -> Color(0xFFF6F6EF)
    }
    val shape = remember { RoundedCornerShape(8.dp) }
    Box(Modifier.padding(end = 6.dp).width(62.dp).height(52.dp).clip(shape).background(color)
        .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(onClick = onClick).testTag("reader_theme_${profile.id}"), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(profile.name, Modifier.background(Color.White.copy(alpha = .85f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.Black)
    }
}
