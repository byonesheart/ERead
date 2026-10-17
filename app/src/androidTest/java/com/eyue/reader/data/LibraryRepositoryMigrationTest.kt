package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryMigrationTest {
    private lateinit var directory: File
    private lateinit var repository: LibraryRepository

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "repository-test-${UUID.randomUUID()}").apply { mkdirs() }
        repository = LibraryRepository(object : ContextWrapper(application) {
            override fun getFilesDir(): File = directory
        })
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun readingTimeChangesBackupFingerprintButBackupTimestampDoesNot() {
        val book = Book(id = "fingerprint-book", title = "指纹", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(id = "fingerprint-chapter", title = "正文")), cloudEnabled = true)
        val state = LibraryState(books = listOf(book), readingSeconds = 100)
        val original = cloudBackupFingerprint(repository, state, book)
        assertTrue(original != cloudBackupFingerprint(repository, state.copy(readingSeconds = 160), book))
        assertEquals(original, cloudBackupFingerprint(repository, state, book.copy(cloudBackupAt = 9999)))
    }

    @Test fun cloudSelectionAndUndownloadedInformationRoundTripWithoutCredentials() {
        val book = Book(id = "cloud-book", title = "云端信息", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章")), cloudEnabled = true, cloudOnly = true, cloudBackupAt = 1234)
        val state = LibraryState(books = listOf(book), cloudAutoBackup = true)
        repository.save(state)
        assertEquals(state, repository.load())
        val json = JSONObject(File(directory, "library.json").readText())
        assertTrue(!json.has("password") && !json.has("cloudAccount"))
    }

    @Test fun oldDefaultRuleUpgradesWhileCustomComicAndNovelRulesRemainIndependent() {
        val custom = "^自定义[0-9]+$"
        repository.save(LibraryState(settings = ReaderSettings(chapterRule = LEGACY_DEFAULT_CHAPTER_RULE),
            comicSettings = ReaderSettings(chapterRule = custom)))
        assertEquals(DEFAULT_CHAPTER_RULE, repository.load().settings.chapterRule)
        assertEquals(custom, repository.load().comicSettings.chapterRule)
        repository.save(LibraryState(settings = ReaderSettings(chapterRule = custom)))
        assertEquals(custom, repository.load().settings.chapterRule)
    }

    @Test fun roundTripsHighlightsAndNewReaderSettingsWithoutLosingRangesOrColors() {
        val book = Book(id = "fixture-book", title = "MOBI fixture", format = BookFormat.MOBI,
            mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "Chapter one", inlineImages = listOf(InlineImage(12, "private.png")),
                sourceStyles = listOf(SourceTextStyle(0, 12, fontScale = 1.5f, bold = true, italic = false,
                    alignment = SourceTextAlignment.CENTER, firstLineIndentEm = 2f, restLineIndentEm = 1f, paragraph = true),
                    SourceTextStyle(12, 15, italic = true)))),
            positionImagePath = "private.png", contentVersion = 3, sourceUri = "content://fixture/book", sourceLinked = true)
        val annotation = Annotation(bookId = book.id, chapterIndex = 0, pageIndex = 2,
            characterOffset = 37, quote = "selected excerpt", kind = AnnotationKind.HIGHLIGHT,
            endOffset = 53, color = 0xffdfc981L, imagePath = "private.png")
        val settings = ReaderSettings(fontName = "跟随系统", nightMode = true, brightness = 0.37f, letterSpacing = 0.7f)
        val state = LibraryState(books = listOf(book), annotations = listOf(annotation), settings = settings, readingSeconds = 125)
        repository.save(state)
        assertEquals(state, repository.load())
        assertEquals(11, JSONObject(File(directory, "library.json").readText()).getInt("version"))
    }

    @Test fun infersLegacyAnnotationKindsAndMigratesDarkThemeToGlobalNightMode() {
        File(directory, "library.json").writeText("""{
            "version":1,
            "books":[{"id":"book","title":"旧书","format":"EPUB","mode":"NOVEL","chapters":[{"id":"chapter","title":"前言"}]}],
            "annotations":[
                {"id":"bookmark","bookId":"book","chapterIndex":0,"pageIndex":0,"quote":"text","note":""},
                {"id":"note","bookId":"book","chapterIndex":0,"pageIndex":0,"quote":"text","note":"My note"}
            ],
            "settings":{"background":"DARK"}
        }""")
        val state = repository.load()
        assertEquals(listOf(AnnotationKind.BOOKMARK, AnnotationKind.NOTE), state.annotations.map { it.kind })
        assertTrue(state.annotations.all { it.endOffset == 0 })
        assertNull(state.annotations[0].color)
        assertNull(state.annotations[0].imagePath)
        assertEquals(1, state.books.single().contentVersion)
        assertNull(state.books.single().positionImagePath)
        assertNull(state.books.single().sourceUri)
        assertTrue(!state.books.single().sourceLinked)
        assertNull(state.books.single().groupId)
        assertTrue(state.groups.isEmpty())
        assertTrue(state.books.single().chapters.single().inlineImages.isEmpty())
        assertTrue(state.books.single().chapters.single().sourceStyles.isEmpty())
        assertTrue(state.books.single().chapters.single().subsections.isEmpty())
        assertTrue(state.settings.nightMode)
        assertEquals(ReaderBackground.PAPER, state.settings.background)
        assertEquals(-1f, state.settings.brightness)
        assertEquals(0f, state.settings.letterSpacing)
        assertEquals("跟随系统", state.settings.fontName)
        assertEquals(state.settings, state.comicSettings)
    }

    @Test fun roundTripsNestedComicSectionsWithoutChangingChapterIdsPagesOrBookmarks() {
        val chapter = Chapter(id = "chapter-id", title = "第一话", pages = listOf(PageSource("1.png"), PageSource("part.pdf", 0),
            PageSource("part.pdf", 1), PageSource("4.png")), subsections = listOf(ComicSection("PDF 小节", 1, 3,
            listOf(ComicSection("嵌套小节", 2, 3))), ComicSection("图片小节", 3, 4)))
        val book = Book(id = "comic-id", title = "漫画", format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            chapters = listOf(chapter), chapterIndex = 0, pageIndex = 2, progress = .5f)
        val bookmark = Annotation(id = "bookmark-id", bookId = book.id, chapterIndex = 0, pageIndex = 2)
        val state = LibraryState(books = listOf(book), annotations = listOf(bookmark))
        repository.save(state)
        assertEquals(state, repository.load())
    }

    @Test fun novelAndComicProfilesRoundTripIndependentlyWhileSharingGlobalNight() {
        val novel = ReaderSettings(fontSize = 22f, lineHeight = 1.4f, fontName = "系统宋体", nightMode = true,
            background = ReaderBackground.PAPER, readingMode = ReadingMode.PAGED, brightness = .3f)
        val comic = ReaderSettings(fontSize = 30f, lineHeight = 2.2f, fontName = "系统黑体", nightMode = false,
            background = ReaderBackground.WHITE, readingMode = ReadingMode.SCROLL, brightness = .8f, volumePaging = true)
        val state = LibraryState(settings = novel, comicSettings = comic)
        repository.save(state)
        val restored = repository.load()
        assertEquals(novel, restored.settings)
        assertEquals(comic.copy(nightMode = true), restored.comicSettings)
        val json = JSONObject(File(directory, "library.json").readText())
        assertTrue(json.getJSONObject("comicSettings").getBoolean("nightMode"))
        val changed = restored.copy(settings = restored.settings.copy(fontSize = 25f, nightMode = false))
        repository.save(changed)
        assertEquals(25f, repository.load().settings.fontSize)
        assertEquals(comic.copy(nightMode = false), repository.load().comicSettings)
    }

    @Test fun legacySharedSettingsInitializeBothProfilesThenCanBeEditedSeparately() {
        File(directory, "library.json").writeText("""{"version":5,"settings":{"fontSize":23,"fontName":"系统宋体","lineHeight":1.4,"brightness":0.4,"readingMode":"PAGED","nightMode":true}}""")
        val restored = repository.load()
        assertEquals(restored.settings, restored.comicSettings)
        assertEquals(23f, restored.comicSettings.fontSize)
        val changed = restored.copy(comicSettings = restored.comicSettings.copy(fontSize = 32f, readingMode = ReadingMode.SCROLL))
        repository.save(changed)
        assertEquals(23f, repository.load().settings.fontSize)
        assertEquals(ReadingMode.PAGED, repository.load().settings.readingMode)
        assertEquals(32f, repository.load().comicSettings.fontSize)
        assertEquals(ReadingMode.SCROLL, repository.load().comicSettings.readingMode)
    }

    @Test fun groupsWithTheSameNameInDifferentModesAndBookMembershipRoundTrip() {
        val novelGroup = ShelfGroup("收藏", LibraryMode.NOVEL)
        val comicGroup = ShelfGroup("收藏", LibraryMode.COMIC)
        val books = listOf(
            Book(title = "小说", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
                chapters = listOf(Chapter(title = "正文")), groupId = novelGroup.id, characterOffset = 42, progress = .3f),
            Book(title = "漫画", format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
                chapters = listOf(Chapter(title = "第一话", pages = listOf(PageSource("comic.png")))), groupId = comicGroup.id),
            Book(title = "未分组", format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = emptyList())
        )
        val note = Annotation(bookId = books.first().id, chapterIndex = 0, pageIndex = 0, note = "保留笔记")
        val state = LibraryState(books = books, groups = listOf(novelGroup, comicGroup), annotations = listOf(note))
        repository.save(state)
        assertEquals(state, repository.load())
        assertEquals(2, repository.load().groups.map { it.id }.distinct().size)
        assertNull(repository.load().books.last().groupId)
    }
}
