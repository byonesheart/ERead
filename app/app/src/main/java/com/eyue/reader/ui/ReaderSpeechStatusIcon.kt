package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderSpeechStatusIcon(complete: Boolean, description: String? = null, modifier: Modifier = Modifier) {
    val tint = if (complete) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Box(modifier.size(24.dp).semantics(mergeDescendants = true) {
        description?.let { contentDescription = it }
    }, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Headphones, null, Modifier.fillMaxSize(), tint = tint)
        if (complete) Icon(Icons.Outlined.Check, null, Modifier.size(10.dp).offset(y = 3.dp)
            .testTag("reader_speech_complete_mark"), tint = tint)
    }
}
