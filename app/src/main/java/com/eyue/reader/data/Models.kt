package com.eyue.reader.data

import java.util.UUID

enum class LibraryMode { NOVEL, COMIC }
enum class BookFormat { EPUB, TXT, MOBI, PDF, IMAGES }
enum class ReadingMode { SCROLL, PAGED, VERTICAL_PAGED }
enum class PageTurnStyle { COVER, SLIDE, CURL }
enum class ReaderBackground { PAPER, WHITE, GREEN, DARK, IMAGE }
enum class AnnotationKind { BOOKMARK, NOTE, HIGHLIGHT, UNDERLINE }
enum class UnderlineStyle { STRAIGHT, WAVY }
enum class SpeechEngine { SYSTEM, AI }

data class ShelfGroup(val name: String, val mode: LibraryMode, val id: String = UUID.randomUUID().toString())

data class PageSource(val path: String, val pdfPage: Int = -1, val textPath: String? = null)
data class InlineImage(val offset: Int, val path: String)
/** All ranges are [startPage, endPage) in the owning chapter's flat page list. */
data class ComicSection(val title: String, val startPage: Int, val endPage: Int,
                        val children: List<ComicSection> = emptyList())

data class Chapter(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val textPath: String? = null,
    val pages: List<PageSource> = emptyList(),
    val inlineImages: List<InlineImage> = emptyList(),
    val sourceStyles: List<SourceTextStyle> = emptyList(),
    val subsections: List<ComicSection> = emptyList(),
    val characterCount: Int = -1,
    val lazySourcePath: String? = null
)

data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String = "",
    val format: BookFormat,
    val mode: LibraryMode,
    val coverPath: String? = null,
    val chapters: List<Chapter>,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val characterOffset: Int = 0,
    val progress: Float = 0f,
    val lastReadAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val isDemo: Boolean = false,
    val sourcePath: String? = null,
    val positionImagePath: String? = null,
    val contentVersion: Int = 1,
    val sourceUri: String? = null,
    val sourceLinked: Boolean = false,
    val groupId: String? = null,
    val cloudEnabled: Boolean = false,
    val cloudOnly: Boolean = false,
    val cloudBackupAt: Long = 0,
    val hasTextEdits: Boolean = false,
    val voiceRoles: List<BookVoiceRole> = emptyList()
)

data class Annotation(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val characterOffset: Int = 0,
    val quote: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val kind: AnnotationKind = if (note.isBlank()) AnnotationKind.BOOKMARK else AnnotationKind.NOTE,
    val endOffset: Int = 0,
    val color: Long? = null,
    val imagePath: String? = null,
    val underlineStyle: UnderlineStyle = UnderlineStyle.STRAIGHT
)

data class ImportedFont(val name: String, val path: String)
data class ChapterRulePreset(val id: String = UUID.randomUUID().toString(), val name: String, val pattern: String)

data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.85f,
    val paragraphSpacing: Float = 0.8f,
    val fontName: String = "跟随系统",
    val fontPath: String? = null,
    val bold: Boolean = false,
    val background: ReaderBackground = ReaderBackground.PAPER,
    val backgroundImagePath: String? = null,
    val nightMode: Boolean = false,
    val brightness: Float = -1f,
    val letterSpacing: Float = 0f,
    val readingMode: ReadingMode = ReadingMode.SCROLL,
    val pageTurnStyle: PageTurnStyle = PageTurnStyle.SLIDE,
    val showTime: Boolean = true,
    val showBattery: Boolean = true,
    val showChapter: Boolean = true,
    val volumePaging: Boolean = false,
    val keepScreenOn: Boolean = true,
    val ttsSpeed: Float = 1f,
    val ttsFollowPage: Boolean = false,
    val chapterRule: String = DEFAULT_CHAPTER_RULE,
    val fonts: List<ImportedFont> = emptyList(),
    val chapterRules: List<ChapterRulePreset> = listOf(ChapterRulePreset("default", "默认规则", DEFAULT_CHAPTER_RULE)),
    val activeChapterRuleId: String = "default"
)

internal const val LEGACY_DEFAULT_CHAPTER_RULE = "^\\s*(第[零〇一二三四五六七八九十百千万两0-9]+[章节卷回部篇].*|[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s+\\d+.*|序章.*|序言.*|前言.*|楔子.*|尾声.*|后记.*)\\s*$"
private const val CHAPTER_TITLE_SUFFIX = "(?:[ \\t\\u3000:：、.．—-]+[^\\r\\n]{0,30}|[^\\r\\n。；;！？!?]{0,30})"
const val DEFAULT_CHAPTER_RULE = "^[ \\t\\u3000]*(?:" +
    "第[零〇一二三四五六七八九十百千万两0-9]{1,12}[章节卷回集部篇编辑册]" + CHAPTER_TITLE_SUFFIX + "|" +
    "(?i:chapter|part|section)[ \\t\\u3000]+(?:[0-9]{1,6}|[IVXLCDMivxlcdm]{1,12})(?=$|[ \\t\\u3000:：、.．—-])" + CHAPTER_TITLE_SUFFIX + "|" +
    "(?:序章|序言|前言|楔子|尾声|后记)" + CHAPTER_TITLE_SUFFIX + ")[ \\t\\u3000]*$"

data class LibraryState(
    val books: List<Book> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val settings: ReaderSettings = ReaderSettings(),
    val readingSeconds: Long = 0,
    val comicSettings: ReaderSettings = settings.copy(),
    val groups: List<ShelfGroup> = emptyList(),
    val cloudAutoBackup: Boolean = false,
    val cloudAutoBackupMetered: Boolean = false,
    val cloudIdentity: String? = null,
    val speechEngine: SpeechEngine = SpeechEngine.SYSTEM,
    val themeMode: ThemeMode = if (settings.nightMode) ThemeMode.DARK else ThemeMode.LIGHT,
    val aiSpeechFallback: Boolean = false,
    val commonVoiceRoles: List<BookVoiceRole> = defaultCommonVoiceRoles(),
    val einkMode: Boolean = false,
    val tabletDualPage: Boolean = false
)
