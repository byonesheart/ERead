package com.eyue.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.SourceFootnote
import com.eyue.reader.data.UnderlineStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Older notes saved a quote without an end offset; only restore its verified source range. */
internal fun readerAnnotationRange(text: String, item: Annotation): IntRange? {
    val start = item.characterOffset
    if (start !in text.indices) return null
    val end = if (item.endOffset > start) item.endOffset.coerceAtMost(text.length) else {
        val quote = item.quote
        if (quote.isEmpty() || quote.length > text.length - start || !text.regionMatches(start, quote, 0, quote.length)) return null
        start + quote.length
    }
    return start until end
}

internal fun readerTextNotes(text: String, annotations: List<Annotation>, bookId: String, chapter: Int, offset: Int): List<Annotation> =
    annotations.filter { item -> item.bookId == bookId && item.chapterIndex == chapter && item.imagePath == null &&
        item.kind == AnnotationKind.NOTE && item.note.isNotBlank() && readerAnnotationRange(text, item)?.contains(offset) == true }
        .sortedByDescending { it.createdAt }

internal fun readerTextAnnotations(text: String, annotations: List<Annotation>, bookId: String, chapter: Int, offset: Int): List<Annotation> =
    annotations.filter { item -> item.bookId == bookId && item.chapterIndex == chapter && item.imagePath == null &&
        item.kind != AnnotationKind.BOOKMARK && (item.kind != AnnotationKind.NOTE || item.note.isNotBlank()) &&
        readerAnnotationRange(text, item)?.contains(offset) == true }.sortedByDescending { it.createdAt }

internal fun readerSourceFootnoteAt(footnotes: List<SourceFootnote>, offset: Int, imagePath: String? = null): SourceFootnote? =
    footnotes.firstOrNull { item -> item.imagePath == imagePath &&
        (offset >= item.start && offset < item.end || imagePath != null && offset == item.start) }

@Composable
internal fun ReaderSourceFootnotePreview(footnote: SourceFootnote, anchor: Rect, onDismiss: () -> Unit) {
    ReaderAnnotationPopup(anchor, onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("reader_source_footnote_popup"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原文注释", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("reader_source_footnote_title"))
            Text(footnote.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reader_source_footnote_content"))
        }
    }
}

internal fun readerAnnotationNeedsDialog(items: List<Annotation>): Boolean =
    items.any { it.kind == AnnotationKind.NOTE && it.note.codePointCount(0, it.note.length) > 20 }

private fun readerAnnotationLabel(item: Annotation) = when (item.kind) {
    AnnotationKind.NOTE -> "笔记"
    AnnotationKind.HIGHLIGHT -> "标记"
    AnnotationKind.UNDERLINE -> if (item.underlineStyle == UnderlineStyle.WAVY) "波浪线" else "划线"
    AnnotationKind.BOOKMARK -> "书签"
}

@Composable
private fun ReaderAnnotationDetails(annotations: List<Annotation>, onDelete: (String) -> Unit) {
    annotations.forEachIndexed { index, item ->
        if (index > 0) HorizontalDivider()
        if (item.quote.isNotBlank()) Text(item.quote, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(item.note.ifBlank { readerAnnotationLabel(item) })
        Text(remember(item.createdAt) { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt)) },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("reader_annotation_date_${item.id}"))
        TextButton(onClick = { onDelete(item.id) }, modifier = Modifier.testTag("reader_annotation_delete_${item.id}")) {
            Text("删除${readerAnnotationLabel(item)}")
        }
    }
}

@Composable
internal fun ReaderAnnotationPreview(settings: ReaderSettings, annotations: List<Annotation>, anchor: Rect,
    onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    if (readerAnnotationNeedsDialog(annotations)) ReaderNoteDialog(settings, annotations, onDelete, onDismiss)
    else ReaderAnnotationPopup(anchor, onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("reader_note_popup"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderAnnotationDetails(annotations, onDelete)
        }
    }
}

/** Ignore indentation, paragraph spacers and the empty area after a short line. */
internal fun readerTextTapOffset(layout: TextLayoutResult, point: Offset): Int? {
    if (point.x < 0f || point.y < 0f || point.x >= layout.size.width || point.y >= layout.size.height) return null
    val nearest = layout.getOffsetForPosition(point)
    return sequenceOf(nearest, nearest - 1).firstOrNull { offset ->
        offset in layout.layoutInput.text.text.indices && layout.layoutInput.text[offset] !in "\n\r\u200B" &&
            layout.getBoundingBox(offset).contains(point)
    }
}

@Composable
internal fun ReaderNoteDialog(settings: ReaderSettings, notes: List<Annotation>, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("笔记") },
        text = {
            ReaderDialogAppearance(settings)
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                .testTag("reader_note_popup"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReaderAnnotationDetails(notes, onDelete)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun ReaderTxtEditor(settings: ReaderSettings, selection: ReaderSelectedPassage,
    onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(selection) { mutableStateOf(selection.quote) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑正文") },
        text = {
            ReaderDialogAppearance(settings)
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 400.dp)
                .testTag("reader_txt_editor"), label = { Text("替换内容") }, minLines = 6,
                supportingText = { Text("修改先在页面预览，点击阅读页右上角“保存”后才写入文件") })
        }, confirmButton = { TextButton(enabled = draft != selection.quote, onClick = { onSave(draft) }) { Text("应用修改") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
