package com.eyue.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AtomicFile
import com.eyue.reader.data.LegacyLocatorFiles
import com.eyue.reader.data.LegacyEpubIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LibraryRepository(private val context: Context) {
    internal fun epubIndexFile(book: Book): File? = LegacyEpubIndex.find(context, book)
    private val stateFile = AtomicFile(File(context.filesDir, "library.json"))
    private val positionsFile = AtomicFile(File(context.filesDir, "reading_positions.json"))
    private var positionRevision: String? = null
    private companion object {
        // AtomicFile needs a shared lock across the UI and background repository instances.
        val stateLock = Any()
    }

    fun load(): LibraryState = synchronized(stateLock) {
        if (!stateFile.baseFile.exists() && !File(stateFile.baseFile.path + ".bak").exists()) return LibraryState()
        try {
            val json = JSONObject(stateFile.readFully().toString(Charsets.UTF_8))
            val settings = readSettings(json.optJSONObject("settings") ?: JSONObject())
            val comic = json.optJSONObject("comicSettings")?.let { readSettings(it, ReaderSettings().withDefaultComicMargins()) }
                ?: settings.withDefaultComicMargins()
            val state = LibraryState(
                books = json.array("books").objects().map(::readBook),
                annotations = json.array("annotations").objects().map(::readAnnotation),
                settings = settings,
                readingSeconds = json.optLong("readingSeconds", 0),
                comicSettings = comic.copy(nightMode = settings.nightMode),
                groups = json.array("groups").objects().map {
                    ShelfGroup(it.getString("name"), enumValue(it.optString("mode"), LibraryMode.NOVEL), it.getString("id"))
                },
                cloudAutoBackup = json.optBoolean("cloudAutoBackup", false),
                cloudAutoBackupMetered = json.optBoolean("cloudAutoBackupMetered", false),
                cloudIdentity = json.nullableString("cloudIdentity"),
                speechEngine = enumValue(json.optString("speechEngine"), SpeechEngine.SYSTEM),
                themeMode = enumValue(json.optString("themeMode"), if (settings.nightMode) ThemeMode.DARK else ThemeMode.LIGHT),
                aiSpeechFallback = json.optBoolean("aiSpeechFallback", false),
                einkMode = json.optBoolean("einkMode", false),
                tabletDualPage = json.optBoolean("tabletDualPage", false),
                appTheme = enumValue(json.optString("appTheme"), AppThemeStyle.CLASSIC),
                showRecentReading = json.optBoolean("showRecentReading", true),
                commonVoiceRoles = if (json.has("commonVoiceRoles")) json.array("commonVoiceRoles").objects().map(BookVoiceRole::fromJson)
                    else defaultCommonVoiceRoles()
            )
            positionRevision = json.optString("positionRevision", "legacy")
            return readPositions(state)
        } catch (error: Exception) {
            throw IllegalStateException("书架数据无法读取，原文件已保留：${error.message}", error)
        }
    }

    fun save(state: LibraryState): Unit = synchronized(stateLock) {
        val revision = UUID.randomUUID().toString()
        val json = JSONObject().put("version", 13)
            .put("positionRevision", revision)
            .put("books", JSONArray(state.books.map(::writeBook)))
            .put("annotations", JSONArray(state.annotations.map(::writeAnnotation)))
            .put("settings", writeSettings(state.settings))
            .put("comicSettings", writeSettings(state.comicSettings.copy(nightMode = state.settings.nightMode)))
            .put("groups", JSONArray(state.groups.map {
                JSONObject().put("id", it.id).put("name", it.name).put("mode", it.mode.name)
            }))
            .put("readingSeconds", state.readingSeconds).put("cloudAutoBackup", state.cloudAutoBackup)
            .put("cloudAutoBackupMetered", state.cloudAutoBackupMetered).put("cloudIdentity", state.cloudIdentity)
            .put("speechEngine", state.speechEngine.name).put("themeMode", state.themeMode.name)
            .put("aiSpeechFallback", state.aiSpeechFallback)
            .put("einkMode", state.einkMode).put("tabletDualPage", state.tabletDualPage)
            .put("appTheme", state.appTheme.name)
            .put("showRecentReading", state.showRecentReading)
            .put("commonVoiceRoles", JSONArray(state.commonVoiceRoles.map { it.toJson() }))
        val output = stateFile.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            stateFile.finishWrite(output)
            positionRevision = revision
            // A new revision makes a surviving old sidecar harmless even after an interrupted save.
            positionsFile.delete()
        } catch (error: Exception) {
            stateFile.failWrite(output)
            throw error
        }
    }

    fun savePositions(state: LibraryState): Unit = synchronized(stateLock) {
        if (positionRevision == null) {
            if (!stateFile.baseFile.exists() && !File(stateFile.baseFile.path + ".bak").exists()) {
                save(state)
                return
            }
            load()
        }
        val json = JSONObject().put("positionRevision", positionRevision).put("readingSeconds", state.readingSeconds)
            .put("books", JSONArray(state.books.map { book ->
                JSONObject().put("id", book.id).put("contentVersion", book.contentVersion)
                    .put("chapterIndex", book.chapterIndex).put("pageIndex", book.pageIndex)
                    .put("characterOffset", book.characterOffset).put("progress", book.progress.toDouble())
                    .put("lastReadAt", book.lastReadAt).put("positionImagePath", book.positionImagePath)
            }))
        val output = positionsFile.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            positionsFile.finishWrite(output)
        } catch (error: Exception) {
            positionsFile.failWrite(output)
            throw error
        }
    }

    private fun readPositions(state: LibraryState): LibraryState {
        if (!positionsFile.baseFile.exists() && !File(positionsFile.baseFile.path + ".bak").exists()) return state
        val json = JSONObject(positionsFile.readFully().toString(Charsets.UTF_8))
        if (json.optString("positionRevision") != positionRevision) return state
        val positions = json.array("books").objects().associateBy { it.optString("id") }
        return state.copy(readingSeconds = json.optLong("readingSeconds", state.readingSeconds), books = state.books.map { book ->
            val position = positions[book.id]?.takeIf { it.optInt("contentVersion", -1) == book.contentVersion }
                ?: return@map book
            book.copy(chapterIndex = position.optInt("chapterIndex", book.chapterIndex),
                pageIndex = position.optInt("pageIndex", book.pageIndex),
                characterOffset = position.optInt("characterOffset", book.characterOffset),
                progress = position.optDouble("progress", book.progress.toDouble()).toFloat(),
                lastReadAt = position.optLong("lastReadAt", book.lastReadAt),
                positionImagePath = position.nullableString("positionImagePath"))
        })
    }

    @Synchronized
    fun removeBookFiles(book: Book) {
        val booksDirectory = File(context.filesDir, "books").canonicalFile
        val target = File(booksDirectory, book.id).canonicalFile
        require(target.parentFile == booksDirectory) { "图书目录越界" }
        if (target.exists()) check(target.deleteRecursively()) { "图书文件删除失败" }
        check(LegacyLocatorFiles(File(context.filesDir, "readium-locators")).removeBookNow(book.id)) { "EPUB 阅读索引删除失败" }
    }

    suspend fun createDemoBooks(): List<Book> = withContext(Dispatchers.IO) {
        listOf(createDemoNovel(), createDemoComic())
    }

    internal fun readBook(json: JSONObject): Book = Book(
        id = json.getString("id"), title = json.getString("title"), author = json.optString("author", ""),
        format = enumValue(json.optString("format"), BookFormat.TXT),
        mode = enumValue(json.optString("mode"), LibraryMode.NOVEL),
        coverPath = json.nullableString("coverPath"),
        chapters = json.array("chapters").objects().map(::readChapter),
        chapterIndex = json.optInt("chapterIndex", 0), pageIndex = json.optInt("pageIndex", 0),
        characterOffset = json.optInt("characterOffset", 0), progress = json.optDouble("progress", 0.0).toFloat(),
        lastReadAt = json.optLong("lastReadAt", 0), addedAt = json.optLong("addedAt", 0),
        isDemo = json.optBoolean("isDemo", false), sourcePath = json.nullableString("sourcePath"),
        positionImagePath = json.nullableString("positionImagePath"), contentVersion = json.optInt("contentVersion", 1),
        sourceUri = json.nullableString("sourceUri"), sourceLinked = json.optBoolean("sourceLinked", false),
        groupId = json.nullableString("groupId"),
        cloudEnabled = json.optBoolean("cloudEnabled", false), cloudOnly = json.optBoolean("cloudOnly", false),
        cloudBackupAt = json.optLong("cloudBackupAt", 0), hasTextEdits = json.optBoolean("hasTextEdits", false),
        voiceRoles = json.array("voiceRoles").objects().map(BookVoiceRole::fromJson),
        sourceFileName = json.nullableString("sourceFileName")?.takeIf { it.isNotBlank() },
        sourceFileBytes = if (json.isNull("sourceFileBytes")) null else json.optLong("sourceFileBytes", -1).takeIf { it >= 0 }
    )

    internal fun readChapter(chapter: JSONObject): Chapter =
            Chapter(id = chapter.getString("id"), title = chapter.getString("title"),
                textPath = chapter.nullableString("textPath"),
                pages = chapter.array("pages").objects().map {
                    PageSource(it.getString("path"), it.optInt("pdfPage", -1), it.nullableString("textPath"))
                }, inlineImages = chapter.array("inlineImages").objects().map { InlineImage(it.getInt("offset"), it.getString("path")) },
                sourceStyles = chapter.array("sourceStyles").objects().map(::readSourceStyle),
                sourceCssStyles = chapter.array("sourceCssStyles").objects().map(::readSourceStyle),
                subsections = chapter.array("subsections").objects().map(::readComicSection),
                characterCount = chapter.optInt("characterCount", -1), lazySourcePath = chapter.nullableString("lazySourcePath"),
                tocParentTitle = chapter.nullableString("tocParentTitle")?.takeIf { it.isNotBlank() },
                sourceFootnotes = chapter.array("sourceFootnotes").objects().map { footnote ->
                    SourceFootnote(footnote.getInt("start"), footnote.getInt("end"), footnote.getString("label"),
                        footnote.getString("content"), footnote.nullableString("imagePath"))
                })

    internal fun writeBook(book: Book): JSONObject = JSONObject()
        .put("id", book.id).put("title", book.title).put("author", book.author)
        .put("format", book.format.name).put("mode", book.mode.name).put("coverPath", book.coverPath)
        .put("chapters", JSONArray(book.chapters.map(::writeChapter)))
        .put("chapterIndex", book.chapterIndex).put("pageIndex", book.pageIndex)
        .put("characterOffset", book.characterOffset).put("progress", book.progress.toDouble())
        .put("lastReadAt", book.lastReadAt).put("addedAt", book.addedAt).put("isDemo", book.isDemo)
        .put("sourcePath", book.sourcePath)
        .put("positionImagePath", book.positionImagePath).put("contentVersion", book.contentVersion)
        .put("sourceUri", book.sourceUri).put("sourceLinked", book.sourceLinked).put("groupId", book.groupId)
        .put("cloudEnabled", book.cloudEnabled).put("cloudOnly", book.cloudOnly).put("cloudBackupAt", book.cloudBackupAt)
        .put("hasTextEdits", book.hasTextEdits)
        .put("sourceFileName", book.sourceFileName).put("sourceFileBytes", book.sourceFileBytes)
        .apply { if (book.voiceRoles.isNotEmpty()) put("voiceRoles", JSONArray(book.voiceRoles.map { it.toJson() })) }

    internal fun writeChapter(chapter: Chapter): JSONObject =
            JSONObject().put("id", chapter.id).put("title", chapter.title).put("textPath", chapter.textPath)
                .put("pages", JSONArray(chapter.pages.map {
                    JSONObject().put("path", it.path).put("pdfPage", it.pdfPage).put("textPath", it.textPath)
                }))
                .put("inlineImages", JSONArray(chapter.inlineImages.map { JSONObject().put("offset", it.offset).put("path", it.path) }))
                .put("sourceStyles", JSONArray(chapter.sourceStyles.map(::writeSourceStyle)))
                .put("sourceCssStyles", JSONArray(chapter.sourceCssStyles.map(::writeSourceStyle)))
                .put("subsections", JSONArray(chapter.subsections.map(::writeComicSection)))
                .put("characterCount", chapter.characterCount).put("lazySourcePath", chapter.lazySourcePath)
                .put("tocParentTitle", chapter.tocParentTitle)
                .put("sourceFootnotes", JSONArray(chapter.sourceFootnotes.map { footnote ->
                    JSONObject().put("start", footnote.start).put("end", footnote.end).put("label", footnote.label)
                        .put("content", footnote.content).put("imagePath", footnote.imagePath)
                }))

    private fun readComicSection(json: JSONObject): ComicSection = ComicSection(
        json.getString("title"), json.getInt("startPage"), json.getInt("endPage"),
        json.array("children").objects().map(::readComicSection)
    )

    private fun writeComicSection(section: ComicSection): JSONObject = JSONObject()
        .put("title", section.title).put("startPage", section.startPage).put("endPage", section.endPage)
        .put("children", JSONArray(section.children.map(::writeComicSection)))

    private fun readSourceStyle(json: JSONObject): SourceTextStyle = SourceTextStyle(
        start = json.getInt("start"), end = json.getInt("end"),
        fontScale = json.nullableFloat("fontScale"), bold = json.nullableBoolean("bold"), italic = json.nullableBoolean("italic"),
        alignment = json.nullableString("alignment")?.let { enumValue(it, SourceTextAlignment.START) },
        firstLineIndentEm = json.nullableFloat("firstLineIndentEm"), restLineIndentEm = json.nullableFloat("restLineIndentEm"),
        paragraph = json.optBoolean("paragraph", false),
        lineHeightEm = json.nullableFloat("lineHeightEm"), letterSpacingEm = json.nullableFloat("letterSpacingEm"),
        spaceBeforeEm = json.nullableFloat("spaceBeforeEm"), spaceAfterEm = json.nullableFloat("spaceAfterEm")
    )

    private fun writeSourceStyle(style: SourceTextStyle): JSONObject = JSONObject()
        .put("start", style.start).put("end", style.end).put("fontScale", style.fontScale)
        .put("bold", style.bold).put("italic", style.italic).put("alignment", style.alignment?.name)
        .put("firstLineIndentEm", style.firstLineIndentEm).put("restLineIndentEm", style.restLineIndentEm)
        .put("paragraph", style.paragraph)
        .put("lineHeightEm", style.lineHeightEm).put("letterSpacingEm", style.letterSpacingEm)
        .put("spaceBeforeEm", style.spaceBeforeEm).put("spaceAfterEm", style.spaceAfterEm)

    private fun JSONObject.nullableFloat(key: String): Float? = if (has(key) && !isNull(key)) getDouble(key).toFloat() else null
    private fun JSONObject.nullableBoolean(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null

    internal fun readAnnotation(json: JSONObject): Annotation = Annotation(
        id = json.getString("id"), bookId = json.getString("bookId"),
        chapterIndex = json.optInt("chapterIndex", 0), pageIndex = json.optInt("pageIndex", 0),
        characterOffset = json.optInt("characterOffset", 0), quote = json.optString("quote", ""),
        note = json.optString("note", ""), createdAt = json.optLong("createdAt", 0),
        kind = enumValue(json.optString("kind"), if (json.optString("note", "").isBlank()) AnnotationKind.BOOKMARK else AnnotationKind.NOTE),
        endOffset = json.optInt("endOffset", 0), color = if (json.has("color") && !json.isNull("color")) json.getLong("color") else null,
        imagePath = json.nullableString("imagePath"),
        underlineStyle = enumValue(json.optString("underlineStyle"), UnderlineStyle.STRAIGHT)
    )

    internal fun writeAnnotation(annotation: Annotation): JSONObject = JSONObject()
        .put("id", annotation.id).put("bookId", annotation.bookId).put("chapterIndex", annotation.chapterIndex)
        .put("pageIndex", annotation.pageIndex).put("characterOffset", annotation.characterOffset)
        .put("quote", annotation.quote).put("note", annotation.note).put("createdAt", annotation.createdAt)
        .put("kind", annotation.kind.name).put("endOffset", annotation.endOffset).put("color", annotation.color)
        .put("imagePath", annotation.imagePath).put("underlineStyle", annotation.underlineStyle.name)

    internal fun readSettings(json: JSONObject, defaults: ReaderSettings = ReaderSettings()): ReaderSettings {
        fun margin(key: String, fallback: Float): Float = json.optDouble(key, fallback.toDouble()).toFloat()
            .let { if (it.isFinite()) it.coerceIn(0f, 64f) else fallback }
        val background = enumValue(json.optString("background"), defaults.background)
        val legacyNight = !json.has("nightMode") && background == ReaderBackground.DARK
        val legacyRule = json.optString("chapterRule", defaults.chapterRule).let {
            if (it == LEGACY_DEFAULT_CHAPTER_RULE) DEFAULT_CHAPTER_RULE else it
        }
        val presets = json.array("chapterRules").objects().mapNotNull {
            val id = it.optString("id")
            val name = it.optString("name")
            val pattern = it.optString("pattern")
            if (id.isBlank() || name.isBlank() || runCatching { TextParser.validateRule(pattern) }.isFailure) null
            else ChapterRulePreset(id, name, pattern)
        }.distinctBy { it.id }.toMutableList()
        if (presets.none { it.id == "default" }) presets.add(0, defaults.chapterRules.single())
        val selected = if (!json.has("chapterRules") && legacyRule != DEFAULT_CHAPTER_RULE) {
            ChapterRulePreset("legacy-custom", "自定义规则", legacyRule).also { presets += it }
        } else presets.find { it.id == json.optString("activeChapterRuleId", "default") } ?: presets.first()
        return ReaderSettings(
            fontSize = json.optDouble("fontSize", defaults.fontSize.toDouble()).toFloat(),
            lineHeight = json.optDouble("lineHeight", defaults.lineHeight.toDouble()).toFloat(),
            paragraphSpacing = json.optDouble("paragraphSpacing", defaults.paragraphSpacing.toDouble()).toFloat().let {
                if (it.isFinite()) it.coerceIn(0f, 2f) else defaults.paragraphSpacing
            },
            fontName = json.optString("fontName", defaults.fontName), fontPath = json.nullableString("fontPath"),
            bold = json.optBoolean("bold", defaults.bold),
            background = if (legacyNight) ReaderBackground.PAPER else background,
            backgroundImagePath = json.nullableString("backgroundImagePath"),
            nightMode = json.optBoolean("nightMode", legacyNight || defaults.nightMode),
            brightness = json.optDouble("brightness", defaults.brightness.toDouble()).toFloat(),
            letterSpacing = json.optDouble("letterSpacing", defaults.letterSpacing.toDouble()).toFloat(),
            readingMode = enumValue(json.optString("readingMode"), defaults.readingMode),
            pageTurnStyle = enumValue(json.optString("pageTurnStyle"), defaults.pageTurnStyle),
            chineseConversion = enumValue(json.optString("chineseConversion"), defaults.chineseConversion),
            showTime = json.optBoolean("showTime", defaults.showTime),
            showBattery = json.optBoolean("showBattery", defaults.showBattery),
            showChapter = json.optBoolean("showChapter", defaults.showChapter),
            volumePaging = json.optBoolean("volumePaging", defaults.volumePaging),
            keepScreenOn = json.optBoolean("keepScreenOn", defaults.keepScreenOn),
            ttsSpeed = json.optDouble("ttsSpeed", defaults.ttsSpeed.toDouble()).toFloat(),
            ttsFollowPage = json.optBoolean("ttsFollowPage", false),
            chapterRule = selected.pattern,
            fonts = json.array("fonts").objects().map { ImportedFont(it.getString("name"), it.getString("path")) },
            chapterRules = presets, activeChapterRuleId = selected.id,
            appearanceProfiles = readReaderThemeProfiles(json.optJSONArray("appearanceProfiles"), defaults.appearanceSnapshot()),
            activeAppearanceProfileId = json.nullableString("activeAppearanceProfileId"),
            hierarchicalContents = json.optBoolean("hierarchicalContents", defaults.hierarchicalContents),
            useSourceCss = json.optBoolean("useSourceCss", defaults.useSourceCss),
            continuousEbookScroll = json.optBoolean("continuousEbookScroll", defaults.continuousEbookScroll),
            marginLeft = margin("marginLeft", defaults.marginLeft), marginRight = margin("marginRight", defaults.marginRight),
            marginTop = margin("marginTop", defaults.marginTop), marginBottom = margin("marginBottom", defaults.marginBottom)
        )
    }

    internal fun writeSettings(settings: ReaderSettings): JSONObject {
        val pattern = if (settings.chapterRule == LEGACY_DEFAULT_CHAPTER_RULE) DEFAULT_CHAPTER_RULE else settings.chapterRule
        val selected = settings.chapterRules.find { it.id == settings.activeChapterRuleId && it.pattern == pattern }
            ?: settings.chapterRules.find { it.pattern == pattern }
            ?: ChapterRulePreset("legacy-custom", "自定义规则", pattern)
        val presets = if (settings.chapterRules.any { it.id == selected.id })
            settings.chapterRules.map { if (it.id == selected.id) selected else it } else settings.chapterRules + selected
        return JSONObject()
        .put("fontSize", settings.fontSize.toDouble()).put("lineHeight", settings.lineHeight.toDouble())
        .put("paragraphSpacing", settings.paragraphSpacing.toDouble())
        .put("fontName", settings.fontName).put("fontPath", settings.fontPath).put("bold", settings.bold)
        .put("background", settings.background.name).put("readingMode", settings.readingMode.name)
        .put("pageTurnStyle", settings.pageTurnStyle.name)
        .put("chineseConversion", settings.chineseConversion.name)
        .put("backgroundImagePath", settings.backgroundImagePath)
        .put("nightMode", settings.nightMode).put("brightness", settings.brightness.toDouble())
        .put("letterSpacing", settings.letterSpacing.toDouble())
        .put("showTime", settings.showTime).put("showBattery", settings.showBattery).put("showChapter", settings.showChapter)
        .put("volumePaging", settings.volumePaging).put("keepScreenOn", settings.keepScreenOn)
        .put("ttsSpeed", settings.ttsSpeed.toDouble()).put("chapterRule", pattern)
        .put("ttsFollowPage", settings.ttsFollowPage)
        .put("appearanceProfiles", JSONArray(settings.appearanceProfiles.map { it.toJson() }))
        .put("activeAppearanceProfileId", settings.activeAppearanceProfileId)
        .put("hierarchicalContents", settings.hierarchicalContents)
        .put("useSourceCss", settings.useSourceCss)
        .put("continuousEbookScroll", settings.continuousEbookScroll)
        .put("marginLeft", settings.marginLeft.toDouble()).put("marginRight", settings.marginRight.toDouble())
        .put("marginTop", settings.marginTop.toDouble()).put("marginBottom", settings.marginBottom.toDouble())
        .put("chapterRules", JSONArray(presets.map {
            JSONObject().put("id", it.id).put("name", it.name).put("pattern", it.pattern)
        })).put("activeChapterRuleId", selected.id)
        .put("fonts", JSONArray(settings.fonts.map { JSONObject().put("name", it.name).put("path", it.path) }))
    }

    private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun createDemoNovel(): Book {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        val titles = listOf("第一章 雨停之后", "第二章 书页里的车票", "第三章 走到河岸", "第四章 一盏晚灯", "第五章 回信", "第六章 新的书架")
        val paragraphs = listOf(
            listOf(
                "雨在傍晚五点停了。林遥推开旧书店的玻璃门，门上的铜铃轻轻响了一声。她原本只想借屋檐躲雨，却看见门后挂着一张手写的纸：书可以借，故事也可以。",
                "店里没有音乐，只有窗台上的水滴缓慢落进陶碗。老板坐在柜台后修一本地图册，抬头问她想找什么。林遥想了想，说自己还不知道。老板笑着指向最里面的书架，说那就先从不知道的地方开始。",
                "靠墙的木架比她记忆里祖母家的还旧，边角被手掌磨得发亮。诗集挨着天文图鉴，菜谱旁边是航海日记。分类并不整齐，却像一些认识很久的人，愿意为彼此腾出位置。",
                "她抽出一本讲河流的小书。扉页上有人用铅笔画了一朵云，下面写着：走得慢一点，水面才会给你看天空。字迹细小，最后一笔拖得很长。",
                "街灯亮起来的时候，老板把一只空纸袋递给她。纸袋有淡淡的茶叶味。林遥说自己没有打算买书，老板便回答，带它回去读一晚吧，明天若有空再来。",
                "她把书放进包里，走到路口时又回过头。书店的窗像一片暖黄的叶子，贴在蓝色的雨后街道上。那一刻她忽然觉得，这座住了三年的城市仍然有许多地方等着她认识。"
            ),
            listOf(
                "林遥在第二天的早餐桌上打开小书。读到第十七页，一张蓝色车票滑了出来。车票上的日期已经模糊，终点站却清清楚楚：青桥。",
                "她把车票翻过来，看见背面有一行字：如果你找到它，请替我去看看那棵树。没有姓名，也没有地址。像是一个没有说完的问题，安静地留在纸上。",
                "书店老板看了车票许久，说青桥以前有一个很小的车站，停运后修成了步行道。那里有好几棵树，他也不知道纸上的人指的是哪一棵。",
                "林遥问，是不是可以把票贴在门口，让它的主人回来领取。老板摇了摇头，说有的东西留下来，并不是为了被领走。他把一张旧城区地图摊在桌上，手指沿着河岸画了一条路线。",
                "地图边上夹着几张明信片，画的是同一座桥不同季节的样子。春天有白色的花，夏天有骑自行车的人，冬天的桥面留着一串脚印。林遥把这些画面看了很久。",
                "她最后借走了地图，并答应在星期六去青桥。离开时老板送给她一枚很普通的回形针，说地图容易被风吹散，要夹紧一点。她将回形针别在蓝色车票的角上，像给一个迟到的约定加上了标记。"
            ),
            listOf(
                "星期六的天空很高。林遥沿着地图上的线走，穿过早市和一条种满香樟的小路。河水出现在转角处，先是一点银光，接着是一整片缓缓流动的明亮。",
                "青桥比明信片上短，桥栏新刷了油漆，旁边仍然留着旧车站的站牌。一个老人坐在长椅上削梨，看到她手里的地图，问她是不是来找以前的铁路。",
                "她把车票给老人看。老人眯着眼睛认了半天，指向站台尽头：那里的榆树，许多人从前都在树下等车。他说这话时，用削梨的小刀轻轻点了点空气。",
                "榆树并不高大，树根顶起了两块砖。林遥在树下找到一张小小的金属牌，写着种植的年份。旁边有一个木盒，盒盖刻着：给未来经过这里的人。",
                "木盒里放着几支铅笔和一个厚笔记本。有人记录今天看到的鸟，有人写下一句生日祝愿，还有人认真画了河岸的野花。字迹各不相同，纸页却被照顾得很好。",
                "她在空白页上画了那张车票，然后写道：树很好，今天有风。写完后她坐了一会儿，听风穿过叶子的声音。河对岸有人放风筝，红色尾巴从蓝天上轻轻划过去。"
            ),
            listOf(
                "林遥回到书店时已近黄昏。她带了一张榆树的照片，老板用干净的布擦了擦眼镜，仔细地看。照片里树叶被夕阳照得透明，木盒躲在阴影里，只露出一角。",
                "原来盒子还在，老板说。他从柜台下面找出一叠旧信封，选出一只没有封口的。信封里是一张比车票更早的照片，两个年轻人站在榆树下，手里各拿着一本书。",
                "照片右边的人是老板，左边的人是他很久没有见面的朋友。那时他们约定轮流把看完的书放进木盒，让下一位旅客带走。后来车站不再运行，盒子里的书慢慢变成了留言。",
                "老板说起这些事时没有叹气，只是把柜台上的灯拨亮了一格。他说朋友去了很远的地方，但每年仍然寄来一张明信片，画一座从未重复过的桥。",
                "林遥想到书架边那些明信片，便问蓝色车票是不是老板放进去的。老板点了点头：今年腿脚不好，没能去看看树。你说不知道要找什么，我就想，也许你愿意帮一个小忙。",
                "他们一起把照片贴在新明信片背面。林遥写了日期，老板写了地址。窗外的路灯一盏接一盏亮起来，书店的桌上也多了一盏灯，把照片里的两个年轻人照得很清楚。"
            ),
            listOf(
                "回信是在半个月以后到的。信封上贴着一枚海鸟邮票，里面有一张画着蓝色铁桥的明信片。朋友说收到树的照片很开心，还记得当初种树时，铲子只有一把，大家轮流用。",
                "明信片末尾另外写了一句话，请替我谢谢那位带地图去河岸的读者。如果她愿意，下次可以告诉我那里的鸟都是什么颜色。",
                "林遥读了两遍，决定买一本小小的观鸟手册。她并不擅长辨认鸟，只能分清麻雀和鸽子。老板为她找出一本有清楚插图的旧书，扉页空白，纸边带着太阳晒过的浅黄色。",
                "她再次走到青桥的时候，发现木盒的本子已经换了一本。第一页写着春天结束了，夏天正在来。有人把一片榆树叶压在透明的纸袋里，又留下一段关于树荫的诗。",
                "她在河岸看见一只灰色的小鸟，眼睛旁边有一道白线。手册翻了很久才找到答案。林遥把名字抄进本子，画了一双并不对称的翅膀，还注明画得不好，但颜色是真的。",
                "回去的路上她没有再看地图。香樟小路、早市、书店，这些原本分散的地点已经在她心里连成了线。她想着下一封回信该写些什么，步子不知不觉放慢了。"
            ),
            listOf(
                "夏天第一场雷雨来得突然。书店门口又站满了避雨的人。林遥帮老板端出几把折叠椅，把窗台上的陶碗换成了更大的盆。铜铃不断响起，店里比平常热闹许多。",
                "一个带着孩子的母亲问有没有可以读得很短的故事。林遥从木架上取下一本薄薄的童话，孩子坐在椅子上，认真地指着封面的月亮。他说月亮像一艘没有窗的船。",
                "老板决定在门边加一个小书架，专门放可以借走的书。林遥写标签，另外两位客人帮忙搬椅子。每本书中都夹着一张空白卡片，卡片上只印了一句话：读完之后，你想告诉下一位读者什么？",
                "她把讲河流的小书放在最上层。蓝色车票已经寄给了远方的朋友，她又夹进去一张新的纸片，画了一棵叶子很多的榆树。纸片背面写着：从这里走到河岸，不必很着急。",
                "雨停时孩子不愿离开。他问明天还能不能来，老板说门上的铜铃每天都会响。母亲牵着他的手走出书店，孩子在路口挥手，手里拿着那本月亮像船的童话。",
                "林遥最后关上窗，桌上的灯却继续亮着。城市里有太多说不完的故事，有些藏在车票后面，有些刚刚写进一张空白卡片。她拿起新本子，在第一页写下日期，然后写：今天，书架又多了一层。"
            )
        )
        val source = File(directory, "source.txt").apply {
            writeText(titles.mapIndexed { index, title ->
                title + "\n\n" + paragraphs[index].joinToString("\n\n")
            }.joinToString("\n\n"))
        }
        File(directory, "metadata.json").writeText(JSONObject().put("title", "雨后的旧书店")
            .put("author", "E阅示例").toString())
        val chapters = titles.mapIndexed { index, title ->
            val file = File(directory, "chapter-$index.txt").apply {
                writeText(title + "\n\n" + paragraphs[index].joinToString("\n\n"))
            }
            Chapter(title = title, textPath = file.absolutePath)
        }
        val cover = File(directory, "cover.png")
        val bitmap = Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(33, 55, 70))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(230, 204, 152)
            drawCircle(440f, 166f, 73f, paint)
            paint.color = Color.rgb(70, 90, 96)
            drawRoundRect(RectF(80f, 430f, 520f, 730f), 12f, 12f, paint)
            paint.color = Color.rgb(224, 182, 119)
            drawRect(122f, 480f, 478f, 660f, paint)
            paint.color = Color.rgb(245, 237, 219)
            paint.textSize = 52f
            paint.typeface = Typeface.create("serif", Typeface.NORMAL)
            drawText("雨后的旧书店", 70f, 325f, paint)
            paint.textSize = 24f
            drawText("E 阅 · 原创示例", 74f, 374f, paint)
        }
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Book(id = id, title = "雨后的旧书店", author = "E阅示例", format = BookFormat.TXT,
            mode = LibraryMode.NOVEL, coverPath = cover.absolutePath, chapters = chapters,
            isDemo = true, sourcePath = source.absolutePath)
    }

    private fun createDemoComic(): Book {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        File(directory, "metadata.json").writeText(JSONObject().put("title", "小云的散步")
            .put("author", "E阅示例").toString())
        val narration = listOf(
            listOf("第一站 · 城市醒来", "小云：今天的风，要去哪里？", "风：先去看看街角的新书店。"),
            listOf("第二站 · 河边的朋友", "小云：树下的盒子里是什么？", "小树：是大家留给明天的话。"),
            listOf("第三站 · 带一本书回家", "小云：原来散步也能找到故事。", "风：明天，我们再走一条新路。")
        )
        val pages = narration.mapIndexed { index, lines ->
            val bitmap = Bitmap.createBitmap(900, 1300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(249, 244, 232))
            paint.color = Color.rgb(40, 65, 73)
            paint.textSize = 43f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("小云的散步", 62f, 90f, paint)
            paint.textSize = 27f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText(lines[0], 64f, 138f, paint)
            for (panel in 0..1) {
                val top = 186f + panel * 488f
                paint.color = listOf(Color.rgb(208, 228, 229), Color.rgb(220, 232, 210), Color.rgb(232, 217, 195))[index]
                canvas.drawRoundRect(RectF(48f, top, 852f, top + 438f), 24f, 24f, paint)
                paint.color = Color.rgb(167, 195, 166)
                canvas.drawOval(RectF(65f, top + 288f, 846f, top + 493f), paint)
                if (index == 1) {
                    paint.color = Color.rgb(139, 106, 75)
                    canvas.drawRect(636f, top + 144f, 668f, top + 342f, paint)
                    paint.color = Color.rgb(92, 142, 108)
                    canvas.drawCircle(652f, top + 133f, 96f, paint)
                } else {
                    paint.color = Color.rgb(157, 132, 108)
                    canvas.drawRoundRect(RectF(574f, top + 168f, 790f, top + 351f), 10f, 10f, paint)
                    paint.color = Color.rgb(233, 199, 127)
                    canvas.drawRect(602f, top + 206f, 761f, top + 314f, paint)
                }
                paint.color = Color.WHITE
                canvas.drawOval(RectF(165f, top + 163f, 420f, top + 299f), paint)
                canvas.drawCircle(230f, top + 188f, 67f, paint)
                canvas.drawCircle(315f, top + 169f, 77f, paint)
                paint.color = Color.rgb(40, 65, 73)
                canvas.drawCircle(256f, top + 225f, 7f, paint)
                canvas.drawCircle(321f, top + 225f, 7f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                canvas.drawArc(RectF(271f, top + 233f, 305f, top + 254f), 0f, 180f, false, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawRoundRect(RectF(76f, top + 27f, 820f, top + 102f), 22f, 22f, paint)
                paint.color = Color.rgb(40, 65, 73)
                paint.textSize = 30f
                canvas.drawText(lines[panel + 1], 99f, top + 76f, paint)
            }
            paint.textSize = 23f
            paint.color = Color.rgb(97, 113, 111)
            canvas.drawText("原创阅读示例 · ${index + 1} / 3", 302f, 1245f, paint)
            val file = File(directory, "page-${index + 1}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            PageSource(file.absolutePath)
        }
        return Book(id = id, title = "小云的散步", author = "E阅示例", format = BookFormat.IMAGES,
            mode = LibraryMode.COMIC, coverPath = pages.first().path,
            chapters = listOf(Chapter(title = "第一话 小小的发现", pages = pages)),
            isDemo = true, sourcePath = directory.absolutePath)
    }
}
