package com.eyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.LibraryMode

/**
 * 小说 / 漫画：两个入口始终可见，选中项用主色文字加一条短下划线。
 * 仍是独立的模式筛选，不是书籍类型标签。
 */
@Composable internal fun XingcaiLibraryModeSwitch(mode: LibraryMode, onMode: (LibraryMode) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.height(44.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        listOf(LibraryMode.NOVEL to "小说", LibraryMode.COMIC to "漫画").forEach { (value, label) ->
            val selected = mode == value
            Column(Modifier.height(44.dp).clip(RoundedCornerShape(8.dp))
                .selectable(selected, role = Role.Tab, onClick = { if (!selected) onMode(value) })
                .testTag(if (value == LibraryMode.NOVEL) "mode_novel" else "mode_comic"),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 15.sp, maxLines = 1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(20.dp).height(3.dp)
                    .background(if (selected) colors.primary else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(1.5.dp)))
            }
            if (value == LibraryMode.NOVEL) Spacer(Modifier.width(18.dp))
        }
    }
}

@Composable internal fun XingcaiModeTabs(mode: LibraryMode, onMode: (LibraryMode) -> Unit, onNotes: () -> Unit,
    onImport: () -> Unit = {}, importEnabled: Boolean = true, nightMode: Boolean = false, onToggleNight: () -> Unit = {}) {
    Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp).fillMaxWidth().height(52.dp).testTag("mode_tabs"),
        verticalAlignment = Alignment.CenterVertically) {
        XingcaiLibraryModeSwitch(mode, onMode, Modifier.weight(1f))
        // 顶栏三枚图标按设计稿用 Canvas 绘制：月牙+星 / 实心书签 / 浅蓝底加号
        IconButton(onClick = onToggleNight, modifier = Modifier.size(40.dp).testTag("top_night_mode")) {
            XingcaiNightModeGlyph(nightMode, modifier = Modifier.semantics {
                contentDescription = if (nightMode) "切换到日间模式" else "切换到夜间模式" })
        }
        IconButton(onClick = onNotes, modifier = Modifier.size(40.dp).testTag("notebook_entry")) {
            XingcaiNotesGlyph(modifier = Modifier.semantics { contentDescription = "书签与笔记" })
        }
        IconButton(onClick = onImport, enabled = importEnabled, modifier = Modifier.size(40.dp).testTag("top_import")) {
            XingcaiImportGlyph(enabled = importEnabled, modifier = Modifier.semantics { contentDescription = "导入书籍" })
        }
    }
}
