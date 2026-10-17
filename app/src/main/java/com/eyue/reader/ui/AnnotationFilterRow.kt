package com.eyue.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.AnnotationKind

/** Keep all labels horizontal; small windows and large system fonts scroll instead of squeezing a label. */
@Composable
internal fun AnnotationFilterRow(kind: AnnotationKind?, onKind: (AnnotationKind?) -> Unit,
    modifier: Modifier = Modifier, tagPrefix: String = "annotation_filter") {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("${tagPrefix}_row"),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(null to "全部", AnnotationKind.BOOKMARK to "书签", AnnotationKind.NOTE to "笔记",
                AnnotationKind.HIGHLIGHT to "标记", AnnotationKind.UNDERLINE to "划线").forEach { (value, label) ->
                val active = kind == value
                Surface(onClick = { onKind(value) }, modifier = Modifier.heightIn(min = 32.dp).widthIn(min = 40.dp)
                    .semantics { selected = active }.testTag("${tagPrefix}_${value?.name ?: "ALL"}"),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, lineHeight = 14.sp,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
