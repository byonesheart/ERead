package com.eyue.reader.data

import android.app.Notification
import androidx.core.app.NotificationCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ShelfOrganizationRobolectricTest {
    @Test fun restoresSecondarySelectionsPerModeAndHiddenTagsPerScope() {
        val context = RuntimeEnvironment.getApplication()
        val novel = ShelfBrowsePreferences(context, LibraryMode.NOVEL)
        val comic = ShelfBrowsePreferences(context, LibraryMode.COMIC)
        val parent = ShelfGroup("收藏", LibraryMode.NOVEL, "parent")
        val a = ShelfGroup("甲", parent.mode, "a", parent.id)
        val b = ShelfGroup("乙", parent.mode, "b", parent.id)
        val saved = ShelfBrowseFilter(parent.id, setOf(a.id, b.id), "科幻")
        novel.save(saved)
        comic.save(ShelfBrowseFilter(tag = "短篇", tagMode = true))
        novel.saveHidden(null, setOf("隐藏"))
        novel.saveHidden(a.id, setOf("科幻"))
        assertEquals(saved, ShelfBrowsePreferences(context, LibraryMode.NOVEL).read())
        assertEquals("短篇", comic.read().tag)
        assertEquals(setOf("隐藏"), comic.hidden(null))
        assertEquals(setOf("科幻"), novel.hidden(a.id))
        assertTrue(novel.hidden(b.id).isEmpty())
        val book = Book(title = "小说", format = BookFormat.TXT, mode = parent.mode, groupId = b.id, chapters = emptyList())
        assertTrue(saved.includesGroup(book, setOf(a.id, b.id)))
        assertFalse(saved.includesGroup(book.copy(groupId = parent.id), setOf(a.id, b.id)))
        assertTrue(saved.copy(subgroupIds = emptySet()).includesGroup(book.copy(groupId = parent.id), setOf(a.id, b.id)))
        val filtered = saved.available(listOf(parent, a), setOf("科幻"))
        assertEquals(setOf(a.id), filtered.subgroupIds)
        assertEquals("科幻", filtered.tag)
        assertNull(saved.available(listOf(parent, a), emptySet()).tag)
        val hidden = hiddenShelfTags(listOf(parent, a, b), novel::hidden)
        assertEquals(setOf("隐藏", "科幻"), hidden[a.id])
        assertEquals(setOf("隐藏"), hidden[b.id])
        novel.saveHidden(parent.id, setOf("收藏限定"))
        val inherited = hiddenShelfTags(listOf(parent, a, b), novel::hidden)
        assertEquals(setOf("隐藏", "科幻", "收藏限定"), inherited[a.id])
        assertEquals(setOf("隐藏", "收藏限定"), inherited[b.id])
        assertEquals(setOf("隐藏"), inherited[null])
    }

    @Test fun groupOrderMovesOnlySiblingsOfTheSameLibraryMode() {
        val mode = LibraryMode.NOVEL
        val a = ShelfGroup("甲", mode, "a")
        val b = ShelfGroup("乙", mode, "b")
        val c = ShelfGroup("丙", mode, "c")
        val a1 = ShelfGroup("甲一", mode, "a1", a.id)
        val a2 = ShelfGroup("甲二", mode, "a2", a.id)
        val b1 = ShelfGroup("乙一", mode, "b1", b.id)
        val comic = ShelfGroup("漫画", LibraryMode.COMIC, "comic")
        val groups = listOf(a, comic, a1, b, b1, c, a2)
        assertEquals(listOf(b, comic, a1, c, b1, a, a2), groups.reorderShelfGroup(mode, a.id, c.id))
        assertEquals(listOf(a, comic, a2, b, b1, c, a1), groups.reorderShelfGroup(mode, a2.id, a1.id))
        assertSame(groups, groups.reorderShelfGroup(mode, a1.id, b1.id))
        assertSame(groups, groups.reorderShelfGroup(mode, a.id, comic.id))
    }

    @Test fun transferNotificationsDisplayTheSamePercentageAsTheProgressBar() {
        val context = RuntimeEnvironment.getApplication()
        val active = NotificationCompat.Builder(context, "test").bookTransferProgress("下载图书", .42f).build()
        assertEquals("下载图书 · 42%", active.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(42, active.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(active.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        val unknown = NotificationCompat.Builder(context, "test").bookTransferProgress("准备上传", null).build()
        assertEquals("准备上传", unknown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(unknown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }
}
