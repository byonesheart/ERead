package com.eyue.reader.ui

// Card layout adapted from the user-provided ebook 2.0.5 UI, using ERead's existing callbacks and covers.
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.Book
import com.eyue.reader.data.displayAuthor

@Composable internal fun XingcaiFeaturedBook(book: Book, resume: Boolean, onOpen: () -> Unit, onCloud: (() -> Unit)?,
    cloudBusy: Boolean, cloudInProgress: Boolean, cloudTaskLabel: String, cloudFraction: Float?,
    onCancelCloud: (() -> Unit)?, cloudCanCancel: Boolean, cloudDirty: Boolean) {
    Row(Modifier.fillMaxWidth().clip(appShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer)
        .clickable(onClick = onOpen).padding(16.dp).testTag("xingcai_featured_book")) {
        BookCover(book, Modifier.width(72.dp).height(108.dp),
            onCloud, cloudBusy, cloudInProgress, cloudTaskLabel, cloudFraction, onCancelCloud, cloudCanCancel, cloudDirty)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(if (resume) "上次读到这里" else "从这里开始",
                fontSize = 12.sp, color = Muted, maxLines = 1)
            Text(book.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Text(book.displayAuthor, color = Muted, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                XingcaiProgressTrack(book.progress, Modifier.weight(1f))
                Text("${(book.progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 12.sp, color = Olive,
                    modifier = Modifier.padding(start = 8.dp))
            }
            Text(if (resume) book.chapters.getOrNull(book.chapterIndex)?.title ?: "尚未开始" else "${book.format.name} · ${book.chapters.size} 章",
                fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            Button(onClick = { if (book.cloudOnly) onCloud?.invoke() else onOpen() },
                enabled = !book.cloudOnly || onCloud != null && !cloudBusy,
                modifier = Modifier.padding(top = 10.dp).height(38.dp).widthIn(min = 100.dp),
                shape = appShape(13.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                Text(if (book.cloudOnly) "下载书籍" else if (resume) "继续阅读" else "开始阅读", fontSize = 14.sp)
            }
        }
    }
}

@Composable internal fun XingcaiProgressTrack(progress: Float, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val accent = Olive
    val line = Line
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier.height(14.dp)) {
        val track = 6.dp.toPx()
        val top = size.height - track
        val corner = CornerRadius(track / 2)
        drawRoundRect(line, Offset(0f, top), Size(size.width, track), corner)
        if (p > 0f && size.width > 0f) {
            val filled = size.width * p
            drawRoundRect(accent, Offset(0f, top), Size(filled, track), corner)
            val radius = minOf(6.dp.toPx(), size.width / 2)
            val center = Offset(filled.coerceIn(radius, size.width - radius), top + track / 2)
            drawCircle(surface, radius, center)
            drawCircle(accent, radius, center, style = Stroke(2.dp.toPx()))
        }
    }
}
