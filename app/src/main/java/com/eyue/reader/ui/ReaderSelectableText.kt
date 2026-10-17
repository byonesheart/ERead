package com.eyue.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.ChineseTextConverter

internal data class ReaderSelectedPassage(
    val chapter: Int,
    val page: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val raster: Boolean = false,
    val imagePath: String? = null,
    val anchor: Rect? = null
)

/** Read-only text retains Compose's real selection handles and shares the pagination text engine. */
@Composable
internal fun ReaderSelectableText(
    text: AnnotatedString,
    style: TextStyle,
    chapter: Int,
    page: Int,
    pageStart: Int,
    selectionReset: Int,
    onAction: (ReaderSelectedPassage, AnnotationKind) -> Unit,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onTextTap: (Int, Rect) -> Unit = { _, _ -> },
    onEdit: ((ReaderSelectedPassage) -> Unit)? = null,
    selectionMenuEnabled: Boolean = true,
    paragraphSpacing: Float = ReaderParagraphGapEm,
    onRole: ((ReaderSelectedPassage) -> Unit)? = null,
    chineseConverter: ChineseTextConverter? = null,
    sourceContext: String? = null
) {
    var value by remember(chapter, pageStart, text.text) { mutableStateOf(TextFieldValue(text)) }
    var toolbarVisible by remember(chapter, pageStart) { mutableStateOf(false) }
    var toolbarHandedOff by remember(chapter, pageStart, text.text) { mutableStateOf(false) }
    var toolbarRect by remember { mutableStateOf(Rect.Zero) }
    var copyAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestValue by rememberUpdatedState(value)
    val latestAction by rememberUpdatedState(onAction)
    val latestTap by rememberUpdatedState(onTextTap)
    val latestEdit by rememberUpdatedState(onEdit)
    val latestRole by rememberUpdatedState(onRole)
    val latestMenuEnabled by rememberUpdatedState(selectionMenuEnabled)
    var textLayout by remember(chapter, pageStart, text.text) { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pointerConfiguration = LocalViewConfiguration.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val paragraphTransformation = remember(style.fontSize, paragraphSpacing, chineseConverter, sourceContext, pageStart) {
        var previous: Pair<AnnotatedString, androidx.compose.ui.text.input.TransformedText>? = null
        VisualTransformation { input ->
            previous?.takeIf { it.first == input }?.second ?: readerParagraphText(input, style, paragraphSpacing, chineseConverter, sourceContext, pageStart)
                .also { previous = input to it }
        }
    }
    LaunchedEffect(selectionReset) {
        toolbarVisible = false
        if (!value.selection.collapsed) {
            value = value.copy(selection = TextRange(value.selection.min))
            focus.clearFocus()
        }
        toolbarHandedOff = false
    }
    val toolbar = remember(chapter, pageStart) {
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = if (toolbarVisible && !toolbarHandedOff && latestMenuEnabled) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                // The native selection manager may request its toolbar again after losing
                // focus. An annotation/editor owns this selection until its parent resets it.
                if (toolbarHandedOff || !latestMenuEnabled) return
                toolbarRect = rect
                copyAction = onCopyRequested
                toolbarVisible = true
            }

            override fun hide() { toolbarVisible = false }
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        BasicTextField(
            value = value.copy(annotatedString = text),
            onValueChange = { newValue -> value = newValue.copy(annotatedString = text) },
            readOnly = true,
            textStyle = style,
            visualTransformation = paragraphTransformation,
            onTextLayout = { textLayout = it; onTextLayout(it) },
            modifier = modifier.onGloballyPositioned { coordinates = it }.readerAnnotationUnderlines(style.color) { textLayout }
                .semantics { contentDescription = "阅读正文" }.pointerInput(chapter, pageStart) {
                // Observe a short tap before the parent's Final pass; selection and scrolling
                // still belong to BasicTextField and the surrounding reader.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    var released = false
                    var upTime = down.uptimeMillis
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > pointerConfiguration.touchSlop) {
                            moved = true; toolbarVisible = false
                        }
                        upTime = change.uptimeMillis
                        released = !change.pressed
                    } while (!released)
                    if (released && !moved && upTime - down.uptimeMillis < pointerConfiguration.longPressTimeoutMillis) {
                        val layout = textLayout
                        val place = coordinates
                        if (layout != null && place?.isAttached == true) readerTextTapOffset(layout, down.position)?.let { offset ->
                            val glyph = layout.getBoundingBox(offset)
                            val originalOffset = paragraphTransformation.filter(text).offsetMapping.transformedToOriginal(offset)
                            latestTap(pageStart + originalOffset, Rect(place.localToWindow(glyph.topLeft), place.localToWindow(glyph.bottomRight)))
                        }
                    }
                }
            }
        )
    }
    if (toolbarVisible && !toolbarHandedOff && selectionMenuEnabled && !value.selection.collapsed) {
        val location = IntArray(2).also(view::getLocationInWindow)
        val anchor = toolbarRect.translate(location[0].toFloat(), location[1].toFloat())
        ReaderAnnotationPopup(anchor, onDismiss = { toolbarVisible = false }) {
                fun selectedPassage(): ReaderSelectedPassage? {
                    val selected = latestValue.selection
                    val start = selected.min.coerceIn(0, text.length)
                    val end = selected.max.coerceIn(start, text.length)
                    return if (end > start) ReaderSelectedPassage(chapter, page, pageStart + start, pageStart + end,
                        text.text.substring(start, end), anchor = anchor) else null
                }
                fun action(kind: AnnotationKind) {
                    val passage = selectedPassage() ?: return
                    toolbarHandedOff = true
                    toolbarVisible = false
                    latestAction(passage, kind)
                }
                val actions = listOf(
                    ReaderCompactAction("note", "笔记") { action(AnnotationKind.NOTE) },
                    ReaderCompactAction("highlight", "标记") { action(AnnotationKind.HIGHLIGHT) },
                    ReaderCompactAction("underline", "划线") { action(AnnotationKind.UNDERLINE) }
                ) + listOfNotNull(if (onRole != null) ReaderCompactAction("role", "角色") {
                    selectedPassage()?.let { passage ->
                        toolbarHandedOff = true; toolbarVisible = false
                        latestRole?.invoke(passage)
                    }
                } else null) + listOfNotNull(if (onEdit != null) ReaderCompactAction("edit", "编辑") {
                    val passage = selectedPassage()
                    if (passage != null) {
                        toolbarHandedOff = true; toolbarVisible = false
                        latestEdit?.invoke(passage)
                    }
                } else null) + ReaderCompactAction("copy", "复制") {
                    copyAction?.invoke() ?: clipboard.setText(AnnotatedString(value.annotatedString.text.substring(value.selection.min, value.selection.max)))
                    toolbarVisible = false
                    value = value.copy(selection = TextRange(value.selection.max))
                    focus.clearFocus()
                }
                ReaderCompactActionMenu(actions, Modifier.testTag("reader_selection_menu"), moreTag = "reader_selection_more")
        }
    }
}
