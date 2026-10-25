package com.eyue.reader.data.cloud

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import com.eyue.reader.data.*
import com.eyue.reader.data.LegacyEpubIndex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class CloudServiceContract {
    protected abstract fun context(): Context
    protected open fun sourcePath(file: File): String = file.absolutePath

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var transport: FakeCloudTransport
    private lateinit var service: CloudBackupService
    private lateinit var credentials: CloudCredentialStore

    @Before fun setup() {
        val original = context()
        root = File(original.cacheDir, "cloud-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(original) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        transport = FakeCloudTransport()
        credentials = object : CloudCredentialStore {
            var value: CloudCredentials? = CloudCredentials("unit-test@example.invalid", "not-a-real-password")
            override fun read() = value
            override fun write(credentials: CloudCredentials) { value = credentials }
            override fun clear() { value = null }
        }
        service = CloudBackupService(context, credentials, { _, _ -> transport })
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun readiumSpineIndexRecoversAtomicBackupAndSurvivesMetadataOnlyProgressUpdate() = runBlocking {
        val (original, originalState) = novel()
        val book = original.copy(format = BookFormat.EPUB, contentVersion = 8)
        val state = originalState.copy(books = listOf(book))
        val sidecar = LegacyEpubIndex.file(context, book).apply { parentFile!!.mkdirs() }
        val bytes = JSONObject().put("version", 1).put("contentVersion", 8).put("manifest", JSONObject())
            .put("spine", JSONArray(book.chapters.mapIndexed { index, chapter -> JSONObject()
                .put("chapterIndex", index).put("chapterId", chapter.id).put("href", "OPS/ch${index + 1}.xhtml") }))
            .toString().toByteArray(Charsets.UTF_8)
        File(sidecar.path + ".bak").writeBytes(bytes)
        val uploaded = service.upload(state, book)
        assertArrayEquals(bytes, sidecar.readBytes())
        val uploads = transport.binaryUploads
        val progressed = book.copy(characterOffset = 2, progress = .5f, lastReadAt = 1234)
        val updated = service.upload(state.copy(books = listOf(progressed)), progressed)
        assertEquals(uploads, transport.binaryUploads)
        assertEquals(uploaded.revision, updated.revision)
        val restored = service.download(updated)
        val restoredIndex = File(File(requireNotNull(restored.book.sourcePath)).parentFile, "readium-publication.json")
        assertArrayEquals(bytes, restoredIndex.readBytes())
        assertTrue(sidecar.delete())
        assertEquals(restoredIndex.canonicalFile, LegacyEpubIndex.find(context, restored.book)?.canonicalFile)
        assertEquals(2, restored.book.characterOffset)
        assertEquals(book.chapters.map { it.id }, restored.book.chapters.map { it.id })
    }

    @Test fun readiumIndexChangeMarksOnlyItsBookBackupFingerprintDirty() {
        val (original, originalState) = novel()
        val book = original.copy(format = BookFormat.EPUB)
        val state = originalState.copy(books = listOf(book))
        val sidecar = LegacyEpubIndex.file(context, book).apply { parentFile!!.mkdirs(); writeText("{\"version\":1}") }
        val repository = LibraryRepository(context)
        val before = cloudBackupFingerprint(repository, state, book)
        sidecar.writeText("{\"version\":1,\"contentVersion\":9}")
        assertNotEquals(before, cloudBackupFingerprint(repository, state, book))
    }

    @Test fun informationRestoreReportsCompletedEntriesIncludingMissingManifests() = runBlocking {
        val (first, state) = novel()
        val second = first.copy(id = "second-progress-book", title = "第二本书")
        service.upload(state, first)
        service.upload(state.copy(books = listOf(second)), second)
        transport.files.remove("books/${second.id}/manifest.json")
        val progress = mutableListOf<CloudOperationProgress>()
        val reader = CloudBackupService(context, credentials, { _, _ -> transport }, onProgress = { progress += it })
        val result = reader.list()
        assertEquals(listOf(first.id), result.map { it.book.id })
        assertEquals("读取云端书架", progress.first().stage)
        assertNull(progress.first().fraction)
        val completed = progress.filter { it.stage.startsWith("读取云端书籍信息") }
        assertEquals(listOf(0f, .5f, 1f), completed.map { it.fraction })
        assertTrue(completed.last().stage.contains("2 / 2"))
    }

    @Test fun anEmptyInformationRestoreReportsCompletionWithoutInventingBookWork() = runBlocking {
        val progress = mutableListOf<CloudOperationProgress>()
        val reader = CloudBackupService(context, credentials, { _, _ -> transport }, onProgress = { progress += it })
        assertTrue(reader.list().isEmpty())
        assertNull(progress.first().fraction)
        assertEquals(CloudOperationProgress("读取云端书籍信息 0 / 0", 1f), progress.last())
    }

    @Test fun aFailedInformationRestoreDoesNotReportFalseCompletion() = runBlocking {
        transport.files["catalog.json"] = "{".toByteArray()
        val progress = mutableListOf<CloudOperationProgress>()
        val reader = CloudBackupService(context, credentials, { _, _ -> transport }, onProgress = { progress += it })
        assertNotNull(runCatching { reader.list() }.exceptionOrNull())
        assertEquals("读取云端书架", progress.first().stage)
        assertTrue(progress.none { it.fraction == 1f })
    }

    @Test fun backgroundAssetAndContentIdentitySurviveInformationRestoreAndDownload() = runBlocking {
        val (book, state) = novel()
        val image = File(root, "background.jpg").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
        val settings = state.settings.copy(background = ReaderBackground.IMAGE, backgroundImagePath = sourcePath(image))
        val uploaded = service.upload(state.copy(settings = settings), book)
        val listed = service.list().single()
        assertEquals(cloudBookContentIdentity(context, book), listed.contentIdentity)
        assertEquals(uploaded.sourceSha256, listed.sourceSha256)
        assertNotNull(listed.settings.backgroundImagePath)
        assertFalse(listed.settings.backgroundImagePath!!.contains(root.absolutePath))
        val restored = service.download(listed)
        val restoredImage = File(requireNotNull(restored.settings.backgroundImagePath))
        assertEquals(ReaderBackground.IMAGE, restored.settings.background)
        assertArrayEquals(image.readBytes(), restoredImage.readBytes())
        assertTrue(restoredImage.absolutePath.startsWith(File(context.filesDir, "backgrounds").absolutePath))
        File(requireNotNull(restored.book.sourcePath)).delete()
        assertTrue(restoredImage.isFile)
    }

    @Test fun savedAppearanceProfilesBackUpTheirOwnUnusedFontsAndBackgroundsWithPortablePaths() = runBlocking {
        val (book, state) = novel()
        val background = File(root, "theme-background.png").apply { writeBytes(byteArrayOf(8, 7, 6)) }
        val font = File(root, "theme-font.ttf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val profile = ReaderThemeProfile(name = "保存的主题", appearance = ReaderAppearance(fontName = "主题字体",
            fontPath = sourcePath(font), background = ReaderBackground.IMAGE, backgroundImagePath = sourcePath(background)))
        service.upload(state.copy(settings = state.settings.copy(appearanceProfiles = listOf(profile))), book)
        val listed = service.list().single()
        val portable = listed.settings.appearanceProfiles.single().appearance
        assertFalse(requireNotNull(portable.fontPath).contains(root.absolutePath))
        assertFalse(requireNotNull(portable.backgroundImagePath).contains(root.absolutePath))
        val restored = service.download(listed).settings.appearanceProfiles.single()
        assertEquals(profile.name, restored.name)
        assertArrayEquals(font.readBytes(), File(requireNotNull(restored.appearance.fontPath)).readBytes())
        assertArrayEquals(background.readBytes(), File(requireNotNull(restored.appearance.backgroundImagePath)).readBytes())
    }

    @Test fun legacyManifestWithoutContentIdentityStillRestoresAndDetectsSameOriginal() = runBlocking {
        val (book, state) = novel()
        service.upload(state, book)
        val path = "books/${book.id}/manifest.json"
        val manifest = JSONObject(requireNotNull(transport.files[path]).toString(Charsets.UTF_8)).apply { remove("contentIdentity") }
        transport.files[path] = manifest.toString().toByteArray(Charsets.UTF_8)
        val listed = service.list().single()
        assertNull(listed.contentIdentity)
        val local = book.copy(id = "reimported")
        assertEquals(CloudBookMatch.SAME, service.restoreBookMatches(state.copy(books = listOf(local)), listOf(listed))[local.id to book.id])
        assertEquals(book.title, service.download(listed).book.title)
    }

    @Test fun originalFilenameAndBytesSurviveCloudRestoreAndMetadataOnlyIdentityRefresh() = runBlocking {
        val (book, state) = novel()
        val first = service.upload(state, book)
        val uploads = transport.binaryUploads
        val identified = service.list().single().book
        assertEquals("original.mobi", identified.sourceFileName)
        assertEquals(File(book.sourcePath!!).length(), identified.sourceFileBytes)
        val renamed = book.copy(sourceFileName = "更正后的原始名字.mobi", progress = .75f)
        val refreshed = service.upload(state.copy(books = listOf(renamed)), renamed)
        assertEquals(first.revision, refreshed.revision)
        assertEquals(uploads, transport.binaryUploads)
        val restored = service.download(service.list().single()).book
        assertEquals(renamed.sourceFileName, restored.sourceFileName)
        assertEquals(File(book.sourcePath!!).length(), restored.sourceFileBytes)
        assertEquals(.75f, restored.progress)
    }

    @Test fun reimportedSameSourceUpdatesTheExistingCloudIdAndKeepsItsLocalNoteIdentity() = runBlocking {
        val (original, state) = novel()
        val first = service.upload(state, original)
        val reimported = original.copy(id = "reimported-book", progress = .8f)
        val note = Annotation(bookId = reimported.id, chapterIndex = 0, pageIndex = 0, note = "重新导入后的新笔记")
        val localState = state.copy(books = listOf(reimported), annotations = listOf(note))
        val catalog = service.list()
        val matches = service.restoreBookMatches(localState, catalog)
        val targetId = cloudUploadTargetId(reimported, catalog, matches)
        val (snapshot, uploadBook) = cloudUploadSnapshot(localState, reimported, targetId)
        service.upload(snapshot, uploadBook)
        val updated = service.list().single()
        assertEquals(first.book.id, updated.book.id)
        assertEquals(.8f, updated.book.progress)
        assertEquals(first.book.id, updated.annotations.single().bookId)
        assertEquals(note.note, service.download(updated).annotations.single().note)
        assertEquals(reimported.id, localState.annotations.single().bookId)
        assertEquals(reimported.id, localState.books.single().id)
    }

    @Test fun aShelfRenameUpdatesTheCloudTitleWithoutReuploadingOrRenamingTheOriginalFile() = runBlocking {
        val (original, state) = novel()
        val source = File(requireNotNull(original.sourcePath))
        val originalBytes = source.readBytes()
        val first = service.upload(state, original)
        val transfers = transport.binaryUploads
        val renamed = original.copy(title = "自选书名")
        val refreshed = service.upload(state.copy(books = listOf(renamed)), renamed)
        assertEquals(first.revision, refreshed.revision)
        assertEquals(transfers, transport.binaryUploads)
        val listed = service.list().single()
        assertEquals("自选书名", listed.book.title)
        assertEquals(first.book.sourceFileName, listed.book.sourceFileName)
        assertEquals(first.book.sourceFileBytes, listed.book.sourceFileBytes)
        val downloaded = service.download(listed)
        assertEquals("自选书名", downloaded.book.title)
        assertArrayEquals(originalBytes, source.readBytes())
    }

    private fun novel(): Pair<Book, LibraryState> {
        val source = File(root, "original.mobi").apply { writeText("mock original bytes") }
        val text = File(root, "chapter.txt").apply { writeText("第一章\n正文的第一段。\n正文的第二段。") }
        val image = File(root, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val font = File(root, "font.ttf").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        val cover = File(root, "cover.png")
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        try { cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val group = ShelfGroup("小说组", LibraryMode.NOVEL)
        val imagePath = sourcePath(image)
        val fontPath = sourcePath(font)
        val book = Book(title = "云端测试书", author = "测试作者", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            sourcePath = sourcePath(source), coverPath = sourcePath(cover),
            chapters = listOf(Chapter(title = "第一章", textPath = sourcePath(text),
                inlineImages = listOf(InlineImage(5, imagePath)), sourceStyles = listOf(SourceTextStyle(0, 3, bold = true)))),
            characterOffset = 5, progress = .25f, positionImagePath = imagePath, groupId = group.id)
        val annotation = Annotation(bookId = book.id, chapterIndex = 0, pageIndex = 0,
            characterOffset = 5, note = "测试笔记", quote = "正文", imagePath = imagePath)
        val settings = ReaderSettings(fontName = "测试字体", fontPath = fontPath,
            fonts = listOf(ImportedFont("测试字体", fontPath)))
        return book to LibraryState(books = listOf(book), annotations = listOf(annotation), settings = settings,
            groups = listOf(group), readingSeconds = 41)
    }

    @Test fun originalContentStylesAnnotationsAndFontsRestoreAcrossLocalPaths() = runBlocking {
        val (book, state) = novel()
        service.upload(state, book)
        val listed = service.list().single()
        assertTrue(listed.book.cloudOnly)
        assertNotNull(listed.book.coverPath)
        assertTrue(File(listed.book.coverPath!!).exists())
        val restored = service.download(listed)
        assertFalse(restored.book.cloudOnly)
        assertFalse(restored.book.sourceLinked)
        assertNull(restored.book.sourceUri)
        assertEquals(book.progress, restored.book.progress)
        assertEquals(book.characterOffset, restored.book.characterOffset)
        assertEquals("mock original bytes", File(restored.book.sourcePath!!).readText())
        assertEquals(File(book.chapters[0].textPath!!).readText(), File(restored.book.chapters[0].textPath!!).readText())
        assertEquals(book.chapters[0].sourceStyles, restored.book.chapters[0].sourceStyles)
        assertEquals(restored.book.chapters[0].inlineImages[0].path, restored.annotations[0].imagePath)
        assertEquals(restored.annotations[0].imagePath, restored.book.positionImagePath)
        assertEquals("测试笔记", restored.annotations[0].note)
        assertEquals(state.groups[0], restored.group)
        assertEquals(41, restored.readingSeconds)
        assertTrue(restored.settings.fontPath!!.startsWith(File(context.filesDir, "fonts").absolutePath))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), File(restored.settings.fontPath!!).readBytes())
        val manifest = transport.files["books/${book.id}/manifest.json"]!!.toString(Charsets.UTF_8)
        assertFalse(manifest.contains(root.absolutePath))
        assertFalse(manifest.contains("unit-test@example.invalid"))
        assertFalse(manifest.contains("not-a-real-password"))
    }

    @Test fun sourceFootnotesAndTheirOwnImagesSurviveBackupAndUserAnnotationCleanup() = runBlocking {
        val (original, originalState) = novel()
        val text = "第一章\n正文[1]后文。"
        File(requireNotNull(original.chapters.single().textPath)).writeText(text)
        val image = File(root, "footnote-only.png").apply { writeBytes(byteArrayOf(11, 12, 13, 14)) }
        val note = SourceFootnote(text.indexOf("[1]"), text.indexOf("[1]") + 3, "[1]", "源文件内的注释", sourcePath(image))
        val book = original.copy(chapters = listOf(original.chapters.single().copy(sourceFootnotes = listOf(note))))
        val state = originalState.copy(books = listOf(book))
        val uploaded = service.upload(state, book)
        val portable = uploaded.book.chapters.single().sourceFootnotes.single()
        assertEquals(note.content, portable.content)
        assertTrue(requireNotNull(portable.imagePath).startsWith("assets/"))
        assertFalse(portable.imagePath!!.contains(root.absolutePath))
        val cleaned = requireNotNull(service.clearBookRecords(book.id, LibraryCleanupKind.ANNOTATIONS))
        assertTrue(cleaned.annotations.isEmpty())
        assertEquals(listOf(portable), cleaned.book.chapters.single().sourceFootnotes)
        val restored = service.download(uploaded)
        assertTrue(restored.annotations.isEmpty())
        val restoredChapter = restored.book.chapters.single()
        val restoredNote = restoredChapter.sourceFootnotes.single()
        assertEquals(note.copy(imagePath = restoredNote.imagePath), restoredNote)
        assertEquals("[1]", File(requireNotNull(restoredChapter.textPath)).readText().substring(restoredNote.start, restoredNote.end))
        assertArrayEquals(image.readBytes(), File(requireNotNull(restoredNote.imagePath)).readBytes())
        assertEquals(sourcePath(image), book.chapters.single().sourceFootnotes.single().imagePath)
    }

    @Test fun recordChangesReuseUploadedContentAndRestoreLatestSnapshot() = runBlocking {
        val (book, state) = novel()
        val first = service.upload(state, book)
        val uploads = transport.binaryUploads
        val roles = listOf(BookVoiceRole("本书角色", "本书角色|别名", "沉稳男声", "角色描述", "紧张场景"))
        val changed = book.copy(characterOffset = 8, progress = .75f, lastReadAt = 12345, voiceRoles = roles)
        val changedState = state.copy(books = listOf(changed), annotations = state.annotations.map { it.copy(note = "更新后的笔记") }, readingSeconds = 99)
        val second = service.upload(changedState, changed)
        assertEquals(first.revision, second.revision)
        assertEquals(uploads, transport.binaryUploads)
        assertEquals(first.archiveSha256, second.archiveSha256)
        val restored = service.download(second)
        assertEquals(.75f, restored.book.progress)
        assertEquals(8, restored.book.characterOffset)
        assertEquals("更新后的笔记", restored.annotations.single().note)
        assertEquals(roles, restored.book.voiceRoles)
        assertEquals(99, restored.readingSeconds)
    }

    @Test fun clearingRecordsChangesOnlyManifestAndNeverRestoresOldArchivedRecords() = runBlocking {
        val (original, originalState) = novel()
        val roles = listOf(BookVoiceRole("角色", "角色", "温和男声"))
        val book = original.copy(lastReadAt = 12345, voiceRoles = roles)
        val annotations = AnnotationKind.entries.map { kind ->
            originalState.annotations.single().copy(id = "record-$kind", kind = kind,
                note = if (kind == AnnotationKind.NOTE) "待清理笔记" else "")
        }
        val other = book.copy(id = "other-book", title = "不清理的书")
        val otherNotes = annotations.map { it.copy(id = "other-${it.id}", bookId = other.id) }
        val state = originalState.copy(books = listOf(book, other), annotations = annotations + otherNotes)
        val uploaded = service.upload(state, book)
        val otherUploaded = service.upload(state, other)
        val path = "books/${book.id}/manifest.json"
        val initialFiles = transport.files.mapValues { it.value.copyOf() }
        val uploads = transport.binaryUploads
        val catalogPuts = transport.catalogPutAttempts

        for (kind in listOf(LibraryCleanupKind.ANNOTATIONS, LibraryCleanupKind.ROLES, LibraryCleanupKind.HISTORY)) {
            transport.requests.clear()
            val cleaned = requireNotNull(service.clearBookRecords(book.id, kind))
            assertEquals(listOf("VERSION GET $path", "CONDITIONAL PUT $path"), transport.requests)
            assertEquals(uploaded.parts, cleaned.parts)
            assertEquals(uploaded.revision, cleaned.revision)
            assertEquals(uploaded.archiveSha256, cleaned.archiveSha256)
            assertEquals(uploaded.archiveBytes, cleaned.archiveBytes)
            assertEquals(uploaded.contentIdentity, cleaned.contentIdentity)
            assertEquals(uploaded.sourceSha256, cleaned.sourceSha256)
            assertEquals(initialFiles.keys, transport.files.keys)
            initialFiles.filterKeys { it != path }.forEach { (key, bytes) -> assertArrayEquals(key, bytes, transport.files[key]) }
            assertEquals(uploads, transport.binaryUploads)
            assertEquals(catalogPuts, transport.catalogPutAttempts)
            assertTrue(transport.deleteAttempts.isEmpty())

            val listed = service.list().associateBy { it.book.id }
            val current = requireNotNull(listed[book.id])
            assertEquals(listOf(AnnotationKind.BOOKMARK), current.annotations.map { it.kind })
            assertEquals(if (kind == LibraryCleanupKind.ANNOTATIONS) roles else emptyList<BookVoiceRole>(), current.book.voiceRoles)
            assertEquals(if (kind == LibraryCleanupKind.HISTORY) 0L else 12345L, current.book.lastReadAt)
            assertEquals(book.progress, current.book.progress)
            assertEquals(book.characterOffset, current.book.characterOffset)
            val untouched = requireNotNull(listed[other.id])
            assertEquals(otherUploaded.annotations, untouched.annotations)
            assertEquals(roles, untouched.book.voiceRoles)
            assertEquals(other.lastReadAt, untouched.book.lastReadAt)

            // Both the caller's snapshot and the unchanged archive still contain the deleted records.
            val restored = service.download(uploaded)
            assertEquals(current.annotations.map { it.id }, restored.annotations.map { it.id })
            assertEquals(current.book.voiceRoles, restored.book.voiceRoles)
            assertEquals(current.book.lastReadAt, restored.book.lastReadAt)
            assertEquals(book.progress, restored.book.progress)
            assertEquals(book.characterOffset, restored.book.characterOffset)
            assertEquals("mock original bytes", File(requireNotNull(restored.book.sourcePath)).readText())
            assertEquals(File(requireNotNull(book.chapters[0].textPath)).readText(),
                File(requireNotNull(restored.book.chapters[0].textPath)).readText())
        }
    }

    @Test fun recordCleanupRetriesManifestConflictWithoutLosingConcurrentReadingProgress() = runBlocking {
        val (book, state) = novel()
        val uploaded = service.upload(state, book)
        val path = "books/${book.id}/manifest.json"
        val concurrent = JSONObject(transport.files.getValue(path).toString(Charsets.UTF_8)).apply {
            getJSONObject("book").put("progress", .85).put("characterOffset", 9)
        }.toString().toByteArray(Charsets.UTF_8)
        transport.metadataConflict = path to concurrent
        transport.conditionalPutAttempts.clear()
        val cleaned = requireNotNull(service.clearBookRecords(book.id, LibraryCleanupKind.ANNOTATIONS))
        val attempts = transport.conditionalPutAttempts
        assertEquals(listOf(path, path), attempts.map { it.first })
        assertNotNull(attempts[0].second)
        assertNotEquals(attempts[0].second, attempts[1].second)
        assertTrue(cleaned.annotations.isEmpty())
        assertEquals(.85f, cleaned.book.progress)
        assertEquals(9, cleaned.book.characterOffset)
        assertEquals(uploaded.parts, cleaned.parts)
        assertEquals(uploaded.archiveSha256, cleaned.archiveSha256)
        assertEquals(uploaded.revision, cleaned.revision)
        val restored = service.download(uploaded)
        assertTrue(restored.annotations.isEmpty())
        assertEquals(.85f, restored.book.progress)
        assertEquals(9, restored.book.characterOffset)
    }

    @Test fun audioCleanupNeverContactsCloudOrChangesExistingBackup() = runBlocking {
        val (book, state) = novel()
        service.upload(state, book)
        val before = transport.files.mapValues { it.value.copyOf() }
        transport.requests.clear()
        credentials.clear()
        assertNull(service.clearBookRecords(book.id, LibraryCleanupKind.AI_AUDIO))
        assertTrue(transport.requests.isEmpty())
        assertEquals(before.keys, transport.files.keys)
        before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, transport.files[path]) }
    }

    @Test fun editedMobiChapterBacksUpWithOriginalFileImagesStylesAndMigratedAnnotations() = runBlocking {
        val (original, state) = novel()
        val text = File(requireNotNull(original.chapters[0].textPath)).readText()
        val edit = EbookTextEdit(0, text, 4, 11, "修改后的新正文。")
        val editedText = File(root, "edited-chapter.txt").apply { writeText(edit.text) }
        val chapter = edit.chapter(original.chapters[0], sourcePath(editedText))
        val edited = edit.book(original, chapter, listOf(text.length))
        val annotations = state.annotations.map(edit::annotation)
        val first = service.upload(state, original)
        val second = service.upload(state.copy(books = listOf(edited), annotations = annotations), edited)
        assertEquals(first.sourceSha256, second.sourceSha256)
        assertNotEquals(first.contentIdentity, second.contentIdentity)
        assertNotEquals(first.revision, second.revision)
        val restored = service.download(second)
        assertEquals(BookFormat.MOBI, restored.book.format)
        assertEquals("mock original bytes", File(requireNotNull(restored.book.sourcePath)).readText())
        assertEquals(edit.text, File(requireNotNull(restored.book.chapters[0].textPath)).readText())
        assertEquals(original.author, restored.book.author)
        assertEquals(original.chapters[0].title, restored.book.chapters[0].title)
        assertEquals(chapter.sourceStyles, restored.book.chapters[0].sourceStyles)
        assertEquals(chapter.inlineImages.map { it.offset }, restored.book.chapters[0].inlineImages.map { it.offset })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(restored.book.chapters[0].inlineImages.single().path).readBytes())
        assertEquals(annotations.single().characterOffset, restored.annotations.single().characterOffset)
        assertEquals(annotations.single().note, restored.annotations.single().note)
        assertEquals(restored.book.chapters[0].inlineImages.single().path, restored.annotations.single().imagePath)
        assertEquals(edited.characterOffset, restored.book.characterOffset)
        assertEquals(edited.contentVersion, restored.book.contentVersion)
        assertTrue(restored.book.hasTextEdits)
    }

    @Test fun changedContentCreatesNewArchiveAndRetainsPreviousBackup() = runBlocking {
        val (book, state) = novel()
        val first = service.upload(state, book)
        val uploads = transport.binaryUploads
        File(book.chapters[0].textPath!!).appendText("\n新增正文")
        val second = service.upload(state, book)
        assertNotEquals(first.revision, second.revision)
        assertTrue(transport.binaryUploads > uploads)
        assertTrue(transport.files.containsKey("books/${book.id}/${first.parts[0].name}"))
        assertTrue(File(service.download(second).book.chapters[0].textPath!!).readText().contains("新增正文"))
    }

    @Test fun failedNewContentUploadKeepsCommittedManifestAndReadableBackup() = runBlocking {
        val (book, state) = novel()
        val first = service.upload(state, book)
        val committed = transport.files["books/${book.id}/manifest.json"]!!.copyOf()
        File(book.chapters[0].textPath!!).appendText("\n待上传新正文")
        transport.failNextUpload = true
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        assertArrayEquals(committed, transport.files["books/${book.id}/manifest.json"])
        assertFalse(File(service.download(first).book.chapters[0].textPath!!).readText().contains("待上传新正文"))
    }

    @Test fun corruptedCloudPartDoesNotReplaceExistingBookFiles() = runBlocking {
        val (book, state) = novel()
        val info = service.upload(state, book)
        val previous = File(context.filesDir, "books/${book.id}/previous.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        val bookDirectory = previous.parentFile!!
        val before = bookDirectory.walkTopDown().drop(1).associate {
            it.relativeTo(bookDirectory).invariantSeparatorsPath to if (it.isFile) it.readBytes() else null
        }
        val key = "books/${book.id}/${info.parts[0].name}"
        transport.files[key] = transport.files[key]!!.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.download(info) } }
        assertEquals("keep", previous.readText())
        assertEquals("A failed restore must not add or remove any existing book entry", before.keys,
            bookDirectory.walkTopDown().drop(1).map { it.relativeTo(bookDirectory).invariantSeparatorsPath }.toSet())
        before.forEach { (path, bytes) ->
            val file = File(bookDirectory, path)
            if (bytes == null) assertTrue("The existing directory must be preserved: $path", file.isDirectory)
            else assertArrayEquals("The existing book file must remain unchanged: $path", bytes, file.readBytes())
        }
        assertFalse(context.cacheDir.listFiles()!!.any { it.name.startsWith("cloud-download-") })
    }

    @Test fun catalogConflictReloadsAndPreservesOtherDeviceIds() = runBlocking {
        val (book, state) = novel()
        transport.catalogConflictId = "other-device-book"
        service.upload(state, book)
        val catalog = JSONObject(transport.files["catalog.json"]!!.toString(Charsets.UTF_8)).getJSONArray("bookIds")
        assertEquals(setOf(book.id, "other-device-book"), (0 until catalog.length()).map { catalog.getString(it) }.toSet())
        assertEquals(2, transport.catalogPutAttempts)
    }

    @Test fun folderComicBacksUpIndexedImagesAndPdfsWithoutReadingRootDirectory() = runBlocking {
        val image = File(root, "page.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pdf = File(root, "chapter.pdf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val book = Book(title = "漫画", format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            sourceUri = "content://unavailable.provider/tree/folder", sourceLinked = true,
            chapters = listOf(Chapter(title = "第1章", pages = listOf(PageSource(sourcePath(image)), PageSource(sourcePath(pdf), 0), PageSource(sourcePath(pdf), 1)),
                subsections = listOf(ComicSection("小节", 1, 3)))))
        val info = service.upload(LibraryState(books = listOf(book)), book)
        val restored = service.download(info).book
        assertEquals(listOf(-1, 0, 1), restored.chapters[0].pages.map { it.pdfPage })
        assertEquals(restored.chapters[0].pages[1].path, restored.chapters[0].pages[2].path)
        assertEquals(book.chapters[0].subsections, restored.chapters[0].subsections)
        assertArrayEquals(byteArrayOf(4, 5, 6), File(restored.chapters[0].pages[1].path).readBytes())
    }

    private fun largeSource(book: Book): File = File(book.sourcePath!!).apply {
        val random = java.util.Random(54321)
        val buffer = ByteArray(64 * 1024)
        outputStream().use { output -> repeat(160) { random.nextBytes(buffer); output.write(buffer) } }
    }

    @Test fun disconnectedTenMiBUploadResumesConfirmedBlocksInNewService() = runBlocking {
        val (book, state) = novel()
        val source = largeSource(book)
        transport.failUploadAt = 2
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        val first = transport.uploadAttempts[0]
        val failed = transport.uploadAttempts[1]
        assertTrue(first.endsWith("-0000.bin"))
        assertTrue(failed.endsWith("-0001.bin"))
        assertTrue(File(context.filesDir, "cloud-pending/${book.id}").walkTopDown().any { it.name == "checkpoint.json" })
        service = CloudBackupService(context, credentials, { _, _ -> transport })
        val info = service.upload(state, book)
        assertEquals(failed, transport.uploadAttempts[2])
        assertEquals(1, transport.uploadAttempts.count { it == first })
        assertTrue(first.contains(info.revision))
        assertTrue(info.parts.size >= 3)
        assertTrue(info.parts.all { it.bytes <= 1024L * 1024 })
        assertEquals(CloudArchive.hash(source), CloudArchive.hash(File(service.download(info).book.sourcePath!!)))
        assertFalse(File(context.filesDir, "cloud-pending/${book.id}").walkTopDown().any { it.name == "book.zip" })
    }

    @Test fun versionOnePendingArchiveResumesWithItsOriginalFourMiBBoundaries() = runBlocking {
        val (book, state) = novel()
        val source = largeSource(book)
        transport.failUploadAt = 2
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        val checkpoint = File(context.filesDir, "cloud-pending/${book.id}").walkTopDown().single { it.name == "checkpoint.json" }
        val json = JSONObject(checkpoint.readText())
        val revision = json.getString("revision")
        val archive = File(checkpoint.parentFile, "book.zip")
        // Recreate a 1.6.2 checkpoint and its already confirmed 4 MiB server block.
        json.put("version", 1).remove("partBytes")
        json.put("completedParts", 1)
        checkpoint.writeText(json.toString())
        val firstBlock = ByteArray(4 * 1024 * 1024)
        archive.inputStream().use { it.read(firstBlock) }
        transport.files["books/${book.id}/$revision-0000.bin"] = firstBlock
        transport.uploadAttempts.clear()
        service = CloudBackupService(context, credentials, { _, _ -> transport })
        val info = service.upload(state, book)
        assertEquals(revision, info.revision)
        assertEquals(4L * 1024 * 1024, info.parts.first().bytes)
        assertEquals("books/${book.id}/$revision-0001.bin", transport.uploadAttempts.first())
        assertFalse(transport.uploadAttempts.any { it.endsWith("-0000.bin") })
        assertEquals(CloudArchive.hash(source), CloudArchive.hash(File(service.download(info).book.sourcePath!!)))
    }

    @Test fun cancellingBlockedUploadInterruptsTransportAndKeepsConfirmedCheckpoint() = runBlocking {
        for (automaticPause in listOf(false, true)) {
            transport = FakeCloudTransport()
            service = CloudBackupService(context, credentials, { _, _ -> transport })
            val (book, state) = novel()
            largeSource(book)
            transport.holdUploadAt = 2
            val allowed = AtomicBoolean(true)
            val operation = launch(Dispatchers.Default) {
                service.upload(state, book) { if (!allowed.get()) throw CancellationException("book paused") }
            }
            assertTrue("upload should reach the held second block", transport.heldStarted.await(5, TimeUnit.SECONDS))
            if (automaticPause) allowed.set(false) else operation.cancel()
            withTimeout(5_000) { operation.join() }
            assertTrue(operation.isCancelled)
            assertEquals(2, transport.uploadAttempts.size)
            val checkpoint = File(context.filesDir, "cloud-pending/${book.id}").walkTopDown().single { it.name == "checkpoint.json" }
            assertEquals(1, JSONObject(checkpoint.readText()).getInt("completedParts"))
            assertTrue(File(checkpoint.parentFile, "book.zip").isFile)
            assertFalse(transport.files.containsKey("books/${book.id}/manifest.json"))
        }
    }

    @Test fun catalogFailureRetriesRecordsWithoutUploadingBookAgain() = runBlocking {
        val (book, state) = novel()
        transport.failCatalogPut = true
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        val uploads = transport.binaryUploads
        val committed = JSONObject(transport.files["books/${book.id}/manifest.json"]!!.toString(Charsets.UTF_8))
        assertTrue(File(context.filesDir, "cloud-pending/${book.id}").walkTopDown().any { it.name == "book.zip" })
        service = CloudBackupService(context, credentials, { _, _ -> transport })
        val info = service.upload(state, book)
        assertEquals(committed.getString("revision"), info.revision)
        assertEquals(uploads, transport.binaryUploads)
        assertTrue(service.list().any { it.book.id == book.id })
        assertFalse(File(context.filesDir, "cloud-pending/${book.id}").walkTopDown().any { it.name == "checkpoint.json" })
    }

    @Test fun registeredBookUpdatesRecordsWithoutRequiringCatalogEtag() = runBlocking {
        val (book, state) = novel()
        val original = service.upload(state, book)
        val uploads = transport.binaryUploads
        val queries = transport.versionedQueries
        val puts = transport.catalogPutAttempts
        transport.rejectVersionedMetadata = true // Simulate an existing weak or absent ETag.
        val info = service.upload(state.copy(readingSeconds = 66), book.copy(progress = .5f))
        assertEquals(original.revision, info.revision)
        assertEquals(uploads, transport.binaryUploads)
        assertEquals(queries, transport.versionedQueries)
        assertEquals(puts, transport.catalogPutAttempts)
        assertEquals(66, service.download(info).readingSeconds)
    }

    @Test fun unusableNewCatalogVersionFailsBeforeUploadingAnyContent() = runBlocking {
        val (book, state) = novel()
        transport.files["catalog.json"] = JSONObject().put("version", 1).put("bookIds", JSONArray(listOf("different-book")))
            .toString().toByteArray()
        transport.rejectVersionedMetadata = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.upload(state, book) } }
        assertEquals(0, transport.binaryUploads)
    }

    @Test fun changedSourceInvalidatesPendingArchiveAndBookDeletionDiscardsIt() = runBlocking {
        val (book, state) = novel()
        val source = largeSource(book)
        transport.failUploadAt = 2
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        val first = transport.uploadAttempts.first()
        RandomAccessFile(source, "rw").use { val firstByte = it.read(); it.seek(0); it.writeByte(firstByte xor 1) }
        val info = service.upload(state, book)
        assertFalse(first.contains(info.revision))
        assertTrue(transport.uploadAttempts[2].endsWith("${info.revision}-0000.bin"))
        assertEquals(CloudArchive.hash(source), CloudArchive.hash(File(service.download(info).book.sourcePath!!)))
        source.appendText("changed again")
        transport.failNextUpload = true
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        service.discardPendingUpload(book.id)
        assertFalse(File(context.filesDir, "cloud-pending/${book.id}").exists())
    }

    @Test fun legacyManifestStillAcceptsOneHundredTwentyEightMiBBlocks() = runBlocking {
        val (book, state) = novel()
        val info = service.upload(state, book)
        val path = "books/${book.id}/manifest.json"
        val json = JSONObject(transport.files[path]!!.toString(Charsets.UTF_8))
        json.put("archiveBytes", CLOUD_LEGACY_PART_BYTES).put("parts", JSONArray(listOf(JSONObject()
            .put("name", info.parts[0].name).put("bytes", CLOUD_LEGACY_PART_BYTES))))
        transport.files[path] = json.toString().toByteArray()
        val legacy = service.list().single()
        assertEquals(CLOUD_LEGACY_PART_BYTES, legacy.parts.single().bytes)
        assertEquals(CLOUD_LEGACY_PART_BYTES, legacy.archiveBytes)
    }

    @Test fun deletingCloudBookRemovesAllRevisionsAndOnlyItsPrivateCaches() = runBlocking {
        val (book, original) = novel()
        val other = book.copy(id = "${book.id}-other", title = "另一册")
        val state = original.copy(books = listOf(book, other))
        val repository = LibraryRepository(context)
        repository.save(state)
        val records = File(context.filesDir, "library.json").readBytes()
        val info = service.upload(state, book)
        service.upload(state, other)
        val local = service.download(info).book
        val source = File(local.sourcePath!!).readBytes()
        val text = File(local.chapters[0].textPath!!).readBytes()
        val covers = service.list().associateBy { it.book.id }
        val ownCover = File(covers.getValue(book.id).book.coverPath!!)
        val otherCover = File(covers.getValue(other.id).book.coverPath!!)
        val partialCover = File(ownCover.parentFile, "${book.id}-${UUID.randomUUID()}.tmp").apply { writeText("partial") }
        val ownPending = pendingFile(book.id).apply { parentFile!!.mkdirs(); writeText("pending") }
        val otherPending = pendingFile(other.id).apply { parentFile!!.mkdirs(); writeText("keep") }
        transport.files["books/${book.id}/old-revision.bin"] = byteArrayOf(1)
        transport.files["books/${book.id}/nested/unfinished.bin"] = byteArrayOf(2)
        transport.directories += "books/${book.id}/nested"
        service.deleteBook(book.id)
        assertFalse(transport.files.keys.any { it.startsWith("books/${book.id}/") })
        assertFalse(transport.directories.any { it == "books/${book.id}" || it.startsWith("books/${book.id}/") })
        assertEquals(setOf(other.id), catalogIds())
        assertEquals(listOf(other.id), service.list().map { it.book.id })
        assertFalse(ownPending.exists())
        assertFalse(ownCover.exists())
        assertFalse(partialCover.exists())
        assertTrue(otherPending.exists())
        assertTrue(otherCover.exists())
        assertArrayEquals(source, File(local.sourcePath!!).readBytes())
        assertArrayEquals(text, File(local.chapters[0].textPath!!).readBytes())
        assertArrayEquals(records, File(context.filesDir, "library.json").readBytes())
        assertEquals(state.annotations, repository.load().annotations)
        assertEquals("mock original bytes", File(book.sourcePath!!).readText())
    }

    @Test fun deletingCloudBookReloadsConflictingCatalogAndKeepsOtherDeviceIds() = runBlocking {
        val (book, state) = novel()
        val other = book.copy(id = "other-local-book")
        service.upload(state, book)
        service.upload(state, other)
        val attempts = transport.catalogPutAttempts
        transport.catalogConflictId = "concurrent-device-book"
        service.deleteBook(book.id)
        assertEquals(setOf(other.id, "concurrent-device-book"), catalogIds())
        assertEquals(attempts + 2, transport.catalogPutAttempts)
        assertEquals(listOf("books/${book.id}/"), transport.deleteAttempts)
        assertTrue(transport.files.containsKey("books/${other.id}/manifest.json"))
    }

    @Test fun cloudDeleteWithFailedCatalogCommitCanRetryMissingDirectory() = runBlocking {
        val (book, state) = novel()
        service.upload(state, book)
        val cover = File(service.list().single().book.coverPath!!)
        val pending = pendingFile(book.id).apply { parentFile!!.mkdirs(); writeText("pending") }
        transport.failCatalogPut = true
        assertThrows(IOException::class.java) { runBlocking { service.deleteBook(book.id) } }
        assertEquals(setOf(book.id), catalogIds())
        assertFalse(transport.files.keys.any { it.startsWith("books/${book.id}/") })
        assertTrue(service.list().isEmpty())
        assertTrue(cover.exists())
        assertTrue(pending.exists())
        service = CloudBackupService(context, credentials, { _, _ -> transport })
        service.deleteBook(book.id)
        service.deleteBook(book.id)
        assertTrue(catalogIds().isEmpty())
        assertFalse(cover.exists())
        assertFalse(pending.exists())
        assertEquals(List(3) { "books/${book.id}/" }, transport.deleteAttempts)
    }

    @Test fun cloudDeletionPreflightAndDeleteErrorsPreserveBackupAndReportFailure() = runBlocking {
        val (book, state) = novel()
        service.upload(state, book)
        val committed = transport.files["books/${book.id}/manifest.json"]!!.copyOf()
        val attempts = transport.catalogPutAttempts
        transport.rejectVersionedMetadata = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.deleteBook(book.id) } }
        assertTrue(transport.deleteAttempts.isEmpty())
        transport.rejectVersionedMetadata = false
        transport.failDelete = true
        assertThrows(IOException::class.java) { runBlocking { service.deleteBook(book.id) } }
        assertArrayEquals(committed, transport.files["books/${book.id}/manifest.json"])
        assertEquals(setOf(book.id), catalogIds())
        assertEquals(attempts, transport.catalogPutAttempts)
    }

    @Test fun cloudDeletionRejectsIllegalIdsBeforeAnyNetworkRequest() = runBlocking {
        val queries = transport.versionedQueries
        for (id in listOf("", "../book", "book/child", "book.name", "book\\child", "https://example.invalid", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.deleteBook(id) } }
        }
        assertEquals(queries, transport.versionedQueries)
        assertTrue(transport.deleteAttempts.isEmpty())
    }

    @Test fun sameAccountOnAnotherServerDoesNotResumeOrDeleteTheFirstServersParts() = runBlocking {
        val (book, state) = novel()
        largeSource(book)
        val firstServer = transport
        val secondServer = FakeCloudTransport()
        val secondUrl = "https://another.example.invalid/dav/"
        service = CloudBackupService(context, credentials, { value, _ ->
            if (value.serverUrl == DEFAULT_WEBDAV_SERVER) firstServer else secondServer
        })
        firstServer.failUploadAt = 2
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        val firstPending = pendingFile(book.id)
        val firstRevision = JSONObject(File(firstPending.parentFile, "checkpoint.json").readText()).getString("revision")
        val account = service.account()!!
        service.saveCredentials(account, "not-a-real-password", secondUrl)
        assertEquals(account, service.account())
        assertNotEquals(account, service.identity())
        secondServer.failUploadAt = 2
        assertThrows(IOException::class.java) { runBlocking { service.upload(state, book) } }
        val secondPending = pendingFile(book.id)
        val secondRevision = JSONObject(File(secondPending.parentFile, "checkpoint.json").readText()).getString("revision")
        assertNotEquals(firstPending, secondPending)
        assertNotEquals(firstRevision, secondRevision)
        assertTrue(secondServer.uploadAttempts.first().endsWith("$secondRevision-0000.bin"))
        service.discardPendingUpload(book.id)
        assertFalse(secondPending.exists())
        assertTrue(firstPending.exists())
        service.saveCredentials(account, "not-a-real-password")
        val completed = service.upload(state, book)
        assertEquals(firstRevision, completed.revision)
        assertTrue(firstServer.uploadAttempts[2].endsWith("$firstRevision-0001.bin"))
        assertEquals(1, firstServer.uploadAttempts.count { it.endsWith("$firstRevision-0000.bin") })
    }

    @Test fun sameCloudBookRevisionUsesSeparateCoverCachesForDifferentServers() = runBlocking {
        val (book, state) = novel()
        val firstServer = transport
        val secondServer = FakeCloudTransport()
        service.upload(state, book)
        val firstCover = File(service.list().single().book.coverPath!!)
        // Identical remote ids/revisions must still never reuse another server's cached bytes.
        secondServer.files.putAll(firstServer.files.mapValues { it.value.copyOf() })
        val manifest = JSONObject(secondServer.files.getValue("books/${book.id}/manifest.json").toString(Charsets.UTF_8))
        val coverPath = "books/${book.id}/${manifest.getString("cloudCover")}"
        secondServer.files[coverPath] = byteArrayOf(9, 8, 7)
        service.saveCredentials(service.account()!!, "not-a-real-password", "https://another.example.invalid/dav/")
        service = CloudBackupService(context, credentials, { _, _ -> secondServer })
        val secondCover = File(service.list().single().book.coverPath!!)
        assertNotEquals(firstCover.parentFile, secondCover.parentFile)
        assertArrayEquals(byteArrayOf(9, 8, 7), secondCover.readBytes())
        assertFalse(firstCover.readBytes().contentEquals(secondCover.readBytes()))
        service.deleteBook(book.id)
        assertFalse(secondCover.exists())
        assertTrue(firstCover.exists())
    }

    private fun pendingFile(bookId: String): File {
        val scope = MessageDigest.getInstance("SHA-256").digest(service.identity()!!.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "cloud-pending/$bookId/$scope/book.zip")
    }

    private fun catalogIds(): Set<String> {
        val array = JSONObject(transport.files.getValue("catalog.json").toString(Charsets.UTF_8)).getJSONArray("bookIds")
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private class FakeCloudTransport : CloudTransport {
        val files = LinkedHashMap<String, ByteArray>()
        val directories = mutableSetOf<String>()
        var binaryUploads = 0
        var failNextUpload = false
        var failUploadAt: Int? = null
        var failCatalogPut = false
        var rejectVersionedMetadata = false
        var versionedQueries = 0
        val uploadAttempts = mutableListOf<String>()
        val deleteAttempts = mutableListOf<String>()
        var failDelete = false
        var holdUploadAt: Int? = null
        val heldStarted = CountDownLatch(1)
        private val heldCancelled = CountDownLatch(1)
        var catalogConflictId: String? = null
        var catalogPutAttempts = 0
        var metadataConflict: Pair<String, ByteArray>? = null
        val conditionalPutAttempts = mutableListOf<Pair<String, String?>>()
        val requests = mutableListOf<String>()

        override fun ensureCollection(path: String) { requests += "MKCOL $path"; directories += path.trimEnd('/') }
        override fun collections(path: String): List<String> {
            requests += "PROPFIND $path"
            return directories.mapNotNull {
                it.removePrefix(path).takeIf { child -> child.isNotBlank() && '/' !in child && it.startsWith(path) }
            }
        }
        override fun metadata(path: String, maximum: Int): ByteArray? {
            requests += "GET $path"
            return files[path]?.also { require(it.size <= maximum) }?.copyOf()
        }
        override fun putMetadata(path: String, bytes: ByteArray) { requests += "PUT $path"; files[path] = bytes.copyOf() }
        override fun cancel() { heldCancelled.countDown() }
        override fun deleteCollection(path: String) {
            requests += "DELETE $path"
            deleteAttempts += path
            if (failDelete) { failDelete = false; throw IOException("mock cloud delete failed") }
            files.keys.removeAll { it.startsWith(path) }
            directories.removeAll { it == path.trimEnd('/') || it.startsWith(path) }
        }
        private fun etag(path: String): String? = files[path]?.let { bytes ->
            "\"" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "\""
        }
        override fun versionedMetadata(path: String): CloudVersionedMetadata? {
            requests += "VERSION GET $path"
            versionedQueries++
            require(!rejectVersionedMetadata) { "mock unavailable strong version" }
            return files[path]?.let { CloudVersionedMetadata(it.copyOf(), requireNotNull(etag(path))) }
        }
        override fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?): Boolean {
            requests += "CONDITIONAL PUT $path"
            conditionalPutAttempts += path to etag
            if (path == "catalog.json") {
                catalogPutAttempts++
                if (failCatalogPut) { failCatalogPut = false; throw IOException("mock catalog disconnect") }
                catalogConflictId?.let { id ->
                    catalogConflictId = null
                    val existing = files[path]?.let { JSONObject(it.toString(Charsets.UTF_8)).getJSONArray("bookIds") }
                    val ids = if (existing == null) emptyList() else (0 until existing.length()).map { existing.getString(it) }
                    files[path] = JSONObject().put("version", 1).put("bookIds", JSONArray((ids + id).distinct())).toString().toByteArray()
                    return false
                }
            }
            metadataConflict?.takeIf { it.first == path }?.let { conflict ->
                metadataConflict = null
                files[path] = conflict.second.copyOf()
                return false
            }
            if (etag != this.etag(path)) return false
            files[path] = bytes.copyOf()
            return true
        }
        override fun upload(path: String, file: File, offset: Long, bytes: Long, progress: (Long) -> Unit) {
            requests += "UPLOAD $path"
            uploadAttempts += path
            if (uploadAttempts.size == failUploadAt) {
                failUploadAt = null
                progress(bytes / 2)
                throw IOException("mock disconnected second block")
            }
            if (failNextUpload) { failNextUpload = false; throw IOException("mock failed upload") }
            if (uploadAttempts.size == holdUploadAt) {
                heldStarted.countDown()
                check(heldCancelled.await(5, TimeUnit.SECONDS)) { "held upload was not cancelled" }
                throw IOException("mock cancelled blocked upload")
            }
            require(bytes <= CLOUD_LEGACY_PART_BYTES)
            val content = ByteArray(bytes.toInt())
            RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(content) }
            files[path] = content
            binaryUploads++
            progress(bytes)
        }
        override fun download(path: String, file: File, append: Boolean, maximum: Long, progress: (Long) -> Unit): Long {
            requests += "DOWNLOAD $path"
            val content = files[path] ?: throw IOException("mock missing file")
            require(content.size <= maximum)
            java.io.FileOutputStream(file, append).use { it.write(content) }
            progress(content.size.toLong())
            return content.size.toLong()
        }
    }
}
