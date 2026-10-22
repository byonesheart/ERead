package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eyue.reader.data.*
import com.eyue.reader.data.Annotation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun NotebookDialog(state: LibraryState, onDismiss: () -> Unit, onDelete: (String) -> Unit, onOpen: (Annotation) -> Unit) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<AnnotationKind?>(null) }
    val visible = state.annotations.filter { annotation ->
        val title = state.books.find { it.id == annotation.bookId }?.title.orEmpty()
        (kind == null || kind == annotation.kind) &&
            (query.isBlank() || annotation.quote.contains(query, true) || annotation.note.contains(query, true) || title.contains(query, true))
    }.sortedByDescending { it.createdAt }
    val configuration = LocalConfiguration.current
    val dialogWidth = (configuration.screenWidthDp.dp - 32.dp).coerceIn(1.dp, 560.dp)
    val dialogHeight = (configuration.screenHeightDp.dp - 48.dp).coerceIn(1.dp, 620.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(dialogWidth).height(dialogHeight).testTag("notebook_dialog"),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("书签与笔记", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(16.dp))
            Text("共 ${state.annotations.size} 条标注", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("查找书名、摘录或笔记", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) }, modifier = Modifier.fillMaxWidth())
            AnnotationFilterRow(kind, { kind = it }, Modifier.padding(vertical = 6.dp), tagPrefix = "notebook_annotation_filter")
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("notebook_annotation_list"),
                verticalArrangement = Arrangement.spacedBy(13.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (visible.isEmpty()) item {
                    Text(if (state.annotations.isEmpty()) "把喜欢的句子留下。\n阅读时可以添加书签、笔记或标记。" else "没有匹配的标注",
                        color = Muted, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 24.dp))
                }
                items(visible, key = { it.id }) { annotation ->
                    val book = state.books.find { it.id == annotation.bookId }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Paper).clickable { onOpen(annotation) }.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (annotation.kind) {
                                AnnotationKind.BOOKMARK -> Icons.Outlined.BookmarkBorder
                                AnnotationKind.NOTE -> Icons.Outlined.EditNote
                                AnnotationKind.HIGHLIGHT -> Icons.Outlined.BorderColor
                                AnnotationKind.UNDERLINE -> Icons.Outlined.FormatUnderlined
                            }, null, tint = Olive, modifier = Modifier.size(17.dp))
                            Text(book?.title.orEmpty(), fontSize = 12.sp, color = Olive, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (annotation.kind == AnnotationKind.HIGHLIGHT) {
                                Box(Modifier.padding(horizontal = 6.dp).size(12.dp)
                                    .background(annotation.color?.let { Color(it.toInt()) } ?: Olive, CircleShape)
                                    .border(1.dp, Line, CircleShape))
                            }
                            IconButton(onClick = { onDelete(annotation.id) }, modifier = Modifier.size(27.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除标注", tint = Muted, modifier = Modifier.size(17.dp)) }
                        }
                        Text(annotation.quote, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        if (annotation.note.isNotBlank()) Text(annotation.note, fontSize = 12.sp, color = Ink, modifier = Modifier.padding(top = 7.dp))
                        val location = if (annotation.imagePath != null) " · 正文插图" else
                            if (book?.format in setOf(BookFormat.PDF, BookFormat.IMAGES)) " · 第 ${annotation.pageIndex + 1} 页" else ""
                        Text("${book?.chapters?.getOrNull(annotation.chapterIndex)?.title.orEmpty()}$location · ${SimpleDateFormat("MM.dd", Locale.CHINA).format(Date(annotation.createdAt))}",
                            color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 9.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("notebook_close")) { Text("关闭") }
            }
        }
        }
    }
}
