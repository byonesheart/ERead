package com.eyue.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.BitmapLoader
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReaderThemeProfile
import com.eyue.reader.data.applyAppearanceProfile
import com.eyue.reader.data.removeAppearanceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ReaderThemeProfiles(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit,
    editingProfileId: String?, onEditProfile: (String?) -> Unit, onNewProfile: () -> Unit) {
    var deleting by remember { mutableStateOf<ReaderThemeProfile?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("主题", Modifier.width(34.dp), fontSize = 12.sp)
        LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            item {
                OutlinedButton(onClick = onNewProfile,
                    modifier = Modifier.padding(end = 6.dp).width(62.dp).height(56.dp).testTag("reader_new_theme"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp), shape = RoundedCornerShape(8.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text("新建", fontSize = 12.sp)
                    }
                }
            }
            items(settings.appearanceProfiles, key = { it.id }) { profile ->
                ReaderThemeThumbnail(profile, settings.activeAppearanceProfileId == profile.id, editingProfileId != null,
                    onClick = {
                        onEditProfile(null)
                        onSettings(settings.applyAppearanceProfile(profile))
                    }, onLongClick = {
                        onSettings(settings.applyAppearanceProfile(profile))
                        onEditProfile(profile.id)
                    }, onDelete = { deleting = profile })
            }
        }
    }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text("删除${profile.name}？") }, text = { Text("删除后自动选中其他配置；仅剩一个配置时，将保留当前界面为新配置。") },
        confirmButton = { TextButton(onClick = {
            onSettings(settings.removeAppearanceProfile(profile.id))
            if (editingProfileId == profile.id) onEditProfile(null)
            deleting = null
        }, modifier = Modifier.testTag("reader_delete_theme_confirm")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderThemeThumbnail(profile: ReaderThemeProfile, selected: Boolean, editing: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onDelete: () -> Unit) {
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
    Box(Modifier.padding(end = 6.dp).width(62.dp).height(56.dp)) {
        Box(Modifier.fillMaxSize().clip(shape).background(color)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "编辑${profile.name}")
            .semantics { this.selected = selected }
            .testTag("reader_theme_${profile.id}"), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(profile.name, Modifier.padding(horizontal = 3.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (bitmap != null || background == ReaderBackground.DARK) Color.White else Color.Black,
                style = TextStyle(shadow = if (bitmap != null) Shadow(Color.Black, Offset(0f, 1f), 3f) else null))
        }
        if (editing) IconButton(onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp).testTag("reader_delete_theme_${profile.id}")) {
            Icon(Icons.Outlined.Close, "删除${profile.name}", Modifier.size(16.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
