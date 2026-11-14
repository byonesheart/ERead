package com.eyue.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.eyue.reader.ui.AppIcon as Icon
import com.eyue.reader.data.Book
import com.eyue.reader.data.ShelfGroup
import com.eyue.reader.data.UNGROUPED_FILTER
import com.eyue.reader.data.normalizeShelfTags
import kotlin.math.abs

internal fun shelfGroupRows(groups: List<ShelfGroup>): List<ShelfGroup> =
    groups.filter { it.parentId == null }.flatMap { parent -> listOf(parent) + groups.filter { it.parentId == parent.id } }

internal fun shelfGroupLabel(groups: List<ShelfGroup>, group: ShelfGroup): String =
    groups.find { it.id == group.parentId }?.let { "${it.name} / ${group.name}" } ?: group.name

@Composable
internal fun ShelfTagsDialog(book: Book, existingTags: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(book.id) { mutableStateOf(book.tags) }
    var name by remember(book.id) { mutableStateOf("") }
    val newTag = normalizeShelfTags(listOf(name)).singleOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑标签") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(60) }, modifier = Modifier.weight(1f).testTag("shelf_tag_name"),
                    singleLine = true, label = { Text("新标签") })
                TextButton(enabled = newTag != null && newTag !in selected && selected.size < 64, onClick = {
                    selected = normalizeShelfTags(selected + requireNotNull(newTag)); name = ""
                }, modifier = Modifier.testTag("shelf_add_tag")) { Text("添加") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items((selected + existingTags).distinct(), key = { it }) { tag ->
                    ShelfTagCheckbox(tag, tag in selected, tag in selected || selected.size < 64) {
                        selected = if (tag in selected) selected - tag else selected + tag
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        onSave(normalizeShelfTags(selected + listOfNotNull(newTag))); onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ShelfHiddenTagsDialog(label: String, tags: List<String>, hidden: Set<String>,
    onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(hidden) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(label) }, text = {
        Column {
            if (tags.isNotEmpty()) Text("分组浏览时隐藏带有选中标签的书籍。", color = Muted, fontSize = 13.sp)
            if (tags.isEmpty()) Text("暂无标签", color = Muted)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 290.dp)) {
                items(tags, key = { it }) { tag ->
                    ShelfTagCheckbox(tag, tag in selected) {
                        selected = if (tag in selected) selected - tag else selected + tag
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        onSave(selected); onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ShelfGroupActionsMenu(group: ShelfGroup?, expanded: Boolean, onDismiss: () -> Unit,
    onHideTags: () -> Unit, onAddChild: (() -> Unit)?, onRename: (() -> Unit)?, onDelete: (() -> Unit)?) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text("隐藏标签") }, onClick = { onDismiss(); onHideTags() },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.LabelOff, null, Modifier.size(19.dp)) })
        if (onAddChild != null) DropdownMenuItem(text = { Text("新建二级分组") },
            modifier = Modifier.testTag("shelf_group_add_subgroup_${group?.id}"), onClick = { onDismiss(); onAddChild() },
            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(19.dp)) })
        if (onRename != null) DropdownMenuItem(text = { Text("重命名") },
            modifier = Modifier.testTag("shelf_group_rename_${group?.id}"), onClick = { onDismiss(); onRename() },
            leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(19.dp)) })
        if (onDelete != null) DropdownMenuItem(text = { Text("删除分组", color = MaterialTheme.colorScheme.error) },
            modifier = Modifier.testTag("shelf_group_delete_${group?.id}"), onClick = { onDismiss(); onDelete() },
            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.error) })
    }
}

