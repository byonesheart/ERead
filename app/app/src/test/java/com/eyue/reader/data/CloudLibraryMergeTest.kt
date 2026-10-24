package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudRestoredBook
import org.junit.Assert.*
import org.junit.Test

class CloudLibraryMergeTest {
    private fun book(id: String, mode: LibraryMode = LibraryMode.NOVEL, only: Boolean = false) =
        Book(id = id, title = id, format = if (mode == LibraryMode.COMIC) BookFormat.IMAGES else BookFormat.MOBI,
            mode = mode, chapters = listOf(Chapter(id = "chapter-$id", title = "第一章", textPath = "/old/body.txt",
                pages = listOf(PageSource("/old/page.pdf", 2)), inlineImages = listOf(InlineImage(3, "/old/image.png")))),
            sourcePath = "/old/source.mobi", cloudOnly = only, characterOffset = 12, progress = .3f)

    @Test fun automaticBackupRequiresGlobalOptInAndASelectedDownloadedBook() {
        val selected = book("selected").copy(cloudEnabled = true)
        val comic = book("comic", LibraryMode.COMIC).copy(cloudEnabled = true)
        val cloudOnly = book("cloud-only", only = true).copy(cloudEnabled = true)
        val state = LibraryState(books = listOf(selected, book("unselected"), comic, cloudOnly))
        assertTrue(cloudBackupBooks(state).isEmpty())
        assertEquals(listOf(selected, comic), cloudBackupBooks(state.copy(cloudAutoBackup = true)))
    }

    @Test fun informationRestorePreservesDownloadedLocalBooksAndTheirNotes() {
        val local = book("local").copy(characterOffset = 99, progress = .7f)
        val note = Annotation(bookId = local.id, chapterIndex = 0, pageIndex = 0, note = "本地新笔记")
        val state = LibraryState(books = listOf(local), annotations = listOf(note))
        val restored = mergeCloudBookInformation(state, listOf(book("local"), book("remote")),
            listOf(note.copy(note = "云端旧笔记")), emptyList(), 20)
        assertEquals(local, restored.books.first())
        assertEquals(note, restored.annotations.single())
        assertTrue(restored.books.last().cloudOnly)
    }

    @Test fun informationRestoreClearsForeignFilePathsAndRetainsPositionsAndPageCounts() {
        val remote = book("remote").copy(sourceUri = "content://foreign/book", sourceLinked = true)
        val restored = mergeCloudBookInformation(LibraryState(), listOf(remote), emptyList(), emptyList(), 0).books.single()
        assertTrue(restored.cloudOnly && restored.cloudEnabled)
        assertFalse(restored.sourceLinked)
        assertNull(restored.sourcePath)
        assertNull(restored.sourceUri)
        assertNull(restored.chapters.single().textPath)
        assertEquals("", restored.chapters.single().pages.single().path)
        assertTrue(restored.chapters.single().inlineImages.isEmpty())
        assertEquals(remote.characterOffset, restored.characterOffset)
        assertEquals(remote.progress, restored.progress)
    }

    @Test fun repeatedInformationRestoreUpdatesPlaceholdersWithoutDuplicatingNotes() {
        val remote = book("remote")
        val note = Annotation(bookId = remote.id, chapterIndex = 0, pageIndex = 0, note = "第一次")
        val first = mergeCloudBookInformation(LibraryState(), listOf(remote), listOf(note), emptyList(), 50)
        val second = mergeCloudBookInformation(first, listOf(remote.copy(progress = .5f)), listOf(note.copy(note = "第二次")), emptyList(), 30)
        assertEquals(1, second.books.size)
        assertEquals(.5f, second.books.single().progress, 0f)
        assertEquals("第二次", second.annotations.single().note)
        assertEquals(50L, second.readingSeconds)
    }

    @Test fun informationRefreshReceivesRemoteNameChangesForUneditedPlaceholders() {
        val remote = book("remote").copy(title = "原书名", sourceFileName = "原文件.mobi", sourceFileBytes = 1234)
        val first = mergeCloudBookInformation(LibraryState(), listOf(remote), emptyList(), emptyList(), 0)
        val next = mergeCloudBookInformation(first, listOf(remote.copy(title = "云端新书名", progress = .8f)), emptyList(), emptyList(), 0)
        assertEquals("云端新书名", next.books.single().title)
        assertEquals(.8f, next.books.single().progress, 0f)
        assertEquals("原文件.mobi", next.books.single().sourceFileName)
        assertEquals(1234L, next.books.single().sourceFileBytes)
        assertTrue(next.books.single().cloudOnly)
    }

    @Test fun groupsWithTheSameNameStaySeparateBetweenNovelAndComic() {
        val novel = ShelfGroup("收藏", LibraryMode.NOVEL, "novel")
        val comic = ShelfGroup("收藏", LibraryMode.COMIC, "comic")
        val remote = book("comic-book", LibraryMode.COMIC).copy(groupId = comic.id)
        val restored = mergeCloudBookInformation(LibraryState(groups = listOf(novel)), listOf(remote), emptyList(), listOf(comic), 0)
        assertEquals(listOf(novel, comic), restored.groups)
        assertEquals(comic.id, restored.books.single().groupId)
    }

    @Test fun existingSameNameGroupIsReusedForTheSameMode() {
        val local = ShelfGroup("收藏", LibraryMode.NOVEL, "local-group")
        val remote = ShelfGroup("收藏", LibraryMode.NOVEL, "remote-group")
        val restored = mergeCloudBookInformation(LibraryState(groups = listOf(local)),
            listOf(book("book").copy(groupId = remote.id)), emptyList(), listOf(remote), 0)
        assertEquals(listOf(local), restored.groups)
        assertEquals(local.id, restored.books.single().groupId)
    }

    @Test fun manualDownloadRestoresOneBookAndItsProfileWhilePreservingGlobalNight() {
        val novel = ReaderSettings(fontSize = 22f, nightMode = true)
        val comic = ReaderSettings(fontSize = 30f, nightMode = true)
        val placeholder = book("remote", only = true)
        val unrelated = book("local")
        val oldNote = Annotation(bookId = placeholder.id, chapterIndex = 0, pageIndex = 0, note = "旧信息")
        val localNote = Annotation(bookId = unrelated.id, chapterIndex = 0, pageIndex = 0, note = "本地")
        val incoming = oldNote.copy(note = "完整备份", characterOffset = 6, endOffset = 8)
        val restored = mergeCloudDownloadedBook(LibraryState(books = listOf(placeholder, unrelated),
            annotations = listOf(oldNote, localNote), settings = novel, comicSettings = comic),
            CloudRestoredBook(book("remote"), listOf(incoming), ReaderSettings(fontSize = 18f), null, 1234, 60))
        assertFalse(restored.books.first().cloudOnly)
        assertEquals(1234L, restored.books.first().cloudBackupAt)
        assertEquals(unrelated, restored.books.last())
        assertEquals(listOf(localNote, incoming), restored.annotations)
        assertEquals(18f, restored.settings.fontSize, 0f)
        assertTrue(restored.settings.nightMode)
        assertEquals(comic, restored.comicSettings)
        assertEquals(60L, restored.readingSeconds)
    }

    @Test fun restoringSeveralBooksUsesTotalReadingTimeWithoutCountingItTwice() {
        val state = mergeCloudBookInformation(LibraryState(readingSeconds = 100), listOf(book("one"), book("two")), emptyList(), emptyList(), 120)
        val next = mergeCloudDownloadedBook(state, CloudRestoredBook(book("one"), emptyList(), ReaderSettings(), null, 0, 110))
        assertEquals(120L, next.readingSeconds)
    }
}
