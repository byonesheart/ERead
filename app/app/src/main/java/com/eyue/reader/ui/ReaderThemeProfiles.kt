package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val rowState = rememberLazyListState()
    LaunchedEffect(settings.appearanceProfiles, settings.activeAppearanceProfileId, editingProfileId) {
        val index = settings.appearanceProfiles.indexOfFirst { it.id == settings.activeAppearanceProfileId }
        if (index >= 0 && (rowState.firstVisibleItemIndex != index || rowState.firstVisibleItemScrollOffset != 0)) rowState.requestScrollToItem(index)
    }
    ReaderSettingSection("配置", trailing = {
        Text(if (editingProfileId == null) "长按管理" else "点选配置结束管理", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(Modifier.weight(1f), state = rowState, horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
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
            ReaderSettingChoice("新建", false, onNewProfile, Modifier.testTag("reader_new_theme"),
                role = androidx.compose.ui.semantics.Role.Button, leading = { Icon(Icons.Outlined.Add, null, Modifier.size(14.dp)) })
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
    val shape = appShape(6.dp)
    val colors = MaterialTheme.colorScheme
    Surface(color = if (selected) colors.primaryContainer else colors.surface, shape = shape,
        border = BorderStroke(if (selected) 1.5.dp else .5.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "编辑${profile.name}")
            .semantics { this.selected = selected }
            .testTag("reader_theme_${profile.id}")) {
        Row(Modifier.heightIn(min = 34.dp).padding(start = 8.dp, end = if (editing) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(13.dp).clip(appShape(3.dp)).background(color).border(.5.dp, colors.outlineVariant, appShape(3.dp))) {
                bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Text(profile.name, Modifier.widthIn(max = 84.dp), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface)
            if (editing) IconButton(onClick = onDelete, modifier = Modifier.size(26.dp).testTag("reader_delete_theme_${profile.id}")) {
                Icon(Icons.Outlined.Close, "删除${profile.name}", Modifier.size(13.dp), tint = colors.onSurfaceVariant)
            } else if (selected) Icon(Icons.Outlined.Check, "当前配置", Modifier.size(13.dp), tint = colors.primary)
        }
    }
}