@Composable
internal fun ShelfGroupManager(groups: List<ShelfGroup>, counts: Map<String?, Int>, onOpen: (ShelfGroup?) -> Unit,
    onAdd: (String?) -> Unit, onAddChild: Boolean, onRename: (ShelfGroup) -> Unit, onDelete: (ShelfGroup) -> Unit,
    onHideTags: (String?) -> Unit, onReorder: (String, String) -> Unit, onDismiss: () -> Unit) {
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menu by remember { mutableStateOf<String?>(null) }
    val children = remember(groups) { groups.filter { it.parentId != null }.groupBy { it.parentId } }
    val rows = remember(groups, children, expanded) {
        groups.filter { it.parentId == null }.flatMap { listOf(it) + if (it.id in expanded) children[it.id].orEmpty() else emptyList() }
    }
    val listState = rememberLazyListState()
    val drag = rememberShelfGroupDrag(listState, groups, onReorder = onReorder, trailingButtons = true)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("shelf_groups_dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书架") }
                    Text("管理分组", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { onHideTags(null) }, modifier = Modifier.testTag("shelf_manage_hidden_tags")) {
                        Icon(Icons.AutoMirrored.Outlined.LabelOff, "全局隐藏标签")
                    }
                    IconButton(onClick = { onAdd(null) }, modifier = Modifier.testTag("shelf_create_group")) {
                        Icon(Icons.Outlined.CreateNewFolder, "新建分组")
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize().then(drag.gesture), state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    item(UNGROUPED_FILTER) {
                        ManagedShelfGroupRow(null, counts[null] ?: 0, false, null,
                            onOpen = { onOpen(null) }, onMenu = { menu = UNGROUPED_FILTER }) {
                            ShelfGroupActionsMenu(null, menu == UNGROUPED_FILTER, { menu = null },
                                { onHideTags(UNGROUPED_FILTER) }, null, null, null)
                        }
                    }
                    items(rows, key = { it.id }) { group ->
                        val open = group.id in expanded
                        ManagedShelfGroupRow(group, counts[group.id] ?: 0, open,
                            onExpand = if (children[group.id].isNullOrEmpty()) null else ({
                                expanded = if (open) expanded - group.id else expanded + group.id
                            }), onOpen = { if (drag.groupId == null) onOpen(group) }, onMenu = { menu = group.id },
                            modifier = drag.item(group.id)) {
                            ShelfGroupActionsMenu(group, menu == group.id, { menu = null }, { onHideTags(group.id) },
                                if (group.parentId == null && onAddChild) ({ onAdd(group.id) }) else null,
                                { onRename(group) }, { onDelete(group) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagedShelfGroupRow(group: ShelfGroup?, count: Int, expanded: Boolean, onExpand: (() -> Unit)?,
    onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier, menu: @Composable () -> Unit) {
    val id = group?.id ?: UNGROUPED_FILTER
    val name = group?.name ?: "未分组"
    val child = group?.parentId != null
    Row(modifier.fillMaxWidth().padding(start = if (child) 25.dp else 0.dp).heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (child) Spacer(Modifier.width(2.dp).height(32.dp).background(Line))
        Row(Modifier.weight(1f).testTag("shelf_manage_group_$id").clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (child) Icons.Outlined.SubdirectoryArrowRight else Icons.Outlined.Folder, null,
                Modifier.size(if (child) 17.dp else 20.dp), tint = Muted)
            Text(name, Modifier.weight(1f).padding(horizontal = 9.dp), fontSize = 14.sp,
                fontWeight = if (child) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count 本", color = Muted, fontSize = 11.sp)
        }
        if (onExpand != null) IconButton(onClick = onExpand, modifier = Modifier.size(40.dp).testTag("shelf_group_expand_$id")) {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "收起 $name" else "展开 $name", Modifier.size(20.dp))
        } else Spacer(Modifier.width(40.dp))
        Box {
            IconButton(onClick = onMenu, modifier = Modifier.size(40.dp).testTag("shelf_group_more_$id")) {
                Icon(Icons.Outlined.MoreVert, "$name 的分组操作", Modifier.size(20.dp))
            }
            menu()
        }
    }
}

@Composable
internal fun ShelfGroupChip(group: ShelfGroup?, label: String, selected: Boolean, edit: Boolean, listState: LazyListState,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit, onEdit: () -> Unit, onHideTags: () -> Unit, onAddChild: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val id = group?.id ?: UNGROUPED_FILTER
    val tag = when {
        group == null -> "shelf_group_ungrouped"
        group.parentId == null -> "shelf_group_$id"
        else -> "shelf_subgroup_$id"
    }
    var width by remember { mutableIntStateOf(0) }
    val chip = modifier.onSizeChanged { width = it.width }.testTag(tag)
        .semantics { onLongClick("分组操作") { onEdit(); true } }
    if (edit && width > 0) LaunchedEffect(listState, id, width) {
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == id } ?: return@LaunchedEffect
        val clipped = item.offset + width - layout.viewportEndOffset + layout.afterContentPadding
        if (clipped > 0) listState.scrollBy(clipped.toFloat())
    }
    FilterChip(selected, onSelect, {
        Text(label, modifier = Modifier.widthIn(max = if (edit) 112.dp else 162.dp),
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }, modifier = chip, trailingIcon = if (edit) {
        {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onHideTags, modifier = Modifier.size(30.dp).testTag("shelf_chip_hide_tags_$id")) {
                        Icon(Icons.AutoMirrored.Outlined.LabelOff, "隐藏标签", Modifier.size(17.dp))
                    }
                    if (onAddChild != null) IconButton(onClick = onAddChild, modifier = Modifier.size(30.dp)
                        .testTag("shelf_group_add_subgroup_$id")) {
                        Icon(Icons.Outlined.CreateNewFolder, "新建二级分组", Modifier.size(17.dp))
                    }
                    if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.size(30.dp).testTag("shelf_chip_delete_$id")) {
                        Icon(Icons.Outlined.Close, "删除分组 ${group?.name}", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    } else null)
}

@Stable
internal class ShelfGroupDrag(private val state: LazyListState, private val horizontal: Boolean, private val direction: Float) {
    var groupId by mutableStateOf<String?>(null)
    var targetId by mutableStateOf<String?>(null)
    var position by mutableFloatStateOf(0f)
    var touchOffset = 0f
    var movement = 0f
    var gesture: Modifier = Modifier

    fun item(id: String): Modifier = if (id == groupId) Modifier.zIndex(1f).graphicsLayer {
        val offset = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: return@graphicsLayer
        val translation = position - touchOffset - offset
        if (horizontal) translationX = translation * direction else translationY = translation
    } else Modifier
}

@Composable
internal fun rememberShelfGroupDrag(state: LazyListState, groups: List<ShelfGroup>, horizontal: Boolean = false,
    onReorder: (String, String) -> Unit, onLongPress: (String) -> Unit = {}, trailingButtons: Boolean = false): ShelfGroupDrag {
    val direction = if (horizontal && LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val drag = remember(state, horizontal, direction) { ShelfGroupDrag(state, horizontal, direction) }
    val currentGroups by rememberUpdatedState(groups.associateBy { it.id })
    val reorder by rememberUpdatedState(onReorder)
    val longPress by rememberUpdatedState(onLongPress)
    val edge = with(LocalDensity.current) { 30.dp.toPx() }

    fun updateTarget() {
        val source = drag.groupId?.let(currentGroups::get) ?: return
        drag.targetId = state.layoutInfo.visibleItemsInfo.filter { item ->
            (item.key as? String)?.let(currentGroups::get)?.let { it.parentId == source.parentId } == true
        }.minByOrNull { abs(it.offset + it.size / 2f - drag.position) }?.key as? String
    }

    drag.gesture = Modifier.pointerInput(drag, trailingButtons) {
        val trailingWidth = 80.dp.toPx()
        fun axis(offset: Offset) = if (!horizontal) offset.y else if (direction > 0) offset.x else size.width - offset.x
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (trailingButtons && down.position.x >= size.width - trailingWidth) return@awaitEachGesture
            val position = axis(down.position) - state.layoutInfo.beforeContentPadding
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { position >= it.offset && position < it.offset + it.size }
                ?: return@awaitEachGesture
            val id = item.key as? String ?: return@awaitEachGesture
            if (id !in currentGroups && !(horizontal && id == UNGROUPED_FILTER)) return@awaitEachGesture
            val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            drag.groupId = id
            drag.targetId = id
            drag.position = position
            drag.touchOffset = position - item.offset
            drag.movement = 0f
            try {
                while (true) {
                    // Claim drag and release before the nested chip or list handles the same event.
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull { it.id == pressed.id } ?: break
                    val offset = change.positionChange()
                    change.consume()
                    if (!change.pressed) {
                        val target = drag.targetId
                        if (drag.movement < viewConfiguration.touchSlop) longPress(id)
                        else if (target != null && target != id) reorder(id, target)
                        break
                    }
                    val delta = if (horizontal) offset.x * direction else offset.y
                    drag.position += delta
                    drag.movement += abs(delta)
                    updateTarget()
                }
            } finally {
                drag.groupId = null
            }
        }
    }
    LaunchedEffect(drag.groupId) {
        while (drag.groupId != null) {
            withFrameNanos { }
            val layout = state.layoutInfo
            val start = layout.viewportStartOffset + edge
            val end = layout.viewportEndOffset - edge
            val speed = when {
                drag.movement == 0f -> 0f
                drag.position < start -> (drag.position - start).coerceAtLeast(-edge)
                drag.position > end -> (drag.position - end).coerceAtMost(edge)
                else -> 0f
            }
            if (speed != 0f && state.scrollBy(speed / 3f) != 0f) updateTarget()
        }
    }
    return drag
}

@Composable
private fun ShelfTagCheckbox(tag: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(8.dp))
        Text(tag, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
