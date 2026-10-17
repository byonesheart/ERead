package com.eyue.reader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.eyue.reader.data.*
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.cloud.CloudBackupService
import com.eyue.reader.ui.prepaginateBook
import com.eyue.reader.ui.readerLayoutSpec
import com.eyue.reader.ui.removeReaderPageCaches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import java.io.File

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val importer = DocumentImporter(application)
    private val aiSpeechStore = AiSpeechConfigStore(application)
    private val aiSpeechSubmissionJobs = mutableMapOf<String, Job>()
    private val aiSpeechCancellationJobs = mutableMapOf<String, Job>()
    private val aiSpeechTaskPreferences = application.getSharedPreferences("ai_speech_task", 0)
    private val aiSpeechObservedWorks = aiSpeechTaskPreferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith("book:")) null else runCatching {
            key.removePrefix("book:") to java.util.UUID.fromString(value as String)
        }.getOrNull()
    }.toMap().toMutableMap()
    var aiSpeechCacheRevision by mutableStateOf(0)
        private set
    var savingBookVoiceRoles by mutableStateOf(false)
        private set
    var cleaningLibrary by mutableStateOf(false)
        private set
    private var systemDark = (application.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private var saveJob: Job? = null
    private var cloudJob: Job? = null
    private val cloudUploadJobs = mutableMapOf<String, Job>()
    private val cloudBookJobs = mutableMapOf<String, Job>()
    private var observedCloudUploads by mutableStateOf<Map<String, CloudTransferProgress>>(emptyMap())
    private var pendingCloudUploads by mutableStateOf<Map<String, CloudTransferProgress>>(emptyMap())
    private var cloudBookOperations by mutableStateOf<Map<String, CloudTransferProgress>>(emptyMap())
    private var cancellingCloudUploads by mutableStateOf<Set<String>>(emptySet())
    private val cloudUploadQueueMutex = Mutex()
    private var cloudGeneration = 0
    private val cloudHistory = CloudBackupHistory(application)
    private var openJob: Job? = null
    private var sessionStarted = 0L
    private var foreground = false
    private val saveMutex = Mutex()
    private val externalFiles = Channel<Triple<List<Uri>, String?, String?>>(Channel.UNLIMITED)
    private val externalRequestIds = mutableSetOf<String>()
    private var storageReady = false
    private data class ReaderViewport(val size: IntSize, val density: Density, val direction: LayoutDirection)
    private var readerViewport: ReaderViewport? = null
    private var preparingLayout = false
    var state by mutableStateOf(LibraryState())
        private set
    var loading by mutableStateOf(true)
        private set
    var importing by mutableStateOf(false)
        private set
    var rebuildingContents by mutableStateOf(false)
        private set
    var importProgress by mutableStateOf<ImportProgress?>(null)
        private set
    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var activeBookId by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf(LibraryMode.NOVEL)
    var cloudAccount by mutableStateOf("")
        private set
    var cloudServerUrl by mutableStateOf(com.eyue.reader.data.cloud.DEFAULT_WEBDAV_SERVER)
        private set
    private var cloudIdentity: String? = null
    var aiSpeechConfig by mutableStateOf<AiSpeechConfig?>(null)
        private set
    var aiSpeechProfiles by mutableStateOf<List<AiSpeechConfig>>(emptyList())
        private set
    var aiSpeechGenerations by mutableStateOf<Map<String, AiSpeechBatchProgress>>(emptyMap())
        private set
    val aiSpeechGeneration: AiSpeechBatchProgress? get() = aiSpeechGenerations[activeBookId]
        ?: aiSpeechGenerations.values.firstOrNull { it.running } ?: aiSpeechGenerations.values.lastOrNull()
    var pendingCloudRestore by mutableStateOf<List<com.eyue.reader.data.cloud.CloudBookInfo>?>(null)
        private set
    private var pendingRestoreMatches: Map<Pair<String, String>, CloudBookMatch> = emptyMap()
    val cloudRestoreConflicts: List<CloudRestoreConflict> get() = cloudRestoreConflicts(state, pendingCloudRestore.orEmpty(), pendingRestoreMatches)
    private var pendingRestoreIdentity: String? = null
    val meteredNetwork: Boolean get() = (getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as android.net.ConnectivityManager).isActiveNetworkMetered
    var cloudConfigured by mutableStateOf(false)
        private set
    private var cloudOperationBusy by mutableStateOf(false)
    val cloudBusy: Boolean get() = cloudOperationBusy || cleaningLibrary
    val cloudTransfers: Map<String, CloudTransferProgress> get() = observedCloudUploads + pendingCloudUploads + cloudBookOperations +
        cancellingCloudUploads.associateWith { CloudTransferProgress("正在取消上传") }
    val cloudUploadCancellable: Boolean get() = cloudTransfers.values.any { it.cancellable }
    var cloudBookId by mutableStateOf<String?>(null)
        private set
    var cloudTaskLabel by mutableStateOf("")
        private set
    var cloudFraction by mutableStateOf<Float?>(null)
        private set
    val readerSettings: ReaderSettings get() = profileSettings(mode)

    private fun profileSettings(selectedMode: LibraryMode): ReaderSettings =
        if (selectedMode == LibraryMode.COMIC) state.comicSettings.copy(nightMode = state.settings.nightMode) else state.settings

    init {
        viewModelScope.launch {
            while (loading) delay(100)
            if (!storageReady) return@launch
            val finished = mutableSetOf<java.util.UUID>()
            AiSpeechAutomation.observe(getApplication()).collect { works ->
                aiSpeechTaskPreferences.getString("work", null)?.let { legacy ->
                    works.firstOrNull { it.id.toString() == legacy }?.let { info ->
                        AiSpeechAutomation.bookId(info)?.let { rememberAiSpeechWork(it, info.id) }
                    }
                    aiSpeechTaskPreferences.edit().remove("work").apply()
                }
                val updates = mutableMapOf<String, AiSpeechBatchProgress>()
                works.groupBy { AiSpeechAutomation.bookId(it) }.forEach { (bookId, bookWorks) ->
                    if (bookId == null) return@forEach
                    val active = bookWorks.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        ?: bookWorks.firstOrNull { !it.state.isFinished }
                    if (active == null && aiSpeechSubmissionJobs[bookId]?.isActive == true) return@forEach
                    val observed = active ?: bookWorks.firstOrNull { it.id == aiSpeechObservedWorks[bookId] }
                    if (active != null && aiSpeechObservedWorks[bookId] != active.id) rememberAiSpeechWork(bookId, active.id)
                    if (observed != null) {
                        val raw = AiSpeechAutomation.progress(getApplication(), observed) ?: return@forEach
                        val previous = aiSpeechGenerations[bookId]
                        val updated = if (raw.cancelled && raw.total == 0 && previous != null)
                            raw.copy(completed = previous.completed, total = previous.total, chapterIndex = previous.chapterIndex,
                                chapterTitle = previous.chapterTitle, chapterCompleted = previous.chapterCompleted, chapterTotal = previous.chapterTotal,
                                selectedChapters = previous.selectedChapters, completedChapters = previous.completedChapters,
                                chapterProgress = previous.chapterProgress) else raw
                        if (updated.completed != previous?.completed || updated.running != previous?.running) aiSpeechCacheRevision++
                        updates[bookId] = updated
                    }
                }
                aiSpeechGenerations = aiSpeechGenerations + updates
                works.filter { it.state.isFinished && finished.add(it.id) }.forEach {
                    aiSpeechCacheRevision++
                    if (it.id == aiSpeechObservedWorks[AiSpeechAutomation.bookId(it)] && it.state == WorkInfo.State.FAILED) {
                        message = it.outputData.getString(AiSpeechAutomation.KEY_ERROR) ?: "AI 语音转化失败，请重试"
                    }
                }
            }
        }
        viewModelScope.launch { importer.progress.collect { if (!preparingLayout) updateImportProgress(it) } }
        viewModelScope.launch {
            val loaded = runCatching {
                val original = withContext(Dispatchers.IO) { repository.load() }
                val upgraded = importer.upgradeInlineBooks(original)
                if (upgraded != original) withContext(Dispatchers.IO) {
                    repository.save(upgraded)
                    val retained = upgraded.books.flatMap { book -> listOfNotNull(book.sourcePath, book.coverPath) +
                        book.chapters.flatMap { chapter -> listOfNotNull(chapter.textPath) + chapter.inlineImages.map { it.path } +
                            chapter.pages.flatMap { listOfNotNull(it.path, it.textPath) }
                        }
                    }.filterNot { it.startsWith("content:") }.map { File(it).canonicalPath }.toSet()
                    val owned = File(application.filesDir, "books").canonicalFile.toPath()
                    original.books.flatMap { it.chapters }.mapNotNull { it.textPath }.distinct().forEach { path ->
                        val file = File(path).canonicalFile
                        if (file.path !in retained && file.toPath().startsWith(owned)) file.delete()
                    }
                }
                upgraded
            }
            if (loaded.isFailure) {
                message = loaded.exceptionOrNull()?.message ?: "无法读取本地书架"
                loading = false
                return@launch
            }
            state = loaded.getOrThrow().withResolvedTheme(systemDark)
            message = (importer.lastImportErrors + importer.lastImportWarnings).takeIf { it.isNotEmpty() }?.joinToString("\n")
            storageReady = true
            val prefs = application.getSharedPreferences("onboarding", 0)
            if (!prefs.getBoolean("demo_seeded", false)) {
                if (state.books.isEmpty()) {
                    state = state.copy(books = repository.createDemoBooks())
                    persistNow()
                }
                prefs.edit().putBoolean("demo_seeded", true).apply()
            }
            loading = false
            refreshCloudConfiguration()
            runCatching { refreshAiSpeechConfiguration() }
                .onFailure { message = "AI 语音配置无法读取，请重新保存" }
        }
        viewModelScope.launch {
            while (loading) delay(100)
            if (!storageReady) return@launch
            val completed = mutableSetOf<java.util.UUID>()
            var first = true
            CloudBackupAutomation.uploads(getApplication()).collect { uploads ->
                observedCloudUploads = uploads.filterNot { it.state.isFinished }.mapNotNull { work ->
                    CloudBackupAutomation.bookId(work)?.let { id -> id to CloudTransferProgress(
                        work.progress.getString(CloudBackupAutomation.KEY_STAGE)
                            ?: if (work.runAttemptCount > 0) "网络中断，等待重试" else "等待联网上传",
                        work.progress.getFloat(CloudBackupAutomation.KEY_FRACTION, -1f).takeIf { it >= 0 }, true) }
                }.toMap()
                uploads.filter { it.state.isFinished && completed.add(it.id) }.forEach { upload ->
                    if (!first) {
                        val title = state.books.find { it.id == CloudBackupAutomation.bookId(upload) }?.title
                        if (title != null) message = when (upload.state) {
                            WorkInfo.State.SUCCEEDED -> "《$title》的内容和阅读记录已上传"
                            WorkInfo.State.FAILED -> upload.outputData.getString(CloudBackupAutomation.KEY_ERROR) ?: "云端上传未完成，请稍后重试"
                            else -> "《$title》的上传已取消"
                        }
                    }
                }
                first = false
                val account = cloudIdentity.takeIf { cloudConfigured } ?: return@collect
                val merged = cloudBackupHistoryState(state) { cloudHistory.backupAt(account, it) }
                if (merged != state) { state = merged; persistNow() }
            }
        }
        viewModelScope.launch {
            for ((uris, mimeType, requestId) in externalFiles) {
                while (loading || importing) delay(100)
                if (!storageReady) {
                    message = "书架未能加载，无法打开文件"
                    continue
                }
                importing = true
                try {
                    val completedBookId = if (requestId == null) null else withContext(Dispatchers.IO) {
                        val prefs = application.getSharedPreferences("external_open", 0)
                        if (prefs.getString("request_id", null) == requestId) prefs.getString("book_id", null) else null
                    }
                    val existing = state.books.find { it.id == completedBookId }
                    if (existing != null) {
                        open(existing)
                        continue
                    }
                    val extension = uris.first().lastPathSegment.orEmpty().substringAfterLast('.').lowercase()
                    val selectedMode = when {
                        mimeType?.startsWith("image/") == true -> LibraryMode.COMIC
                        extension in setOf("mobi", "epub", "txt") || mimeType in setOf("application/x-mobipocket-ebook", "application/epub+zip", "text/plain") -> LibraryMode.NOVEL
                        else -> mode
                    }
                    beginImport(uris, selectedMode)
                    val books = importer.importFiles(uris, selectedMode, state.settings.chapterRule, mimeType).map {
                        if (it.format == BookFormat.IMAGES) it.copy(mode = LibraryMode.COMIC) else it
                    }
                    pendingImport = pendingImport?.copy(mode = books.first().mode, bookIds = books.map { it.id }.toSet())
                    if (mode == selectedMode) mode = books.first().mode
                    changed(state.copy(books = state.books + books))
                    persistNow()
                    val layoutNotices = prepareImportedBooks(books)
                    if (requestId != null) withContext(Dispatchers.IO) {
                        application.getSharedPreferences("external_open", 0).edit()
                            .putString("request_id", requestId).putString("book_id", books.first().id).commit()
                    }
                    open(books.first())
                    message = (importer.lastImportErrors + importer.lastImportWarnings + layoutNotices).takeIf { it.isNotEmpty() }?.joinToString("\n")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message = error.message ?: "文件打开失败，请确认文件可读取"
                } finally {
                    importing = false
                    pendingImport = null
                    updateImportProgress(null)
                }
            }
        }
    }

    fun openExternalFiles(uris: List<Uri>, mimeType: String? = null, requestId: String? = null) {
        if (uris.isEmpty() || (requestId != null && !externalRequestIds.add(requestId))) return
        externalFiles.trySend(Triple(uris.distinct(), mimeType, requestId))
    }

    fun setReaderViewport(size: IntSize, density: Density, direction: LayoutDirection) {
        if (size.width > 0 && size.height > 0) readerViewport = ReaderViewport(size, density, direction)
    }

    private fun updateImportProgress(progress: ImportProgress?) {
        importProgress = progress
        pendingImport = pendingImport?.copy(progress = progress)
    }

    private suspend fun beginImport(uris: List<Uri>, selectedMode: LibraryMode) {
        pendingImport = PendingImport(if (uris.size == 1) "正在读取文件" else "导入 ${uris.size} 个文件", selectedMode)
        if (uris.size == 1) {
            val uri = uris.single()
            val title = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "正在读取文件"
            }
            pendingImport = pendingImport?.copy(title = title.substringBeforeLast('.').ifBlank { title })
        }
    }

    private suspend fun prepareImportedBooks(books: List<Book>): List<String> {
        val novels = books.filter { it.mode == LibraryMode.NOVEL && it.format in setOf(BookFormat.TXT, BookFormat.EPUB, BookFormat.MOBI) &&
            profileSettings(it.mode).readingMode != ReadingMode.SCROLL }
        if (novels.isEmpty()) return emptyList()
        preparingLayout = true
        val notices = mutableListOf<String>()
        try {
            // A cold external open can finish parsing before the first screen measurement.
            repeat(10) { if (readerViewport == null) delay(100) }
            val viewport = readerViewport ?: return listOf("已导入，阅读区域尚未就绪，首次打开时完成排版")
            novels.forEachIndexed { index, book ->
                val settings = profileSettings(book.mode)
                updateImportProgress(ImportProgress("预排版", book.title, index, novels.size, 0f))
                try {
                    prepaginateBook(getApplication(), book, settings,
                        readerLayoutSpec(settings, viewport.density, viewport.direction, viewport.size)) { fraction ->
                        withContext(Dispatchers.Main) {
                            updateImportProgress(ImportProgress("预排版", book.title, index, novels.size, fraction))
                        }
                    }
                    updateImportProgress(ImportProgress("预排版", book.title, index + 1, novels.size, 1f))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notices += "《${book.title}》已导入，但预排版失败：${error.message.orEmpty()}"
                }
            }
        } finally {
            preparingLayout = false
            updateImportProgress(null)
        }
        return notices
    }

    private fun changed(newState: LibraryState) {
        state = newState
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(350)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        if (!storageReady) return
        saveMutex.withLock {
            val snapshot = state
            withContext(Dispatchers.IO) { repository.save(snapshot) }
            CloudBackupAutomation.changed(getApplication(), snapshot, cloudConfigured)
        }
    }

    fun flush() {
        val elapsed = if (sessionStarted > 0) (System.currentTimeMillis() - sessionStarted) / 1000 else 0
        if (sessionStarted > 0) sessionStarted = System.currentTimeMillis()
        if (elapsed > 0) state = state.copy(readingSeconds = state.readingSeconds + elapsed)
        saveJob?.cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { persistNow() }
        }
    }

    fun pauseSession() {
        foreground = false
        flush()
        sessionStarted = 0L
    }

    fun resumeSession() {
        foreground = true
        if (activeBookId != null && sessionStarted == 0L) sessionStarted = System.currentTimeMillis()
        if (!loading) viewModelScope.launch { refreshCloudConfiguration() }
    }

    fun open(book: Book) {
        if (book.cloudOnly) { message = "请点击封面的云端图标，手动下载《${book.title}》后阅读"; return }
        openJob?.cancel()
        openJob = null
        if (!book.sourceLinked) {
            openAvailableBook(book)
            return
        }
        openJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { requireBookSourceAvailable(getApplication(), book) }
                openJob = null
                openAvailableBook(book)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = error.message ?: "源文件无法读取，请重新选择"
            }
        }
    }

    private fun openAvailableBook(book: Book) {
        close()
        mode = book.mode
        activeBookId = book.id
        sessionStarted = if (foreground) System.currentTimeMillis() else 0L
        changed(state.copy(books = state.books.map { if (it.id == book.id) book.copy(lastReadAt = System.currentTimeMillis()) else it }))
    }

    fun close() {
        openJob?.cancel()
        openJob = null
        if (activeBookId != null) flush()
        activeBookId = null
        sessionStarted = 0L
    }

    fun progress(chapter: Int, page: Int, offset: Int, progress: Float, bookId: String? = activeBookId,
                 imagePath: String? = null) {
        val id = bookId ?: return
        if (id != activeBookId) return
        changed(state.copy(books = state.books.map {
            if (it.id == id) it.copy(chapterIndex = chapter, pageIndex = page, characterOffset = offset,
                progress = progress.coerceIn(0f, 1f), lastReadAt = System.currentTimeMillis(), positionImagePath = imagePath) else it
        }))
    }

    fun settings(settings: ReaderSettings) = applySettings(settings, mode)

    fun resetReaderSettings(selectedMode: LibraryMode = mode) {
        changed(state.resetReaderPreferences(selectedMode).withResolvedTheme(systemDark))
    }

    fun updateSystemTheme(dark: Boolean) {
        systemDark = dark
        val resolved = state.withResolvedTheme(dark)
        if (resolved != state) state = resolved
    }

    fun setThemeMode(theme: ThemeMode) {
        if (!storageReady) return
        changed(state.copy(themeMode = theme).withResolvedTheme(systemDark))
    }

    fun setEinkMode(enabled: Boolean) {
        if (storageReady) changed(state.copy(einkMode = enabled))
    }

    fun setTabletDualPage(enabled: Boolean) {
        if (storageReady) changed(state.copy(tabletDualPage = enabled))
    }

    fun toggleNightMode() = setThemeMode(if (state.settings.nightMode) ThemeMode.LIGHT else ThemeMode.DARK)

    private fun applySettings(settings: ReaderSettings, selectedMode: LibraryMode) {
        val theme = if (settings.nightMode == state.settings.nightMode) state.themeMode
            else if (settings.nightMode) ThemeMode.DARK else ThemeMode.LIGHT
        val updated = if (selectedMode == LibraryMode.COMIC) state.copy(comicSettings = settings,
            settings = state.settings.copy(nightMode = settings.nightMode)) else state.copy(settings = settings,
            comicSettings = state.comicSettings.copy(nightMode = settings.nightMode))
        changed(updated.copy(themeMode = theme).withResolvedTheme(systemDark))
    }
    fun annotate(annotation: Annotation) = changed(state.copy(annotations = state.annotations + annotation))
    fun deleteAnnotation(id: String) = changed(state.copy(annotations = state.annotations.filterNot { it.id == id }))

    fun createGroup(name: String) {
        val value = groupName(name, mode) ?: return
        changed(state.copy(groups = state.groups + ShelfGroup(value, mode)))
    }

    fun renameGroup(id: String, name: String) {
        val group = state.groups.find { it.id == id && it.mode == mode } ?: return
        val value = groupName(name, group.mode, id) ?: return
        changed(state.copy(groups = state.groups.map { if (it.id == id) it.copy(name = value) else it }))
    }

    private fun groupName(name: String, selectedMode: LibraryMode, exceptId: String? = null): String? {
        val value = name.trim()
        if (value.isEmpty()) { message = "请输入分组名称"; return null }
        if (state.groups.any { it.mode == selectedMode && it.id != exceptId && it.name == value }) {
            message = "该模式已有同名分组"; return null
        }
        return value
    }

    fun deleteGroup(id: String) {
        if (state.groups.none { it.id == id && it.mode == mode }) return
        changed(state.copy(groups = state.groups.filterNot { it.id == id },
            books = state.books.map { if (it.groupId == id) it.copy(groupId = null) else it }))
    }

    fun moveBookToGroup(bookId: String, groupId: String?) {
        val book = state.books.find { it.id == bookId } ?: return
        if (groupId != null && state.groups.none { it.id == groupId && it.mode == book.mode }) {
            message = "请选择该书所属模式的分组"; return
        }
        changed(state.copy(books = state.books.map { if (it.id == bookId) it.copy(groupId = groupId) else it }))
    }

    fun deleteBook(book: Book) = removeBook(book, false)

    fun removeBook(book: Book, removeCloud: Boolean) {
        if (removeCloud) {
            val allowMetered = meteredNetwork
            cloudOperation(book.id, "移除《${book.title}》及云端备份", beforeLock = {
                stopAiSpeechConversion(book.id)
                stopCloudUploadSubmission(book.id)
            }) { service ->
                require(!meteredNetwork || allowMetered) { "网络已切换为计费网络，请重新确认" }
                service.deleteBook(book.id)
                service.identity()?.let { account -> withContext(Dispatchers.IO) { cloudHistory.forget(account, book.id) } }
                removeLocalBook(book)
            }
        } else viewModelScope.launch {
            try {
                stopAiSpeechConversion(book.id)
                stopCloudUploadSubmission(book.id)
                cloudOperationMutex.withBook(book.id) { removeLocalBook(book) }
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "移除书籍失败" }
        }
    }

    private suspend fun removeLocalBook(book: Book) {
        CloudBackupAutomation.cancelUpload(getApplication(), book.id)
        openJob?.cancel()
        openJob = null
        if (activeBookId == book.id) close()
        state = state.copy(books = state.books.filterNot { it.id == book.id },
            annotations = state.annotations.filterNot { it.bookId == book.id })
        saveJob?.cancel()
        persistNow()
        withContext(Dispatchers.IO) {
            CloudBackupService(getApplication()).discardPendingUpload(book.id)
            repository.removeBookFiles(book)
            removeReaderPageCaches(getApplication(), book.id)
            AiSpeechCache(getApplication()).clear(book.id)
        }
    }

    fun clearHistory() = changed(state.copy(books = state.books.map { it.copy(lastReadAt = 0) }))

    private suspend fun refreshCloudConfiguration() {
        try {
            val service = CloudBackupService(getApplication())
            val (account, server, identity) = withContext(Dispatchers.IO) {
                Triple(service.account(), service.serverUrl(), service.identity())
            }
            cloudAccount = account.orEmpty()
            cloudServerUrl = server ?: com.eyue.reader.data.cloud.DEFAULT_WEBDAV_SERVER
            cloudIdentity = identity
            cloudConfigured = account != null
            if (identity != null) {
                val before = state
                if (state.cloudIdentity != null && state.cloudIdentity != identity) {
                    val removed = state.books.filter { it.cloudOnly }.map { it.id }.toSet()
                    state = state.copy(books = state.books.filterNot { it.cloudOnly }.map {
                        it.copy(cloudEnabled = false, cloudBackupAt = 0)
                    }, annotations = state.annotations.filterNot { it.bookId in removed }, cloudIdentity = identity)
                } else state = state.copy(cloudIdentity = identity)
                val merged = cloudBackupHistoryState(state) { cloudHistory.backupAt(identity, it) }
                state = merged
                if (state != before) persistNow()
            }
            CloudBackupAutomation.configure(getApplication(), cloudConfigured && state.cloudAutoBackup, state.cloudAutoBackupMetered)
            val error = cloudHistory.error()
            if (error != null) { message = "自动备份未完成：$error"; cloudHistory.error(null) }
        } catch (error: CancellationException) { throw error }
          catch (error: Exception) {
            cloudConfigured = false
            CloudBackupAutomation.configure(getApplication(), false)
            message = error.message ?: "WebDAV 配置无法读取，请重新填写服务器、账户和密码"
        }
    }

    private fun cloudOperation(bookId: String? = null, label: String,
        beforeLock: suspend (CloudBackupService) -> Unit = {}, action: suspend (CloudBackupService) -> Unit) {
        if (cloudBusy) return
        if (bookId != null && cloudBookJobs.containsKey(bookId)) return
        if (!loading && !storageReady) { message = "书架未能加载，无法备份或还原，请先处理本地书架错误"; return }
        if (loading || importing || rebuildingContents) { message = "请等待当前文件整理完成"; return }
        val requestedIdentity = cloudIdentity
        val generation = if (bookId == null) ++cloudGeneration else cloudGeneration
        if (bookId == null) {
            cloudOperationBusy = true
            cloudBookId = null
            cloudTaskLabel = label
            cloudFraction = null
        } else cloudBookOperations = cloudBookOperations + (bookId to CloudTransferProgress(label))
        val service = CloudBackupService(getApplication(), onProgress = { progress ->
            viewModelScope.launch {
                if (bookId != null && cloudBookJobs.containsKey(bookId)) {
                    cloudBookOperations = cloudBookOperations + (bookId to CloudTransferProgress(progress.stage, progress.fraction))
                } else if (cloudOperationBusy && cloudGeneration == generation) {
                    cloudTaskLabel = progress.stage
                    cloudFraction = progress.fraction
                }
            }
        })
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                beforeLock(service)
                if (bookId == null) cloudOperationMutex.withLock { action(service) }
                else cloudOperationMutex.withBook(bookId) {
                    require(withContext(Dispatchers.IO) { service.identity() } == requestedIdentity) { "WebDAV 配置已更改，请重新操作这本书" }
                    action(service)
                }
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "云端操作未完成，请稍后重试" }
            finally {
                if (bookId == null) {
                    cloudOperationBusy = false
                    cloudBookId = null
                    cloudTaskLabel = ""
                    cloudFraction = null
                    cloudJob = null
                } else {
                    cloudBookJobs.remove(bookId)
                    cloudBookOperations = cloudBookOperations - bookId
                }
            }
        }
        if (bookId == null) cloudJob = job else cloudBookJobs[bookId] = job
        job.start()
    }

    fun saveCloudAccount(account: String, password: String, serverUrl: String = cloudServerUrl) = cloudOperation(label = "保存 WebDAV 配置") { service ->
        val address = account.trim()
        require(address.isNotBlank()) { "请输入账户" }
        val normalizedServer = com.eyue.reader.data.cloud.normalizeWebDavServer(serverUrl)
        persistNow()
        withContext(Dispatchers.IO) {
            if (password.isBlank()) require(service.configured() && service.account() == address && service.serverUrl() == normalizedServer) {
                "更换服务器或账户时请填写密码"
            }
            else service.saveCredentials(address, password, normalizedServer)
        }
        refreshCloudConfiguration()
        persistNow()
        message = "WebDAV 配置已保存，可测试连接或点击书籍云图标上传"
    }

    fun disconnectCloud() = cloudOperation(label = "解除 WebDAV 连接") { service ->
        CloudBackupAutomation.cancelUploads(getApplication())
        withContext(Dispatchers.IO) { service.clearCredentials() }
        changed(state.copy(cloudAutoBackup = false))
        refreshCloudConfiguration()
        persistNow()
        message = "已解除连接，云端已有备份保留"
    }

    fun testCloudConnection() = cloudOperation(label = "测试 WebDAV 连接") { service ->
        service.testConnection()
        message = "WebDAV 连接成功"
    }

    suspend fun testCloudDraftConnection(account: String, password: String, server: String): String {
        val credentials = withContext(Dispatchers.IO) {
            com.eyue.reader.data.cloud.resolveCloudConnectionDraft(account, password, server,
                if (password.isBlank()) com.eyue.reader.data.cloud.EncryptedCloudCredentialStore(getApplication()).read() else null)
        }
        CloudBackupService(getApplication()).testConnection(credentials)
        return "WebDAV 连接成功"
    }

    fun setCloudAutoBackup(enabled: Boolean) {
        if (!storageReady) { message = "请先等待本地书架成功加载"; return }
        if (enabled && !cloudConfigured) { message = "请先配置 WebDAV 网盘"; return }
        changed(state.copy(cloudAutoBackup = enabled))
        CloudBackupAutomation.configure(getApplication(), enabled && cloudConfigured, state.cloudAutoBackupMetered)
    }

    fun setCloudAutoBackupMetered(enabled: Boolean) {
        if (!storageReady) return
        changed(state.copy(cloudAutoBackupMetered = enabled))
        CloudBackupAutomation.configure(getApplication(), state.cloudAutoBackup && cloudConfigured, enabled)
    }

    fun setSpeechEngine(engine: SpeechEngine) {
        if (engine == SpeechEngine.AI && aiSpeechConfig == null) { message = "请先配置 AI 语音接口"; return }
        changed(state.copy(speechEngine = engine))
    }

    fun saveAiSpeech(url: String, key: String, model: String, voice: String, instructions: String) {
        try {
            val previous = aiSpeechConfig
            saveAiSpeechProfile(AiSpeechConfig(url.trim(), key.trim(), model.trim(), voice.trim(), instructions.trim(),
                protocol = previous?.protocol ?: SpeechApiProtocol.OPENAI,
                name = previous?.name ?: "默认配置", id = previous?.id ?: java.util.UUID.randomUUID().toString(),
                rolePromptEnabled = previous?.rolePromptEnabled ?: false))
        } catch (error: IllegalArgumentException) { message = error.message ?: "AI 语音配置未保存" }
    }

    private suspend fun refreshAiSpeechConfiguration() {
        val (current, profiles) = withContext(Dispatchers.IO) { aiSpeechStore.read() to aiSpeechStore.profiles() }
        aiSpeechConfig = current
        aiSpeechProfiles = profiles
    }

    suspend fun testAiSpeechConnection(draft: AiSpeechConfig): String {
        val config = withContext(Dispatchers.IO) { aiSpeechStore.resolve(draft) }
        val target = File(getApplication<Application>().cacheDir,
            "ai-connection-${java.util.UUID.randomUUID()}.${if (config.protocol == SpeechApiProtocol.MIMO) "wav" else "mp3"}")
        try {
            val result = AiSpeechClient().test(config, target)
            return "已生成测试音频，耗时 ${java.lang.String.format(java.util.Locale.ROOT, "%.1f", result.elapsedMillis / 1000.0)} 秒（${(result.audioBytes + 1023) / 1024} KB）"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { target.delete() }
        }
    }

    fun saveAiSpeechProfile(config: AiSpeechConfig) {
        viewModelScope.launch {
            try {
                val previous = aiSpeechProfiles.firstOrNull { it.id == config.id }
                if (previous != null) withContext(Dispatchers.IO) {
                    val cache = AiSpeechCache(getApplication())
                    val snapshot = state
                    snapshot.books.filter { it.mode == LibraryMode.NOVEL && !it.cloudOnly && cache.bytes(it.id) > 0 }.forEach {
                        cache.chapterCacheStatus(it, previous, snapshot.commonVoiceRoles)
                    }
                }
                withContext(Dispatchers.IO) { aiSpeechStore.save(config) }
                refreshAiSpeechConfiguration()
                changed(state.copy(speechEngine = SpeechEngine.AI))
                message = "AI 语音配置已保存"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "AI 语音配置未保存" }
        }
    }

    fun selectAiSpeechProfile(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { aiSpeechStore.select(id) }
                refreshAiSpeechConfiguration()
                changed(state.copy(speechEngine = SpeechEngine.AI))
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "AI 语音配置切换失败" }
        }
    }

    fun setAiSpeechFallback(enabled: Boolean) = changed(state.copy(aiSpeechFallback = enabled))

    private fun rememberAiSpeechWork(bookId: String, id: java.util.UUID?) {
        if (id == null) aiSpeechObservedWorks.remove(bookId) else aiSpeechObservedWorks[bookId] = id
        aiSpeechTaskPreferences.edit().putString("book:$bookId", id?.toString()).apply()
    }

    fun generateAiSpeech(bookId: String, chapters: Set<Int>? = null) {
        if (aiSpeechSubmissionJobs[bookId]?.isActive == true || aiSpeechCancellationJobs[bookId]?.isActive == true ||
            aiSpeechGenerations[bookId]?.running == true) { message = "本书正在转化，请先完成或取消本书任务"; return }
        if (!storageReady || loading || importing || rebuildingContents || cleaningLibrary) { message = "请等待本地书架和文件整理完成"; return }
        val book = state.books.find { it.id == bookId } ?: return
        if (book.mode != LibraryMode.NOVEL) { message = "漫画无需转化 AI 语音"; return }
        if (book.cloudOnly) { message = "请先下载书籍正文"; return }
        val config = aiSpeechConfig
        if (config == null) { message = "请先配置 AI 语音接口"; return }
        aiSpeechGenerations = aiSpeechGenerations + (book.id to AiSpeechBatchProgress(book.id, book.title))
        val submission = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                persistNow()
                rememberAiSpeechWork(book.id, AiSpeechAutomation.enqueue(getApplication(), book, config, chapters))
            } catch (error: CancellationException) {
                aiSpeechGenerations[book.id]?.let { aiSpeechGenerations = aiSpeechGenerations + (book.id to it.copy(running = false, cancelled = true)) }
                throw error
            } catch (error: Exception) {
                aiSpeechGenerations[book.id]?.let { aiSpeechGenerations = aiSpeechGenerations + (book.id to it.copy(running = false,
                    error = error.message ?: "AI 语音转化失败")) }
            } finally {
                aiSpeechSubmissionJobs.remove(book.id)
            }
        }
        aiSpeechSubmissionJobs[book.id] = submission
        submission.start()
    }

    fun cancelAiSpeechGeneration(bookId: String) {
        if (aiSpeechCancellationJobs[bookId]?.isActive == true) return
        val submission = aiSpeechSubmissionJobs[bookId]
        submission?.cancel()
        val cancellation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                submission?.join()
                AiSpeechAutomation.cancelAndAwait(getApplication(), bookId)
                aiSpeechGenerations[bookId]?.let { aiSpeechGenerations = aiSpeechGenerations + (bookId to it.copy(running = false, cancelled = true)) }
                aiSpeechCacheRevision++
            } finally { aiSpeechCancellationJobs.remove(bookId) }
        }
        aiSpeechCancellationJobs[bookId] = cancellation
        cancellation.start()
    }

    fun cancelAiSpeechGeneration() { aiSpeechGeneration?.bookId?.let(::cancelAiSpeechGeneration) }

    internal suspend fun stopAiSpeechConversion(bookId: String) {
        aiSpeechCancellationJobs[bookId]?.join()
        aiSpeechSubmissionJobs[bookId]?.cancelAndJoin()
        AiSpeechAutomation.cancelAndAwait(getApplication(), bookId)
    }

    internal suspend fun stopAllAiSpeechConversions() {
        aiSpeechCancellationJobs.values.toList().forEach { it.join() }
        val submissions = aiSpeechSubmissionJobs.values.toList()
        submissions.forEach { it.cancel() }
        submissions.forEach { it.join() }
        AiSpeechAutomation.cancelAllAndAwait(getApplication())
    }

    fun dismissAiSpeechGeneration() {
        aiSpeechGeneration?.takeIf { !it.running }?.let {
            aiSpeechGenerations = aiSpeechGenerations - it.bookId
            rememberAiSpeechWork(it.bookId, null)
        }
    }

    fun saveBookVoiceRoles(bookId: String, roles: List<BookVoiceRole>) {
        val book = state.books.find { it.id == bookId } ?: return
        if (book.mode != LibraryMode.NOVEL || importing || loading || savingBookVoiceRoles || cleaningLibrary) return
        if (roles.map { it.id }.distinct().size != roles.size) { message = "角色记录重复，请重试"; return }
        savingBookVoiceRoles = true
        viewModelScope.launch {
            try {
                stopAiSpeechConversion(bookId)
                changed(state.copy(books = state.books.map { if (it.id == bookId) it.copy(voiceRoles = roles) else it }))
                persistNow()
                aiSpeechCacheRevision++
                message = "角色音色已保存"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "角色音色保存失败" }
            finally { savingBookVoiceRoles = false }
        }
    }

    fun saveCommonVoiceRoles(roles: List<BookVoiceRole>) {
        if (loading || importing || savingBookVoiceRoles || cleaningLibrary) return
        savingBookVoiceRoles = true
        viewModelScope.launch {
            try {
                stopAllAiSpeechConversions()
                changed(state.copy(commonVoiceRoles = roles))
                persistNow()
                aiSpeechCacheRevision++
                message = "通用角色已保存"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "通用角色保存失败" }
            finally { savingBookVoiceRoles = false }
        }
    }

    fun clearAiSpeechCache(book: Book) {
        viewModelScope.launch {
            try {
                stopAiSpeechConversion(book.id)
                withContext(Dispatchers.IO) { AiSpeechCache(getApplication()).clear(book.id) }
                aiSpeechCacheRevision++
                message = "已清理《${book.title}》的 AI 语音缓存"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "AI 语音缓存清理失败" }
        }
    }

    fun notifyAiSpeechCacheChanged() { aiSpeechCacheRevision++ }

    fun clearAiSpeechCaches() {
        viewModelScope.launch {
            try {
                stopAllAiSpeechConversions()
                withContext(Dispatchers.IO) {
                    val cache = AiSpeechCache(getApplication())
                    state.books.forEach { cache.clear(it.id) }
                }
                aiSpeechCacheRevision++
                message = "AI 语音缓存已清理"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "AI 语音缓存清理失败" }
        }
    }

    fun cleanupLibrary(kind: LibraryCleanupKind, clearCloud: Boolean) {
        if (cleaningLibrary || loading || importing || savingBookVoiceRoles || cloudBusy) return
        if (clearCloud && !cloudConfigured) { message = "请先配置 WebDAV 网盘"; return }
        val allowMetered = meteredNetwork
        val ids = state.books.map { it.id }.toSet()
        cleaningLibrary = true
        viewModelScope.launch {
            try {
                if (kind == LibraryCleanupKind.ROLES || kind == LibraryCleanupKind.AI_AUDIO) ids.forEach { stopAiSpeechConversion(it) }
                val account = cloudIdentity
                ids.forEach { stopCloudUploadSubmission(it) }
                cloudOperationMutex.withLock {
                    if (clearCloud && kind != LibraryCleanupKind.AI_AUDIO) {
                        val service = CloudBackupService(getApplication())
                        require(service.identity() == account) { "网盘配置已更改，请重新确认清理" }
                        ids.forEach { id ->
                            require(!meteredNetwork || allowMetered) { "网络已切换为计费网络，请重新确认" }
                            service.clearBookRecords(id, kind)
                        }
                    }
                    if (kind == LibraryCleanupKind.AI_AUDIO) withContext(Dispatchers.IO) {
                        val cache = AiSpeechCache(getApplication()); ids.forEach(cache::clear)
                    }
                    state = cleanLibraryRecords(state, kind, ids)
                    saveJob?.cancel()
                    // Do not immediately upload the local-only cleanup as an automatic backup change.
                    if (account != null) withContext(Dispatchers.IO) {
                        state.books.filter { it.id in ids && it.cloudEnabled && !it.cloudOnly && !cloudHistory.automaticPaused(account, it.id) }.forEach {
                            cloudHistory.record(account, it.id, cloudBackupFingerprint(repository, state, it), it.cloudBackupAt)
                        }
                    }
                    persistNow()
                }
                aiSpeechCacheRevision++
                message = if (clearCloud && kind != LibraryCleanupKind.AI_AUDIO) "本地及云端对应记录已清空" else "已完成本地清理"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "清理未完成，请重试" }
            finally { cleaningLibrary = false }
        }
    }

    fun uploadCloudBook(book: Book) = uploadCloudBooks(listOf(book))

    fun uploadCloudBooks(books: List<Book>) {
        if (cloudBusy) return
        if (!storageReady || loading || importing || rebuildingContents) { message = "请等待本地书架和文件整理完成"; return }
        if (!cloudConfigured) { message = "请先配置 WebDAV 网盘"; return }
        val selected = books.mapNotNull { target -> state.books.find { it.id == target.id && !it.cloudOnly && it.id !in cloudTransfers } }.distinctBy { it.id }
        val account = cloudIdentity ?: return
        selected.forEach { book ->
            pendingCloudUploads = pendingCloudUploads + (book.id to CloudTransferProgress("准备上传《${book.title}》", cancellable = true))
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    persistNow()
                    cloudUploadQueueMutex.withLock {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        require(cloudIdentity == account) { "WebDAV 配置已变更，请重新上传" }
                        // Complete this book's DB submission before its cancellation removes the task.
                        withContext(NonCancellable) { CloudBackupAutomation.upload(getApplication(), book, account) }
                    }
                } catch (error: CancellationException) { throw error }
                  catch (error: Exception) { message = error.message ?: "云端上传任务无法创建，请稍后重试" }
                finally {
                    cloudUploadJobs.remove(book.id)
                    pendingCloudUploads = pendingCloudUploads - book.id
                }
            }
            cloudUploadJobs[book.id] = job
            job.start()
        }
    }

    fun cancelCloudUpload(book: Book) {
        if (cloudTransfers[book.id]?.cancellable != true || book.id in cancellingCloudUploads) return
        val account = cloudIdentity ?: return
        cancellingCloudUploads = cancellingCloudUploads + book.id
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cloudHistory.pauseAutomatic(account, book.id) }
                stopCloudUploadSubmission(book.id)
                withContext(Dispatchers.IO) {
                    cloudOperationMutex.withBook(book.id) { cloudHistory.pauseAutomatic(account, book.id) }
                }
                message = "已取消《${book.title}》的上传，已确认分块保留，再次点击云图标可继续"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "取消上传失败，请重试" }
            finally {
                cancellingCloudUploads = cancellingCloudUploads - book.id
            }
        }
    }

    private suspend fun stopCloudUploadSubmission(bookId: String) {
        CloudBackupAutomation.cancelSubmissionAndUpload(getApplication(), bookId, cloudUploadJobs[bookId], cloudUploadQueueMutex)
    }

    fun deleteCloudBook(book: Book) = cloudOperation(book.id, "删除《${book.title}》的云端文件", beforeLock = { service ->
        val account = withContext(Dispatchers.IO) { requireNotNull(service.identity()) { "请先配置 WebDAV 网盘" } }
        withContext(Dispatchers.IO) { cloudHistory.pauseAutomatic(account, book.id) }
        stopCloudUploadSubmission(book.id)
    }) { service ->
        val current = state.books.find { it.id == book.id && it.cloudEnabled } ?: return@cloudOperation
        val account = withContext(Dispatchers.IO) { requireNotNull(service.identity()) { "请先配置 WebDAV 网盘" } }
        service.deleteBook(current.id)
        withContext(Dispatchers.IO) { cloudHistory.forget(account, current.id) }
        if (current.cloudOnly) {
            state = state.copy(books = state.books.filterNot { it.id == current.id },
                annotations = state.annotations.filterNot { it.bookId == current.id })
        } else state = state.copy(books = state.books.map {
            if (it.id == current.id) it.copy(cloudEnabled = false, cloudBackupAt = 0) else it
        })
        saveJob?.cancel()
        persistNow()
        message = "《${book.title}》的云端文件已删除，本书自动备份已关闭"
    }

    fun restoreCloudBookInfo() = cloudOperation(label = "还原云端书籍信息") { service ->
        val info = service.list()
        val matches = service.restoreBookMatches(state, info)
        if (cloudRestoreConflicts(state, info, matches).isNotEmpty()) {
            pendingRestoreIdentity = service.identity()
            pendingRestoreMatches = matches
            pendingCloudRestore = info
            return@cloudOperation
        }
        val before = state.books.size
        changed(resolveCloudRestoreInformation(state, info, emptyMap(), matches))
        persistNow()
        message = "已还原云端信息，新增 ${state.books.size - before} 本；请点击云图标逐本下载，本地已有书籍保留"
    }

    fun cancelCloudRestore() { pendingCloudRestore = null; pendingRestoreIdentity = null; pendingRestoreMatches = emptyMap() }

    fun resolveCloudRestore(choices: Map<String, CloudRestoreChoice>, allowMetered: Boolean) {
        val selections = cloudRestoreConflicts.associate { conflict -> conflict.remote.id to when (choices[conflict.remote.id] ?: CloudRestoreChoice.BOTH) {
            CloudRestoreChoice.LOCAL -> CloudRestoreSelection(conflict.locals.map { it.id }.toSet(), false)
            CloudRestoreChoice.CLOUD -> CloudRestoreSelection(conflict.locals.filter { it.id != conflict.local.id }.map { it.id }.toSet(), true)
            CloudRestoreChoice.BOTH -> CloudRestoreSelection(conflict.locals.map { it.id }.toSet(), true)
        } }
        resolveCloudRestoreSelections(selections, allowMetered)
    }

    fun resolveCloudRestoreSelections(selections: Map<String, CloudRestoreSelection>, allowMetered: Boolean) {
        val info = pendingCloudRestore ?: return
        val conflicts = cloudRestoreConflicts
        val resolved = try { cloudRestoreSelectionsForState(state, info, selections, pendingRestoreMatches) }
            catch (error: IllegalArgumentException) { message = error.message; return }
        val updatesCloud = resolved.values.any { it.keepLocalIds.size + (if (it.keepRemote) 1 else 0) == 1 }
        if (updatesCloud && meteredNetwork && !allowMetered) { message = "当前为计费网络，请确认允许流量后更新云端"; return }
        cloudOperation(label = "处理同名书籍并更新备份") { service ->
            require(service.identity() == pendingRestoreIdentity) { "网盘配置已更改，请重新还原书籍信息" }
            for (conflict in conflicts) {
                val selection = resolved.getValue(conflict.remote.id)
                val count = selection.keepLocalIds.size + if (selection.keepRemote) 1 else 0
                if (count != 1) continue
                require(!meteredNetwork || allowMetered) {
                    "网络已切换为计费网络，请确认后继续还原"
                }
                if (!selection.keepRemote) {
                        val local = state.books.find { it.id == selection.keepLocalIds.single() && !it.cloudOnly }
                            ?: error("本地书籍已变化，请重新还原书籍信息")
                        val snapshot = state
                        val result = service.upload(snapshot, local) {
                            require(!meteredNetwork || allowMetered) { "网络已切换为计费网络，请确认后继续上传" }
                        }
                        service.deleteBook(conflict.remote.id)
                        val identity = requireNotNull(service.identity())
                        withContext(Dispatchers.IO) {
                            cloudHistory.record(identity, local.id, cloudBackupFingerprint(repository, snapshot, local), result.backupAt)
                            cloudHistory.forget(identity, conflict.remote.id)
                        }
                        state = state.copy(books = state.books.map { if (it.id == local.id) it.copy(cloudEnabled = true, cloudBackupAt = result.backupAt) else it })
                }
                for (candidate in conflict.locals.filterNot { it.id in selection.keepLocalIds }) {
                        val local = state.books.find { it.id == candidate.id } ?: continue
                        withContext(Dispatchers.IO) { cloudHistory.pauseAutomatic(requireNotNull(service.identity()), local.id) }
                        service.deleteBook(local.id)
                        withContext(Dispatchers.IO) { cloudHistory.forget(requireNotNull(service.identity()), local.id) }
                }
            }
            val restored = resolveCloudRestoreSelectionsInformation(state, info, resolved, pendingRestoreMatches)
            val discarded = state.books.filter { local -> !local.cloudOnly && restored.books.none { it.id == local.id } }
            for (book in discarded) stopAiSpeechConversion(book.id)
            changed(restored)
            persistNow()
            withContext(Dispatchers.IO) { discarded.forEach { book ->
                repository.removeBookFiles(book)
                AiSpeechCache(getApplication()).clear(book.id)
            } }
            cancelCloudRestore()
            message = "书籍信息已还原，请点击云图标下载需要阅读的书籍"
        }
    }

    fun downloadCloudBook(book: Book) = cloudOperation(book.id, "准备下载《${book.title}》") { service ->
        if (state.books.none { it.id == book.id && it.cloudOnly }) { message = "这本书已在本地，可直接阅读"; return@cloudOperation }
        val restored = service.download(book.id)
        if (state.books.none { it.id == book.id }) {
            withContext(Dispatchers.IO) { repository.removeBookFiles(restored.book) }
            return@cloudOperation
        }
        changed(mergeCloudDownloadedBook(state, restored))
        persistNow()
        val account = withContext(Dispatchers.IO) { service.identity() }
        val snapshot = state
        val downloadedBook = snapshot.books.first { it.id == book.id }
        if (account != null) withContext(Dispatchers.IO) {
            cloudHistory.record(account, book.id, cloudBackupFingerprint(repository, snapshot, downloadedBook), restored.backupAt)
        }
        message = "《${book.title}》的内容和阅读记录已下载，可以开始阅读"
    }

    fun importFiles(uris: List<Uri>, selectedMode: LibraryMode) {
        if (uris.isEmpty() || importing) return
        viewModelScope.launch {
            importing = true
            try {
                beginImport(uris, selectedMode)
                runCatching {
                    val books = importer.importFiles(uris, selectedMode, state.settings.chapterRule)
                    pendingImport = pendingImport?.copy(mode = books.first().mode, bookIds = books.map { it.id }.toSet())
                    if (mode == selectedMode) mode = books.first().mode
                    changed(state.copy(books = state.books + books))
                    persistNow()
                    val notices = importer.lastImportErrors + importer.lastImportWarnings + prepareImportedBooks(books)
                    message = if (notices.isEmpty()) "已导入 ${books.size} 本，去书架翻开看看" else
                        "已导入 ${books.size} 本\n${notices.joinToString("\n")}"
                }.onFailure {
                    if (it is CancellationException) throw it
                    message = it.message ?: "导入失败，请确认文件可读取"
                }
            } finally {
                importing = false
                pendingImport = null
                updateImportProgress(null)
            }
        }
    }

    fun importFolder(uri: Uri) {
        if (importing) return
        viewModelScope.launch {
            importing = true
            try {
                beginImport(listOf(uri), LibraryMode.COMIC)
                runCatching { importer.importFolder(uri, state.settings.chapterRule) }.onSuccess {
                    changed(state.copy(books = state.books + it)); mode = LibraryMode.COMIC
                    message = (listOf("《${it.title}》已导入，共 ${it.chapters.size} 章") + importer.lastImportWarnings).joinToString("\n")
                }.onFailure {
                    if (it is CancellationException) throw it
                    message = it.message ?: "文件夹导入失败"
                }
            } finally {
                importing = false
                pendingImport = null
                updateImportProgress(null)
            }
        }
    }

    fun importBackground(uri: Uri, selectedMode: LibraryMode = mode) {
        viewModelScope.launch {
            try {
                val path = importReaderBackground(getApplication(), uri)
                applySettings(profileSettings(selectedMode).copy(background = ReaderBackground.IMAGE,
                    backgroundImagePath = path), selectedMode)
                message = "已应用自定义背景"
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { message = error.message ?: "背景图片导入失败" }
        }
    }

    fun importFont(uri: Uri, selectedMode: LibraryMode = mode) {
        viewModelScope.launch {
            runCatching { importer.importFont(uri) }.onSuccess { font ->
                val settings = profileSettings(selectedMode)
                applySettings(settings.copy(fontName = font.name, fontPath = font.path,
                    fonts = settings.fonts.filterNot { it.name == font.name } + font), selectedMode)
                message = "已应用字体「${font.name}」"
            }.onFailure { message = it.message ?: "字体导入失败，请选择 TTF 或 OTF 字体" }
        }
    }

    fun saveChapterRule(rule: String) = saveChapterRulePreset(
        state.settings.chapterRules.find { it.id == state.settings.activeChapterRuleId }?.copy(pattern = rule)
            ?: ChapterRulePreset(name = "自定义规则", pattern = rule))

    fun saveChapterRulePreset(preset: ChapterRulePreset) {
        if (importing || loading) return
        runCatching {
            require(preset.name.isNotBlank()) { "请输入规则名称" }
            TextParser.validateRule(preset.pattern)
        }.onFailure { message = it.message; return }
        if (preset.id == "default" && preset.pattern != DEFAULT_CHAPTER_RULE) {
            message = "默认规则保留不变，请另存为新规则"; return
        }
        val presets = state.settings.chapterRules.toMutableList()
        val index = presets.indexOfFirst { it.id == preset.id }
        if (index >= 0) presets[index] = preset.copy(name = preset.name.trim()) else presets += preset.copy(name = preset.name.trim())
        val settings = state.settings.copy(chapterRules = presets)
        if (preset.id == settings.activeChapterRuleId && preset.pattern != settings.chapterRule)
            applyChapterRule(settings.copy(chapterRule = preset.pattern))
        else changed(state.copy(settings = settings))
    }

    fun selectChapterRule(id: String) {
        if (importing || loading) return
        val preset = state.settings.chapterRules.find { it.id == id } ?: return
        val settings = state.settings.copy(activeChapterRuleId = id, chapterRule = preset.pattern)
        if (preset.pattern == state.settings.chapterRule) changed(state.copy(settings = settings)) else applyChapterRule(settings)
    }

    fun deleteChapterRule(id: String) {
        if (importing || loading) return
        if (id == "default" || id == state.settings.activeChapterRuleId) { message = "请保留默认规则，并先切换使用的规则再删除"; return }
        changed(state.copy(settings = state.settings.copy(chapterRules = state.settings.chapterRules.filterNot { it.id == id })))
    }

    private fun applyChapterRule(settings: ReaderSettings) {
        val rule = settings.chapterRule
        if (cloudBusy || cloudTransfers.isNotEmpty()) { message = "请等待云端上传或还原完成后再更新目录"; return }
        if (importing) return
        viewModelScope.launch {
            runCatching { TextParser.validateRule(rule) }.onSuccess {
                importing = true
                rebuildingContents = true
                try {
                    val updated = state.books.toMutableList()
                    var annotations = state.annotations
                    var failures = 0
                    updated.indices.forEach { i ->
                        if (!updated[i].cloudOnly && updated[i].mode == LibraryMode.NOVEL && updated[i].format in setOf(BookFormat.TXT, BookFormat.MOBI)) {
                            val oldBook = updated[i]
                            runCatching {
                                stopAiSpeechConversion(oldBook.id)
                                val newBook = importer.rebuildChapters(oldBook, rule)
                                val migrated = withContext(Dispatchers.IO) {
                                    val oldText = oldBook.chapters.map { chapter -> chapter.textPath?.let { File(it).readText() }.orEmpty() }
                                    val newText = newBook.chapters.map { chapter -> chapter.textPath?.let { File(it).readText() }.orEmpty() }
                                    val chapterStarts = newText.runningFold(0) { offset, text -> offset + text.length }.dropLast(1)
                                    val images = newBook.chapters.flatMapIndexed { chapter, content ->
                                        content.inlineImages.map { chapter to it }
                                    }.groupBy { it.second.path }
                                    annotations.map { annotation ->
                                        if (annotation.bookId != oldBook.id) annotation else {
                                            val position = TextParser.relocatePosition(oldText, newText,
                                                annotation.chapterIndex, annotation.characterOffset, annotation.quote)
                                            val mappedOffset = chapterStarts[position.first] + position.second
                                            val image = annotation.imagePath?.let { images[it] }?.minByOrNull { (chapter, image) ->
                                                kotlin.math.abs(chapterStarts[chapter].toLong() + image.offset - mappedOffset)
                                            }
                                            if (image != null) annotation.copy(chapterIndex = image.first, pageIndex = 0,
                                                characterOffset = image.second.offset, endOffset = image.second.offset)
                                            else relocateTextAnnotation(annotation, oldText, newText, position)
                                        }
                                    }
                                }
                                newBook to migrated
                            }.onSuccess { (newBook, migrated) -> updated[i] = newBook; annotations = migrated }
                                .onFailure { if (it is CancellationException) throw it; failures++ }
                        }
                    }
                    changed(state.copy(books = updated, annotations = annotations, settings = state.settings.copy(
                        chapterRule = rule, chapterRules = settings.chapterRules, activeChapterRuleId = settings.activeChapterRuleId)))
                    message = if (failures == 0) "目录规则已保存，TXT / MOBI 目录已重新生成" else "规则已保存；有 $failures 本未能重新生成目录"
                } finally {
                    importing = false
                    rebuildingContents = false
                }
            }.onFailure { if (it is CancellationException) throw it; message = "规则无效：${it.message}" }
        }
    }

    private data class PendingSourceEdit(val bookId: String, val chapter: Int, val start: Int, val end: Int,
        val replacement: String, val expectedText: String? = null, val findAll: String? = null)
    private var pendingSourceEdit: PendingSourceEdit? = null
    var pendingSourceWriteUri by mutableStateOf<String?>(null)
        private set

    fun cancelSourceWrite() { pendingSourceWriteUri = null; pendingSourceEdit = null }

    fun resumeSourceWrite(uri: Uri) {
        val pending = pendingSourceEdit ?: return
        val original = state.books.find { it.id == pending.bookId } ?: return cancelSourceWrite()
        pendingSourceWriteUri = null
        pendingSourceEdit = null
        if (importing || cloudBusy || original.id in cloudTransfers || activeBookId != original.id) { message = "请重新打开这本书后再编辑"; return }
        importing = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(EbookSourceWriteback.sameDocument(getApplication(), EbookSourceWriteback.source(original), uri.toString())) {
                        "请选择刚才编辑的同一本原文件，修改尚未保存"
                    }
                    EbookSourceWriteback.requireWritable(getApplication(), uri.toString())
                }
                importing = false
                editBookText(pending.chapter, pending.start, pending.end, pending.replacement, uri.toString(),
                    pending.expectedText, pending.findAll)
            } catch (error: Exception) {
                importing = false
                if (error is CancellationException) throw error
                message = error.message ?: "无法获得原文件写入权限，修改尚未保存"
            }
        }
    }

    fun editTxt(chapter: Int, start: Int, end: Int, replacement: String) = editBookText(chapter, start, end, replacement)

    fun replaceBookMatch(chapter: Int, start: Int, expectedText: String, replacement: String) {
        if (expectedText.isEmpty()) return
        editBookText(chapter, start, start + expectedText.length, replacement, expectedText = expectedText)
    }

    fun replaceAllBookMatches(query: String, replacement: String) {
        if (query.trim().isEmpty()) return
        editBookText(0, 0, 0, replacement, findAll = query.trim())
    }

    private fun editBookText(chapter: Int, start: Int, end: Int, replacement: String, sourceOverride: String? = null,
        expectedText: String? = null, findAll: String? = null) {
        val book = state.books.find { it.id == activeBookId && it.mode == LibraryMode.NOVEL &&
            it.format in setOf(BookFormat.TXT, BookFormat.EPUB, BookFormat.MOBI) && !it.cloudOnly } ?: return
        if (importing || cloudBusy || book.id in cloudTransfers) { message = "请等待当前整理或本书云端操作完成后再编辑"; return }
        importing = true
        viewModelScope.launch {
            var editedDirectory: File? = null
            var sourceWrite: EbookSourceWriteback? = null
            var saved = false
            var sourceLocked = false
            var replacementCount = 0
            try {
                stopAiSpeechConversion(book.id)
                cloudOperationMutex.lock(); sourceLocked = true
                val (updated, relocate) = withContext<Pair<Book, (Annotation) -> Annotation>>(Dispatchers.IO) {
                    val folder = File(getApplication<Application>().filesDir, "books/${book.id}/edit-${java.util.UUID.randomUUID()}")
                    check(folder.mkdirs()) { "无法保存修改后的正文" }
                    editedDirectory = folder
                    if (book.format == BookFormat.TXT) {
                        val texts = book.chapters.map { File(requireNotNull(it.textPath)).readText() }
                        if (expectedText != null) require(texts.getOrNull(chapter)?.let {
                            start >= 0 && end <= it.length && it.substring(start, end) == expectedText
                        } == true) { "搜索结果已变化，请重新搜索后替换" }
                        val edit = if (findAll == null) TxtEdit(texts, chapter, start, end, replacement, state.settings.chapterRule)
                        else {
                            val replacements = texts.mapIndexed { index, text ->
                                index to literalTextReplacements(text, findAll, replacement)
                            }.filter { it.second.isNotEmpty() }.toMap()
                            replacementCount = replacements.values.sumOf { it.size }
                            require(replacementCount > 0) { "没有找到可替换的文字，请重新搜索" }
                            TxtEdit(texts, replacements, state.settings.chapterRule)
                        }
                        val source = File(folder, "source.txt").apply { writeText(edit.source) }
                        val chapters = edit.chapters.mapIndexed { index, parsed ->
                            Chapter(title = parsed.title, textPath = File(folder, "chapter-$index.txt").apply { writeText(parsed.text) }.absolutePath)
                        }
                        val position = edit.position(book.chapterIndex, book.characterOffset)
                        val progress = (edit.chapters.take(position.first).sumOf { it.text.length }.toFloat() + position.second) /
                            edit.chapters.sumOf { it.text.length }.coerceAtLeast(1)
                        book.copy(chapters = chapters, chapterIndex = position.first, characterOffset = position.second, pageIndex = 0,
                            progress = progress.coerceIn(0f, 1f), sourcePath = source.absolutePath, sourceUri = null, sourceLinked = false,
                            contentVersion = book.contentVersion + 1) to edit::annotation
                    } else {
                        val sourceBook = if (sourceOverride == null) book else book.copy(sourceUri = sourceOverride,
                            sourcePath = documentFile(sourceOverride)?.absolutePath, sourceLinked = true)
                        val (edited, annotationEdit) = if (findAll != null) {
                            prepareEbookReplacement(sourceBook, folder, findAll, replacement).also { replacementCount = it.count }
                                .let { it.book to it.annotation }
                        } else {
                            require(chapter in book.chapters.indices) { "编辑位置无效，请重新打开文本" }
                            val prepared = loadChapterContent(getApplication(), book, chapter)
                            if (expectedText != null) require(start >= 0 && end <= prepared.text.length &&
                                prepared.text.substring(start, end) == expectedText) { "搜索结果已变化，请重新搜索后替换" }
                            val edit = EbookTextEdit(chapter, prepared.text, start, end, replacement)
                            val textFile = File(folder, "chapter-$chapter.txt").apply { writeText(edit.text) }
                            val updatedChapter = edit.chapter(prepared.chapter, textFile.absolutePath)
                            val lengths = book.chapters.map { content -> content.characterCount.takeIf { it >= 0 }
                                ?: File(requireNotNull(content.textPath)).readText().length }
                            edit.book(sourceBook, updatedChapter, lengths) to edit::annotation
                        }
                        val updated = edited.let {
                            if (it.sourceUri == null) it else it.copy(sourceLinked = true, sourcePath = documentFile(it.sourceUri)?.absolutePath)
                        }
                        sourceWrite = EbookSourceWriteback.prepare(getApplication(), sourceBook, updated, folder)
                        withContext(NonCancellable) { sourceWrite!!.commit() }
                        updated to annotationEdit
                    }
                }
                saveJob?.cancel()
                val previous = state
                state = state.copy(books = state.books.map { if (it.id == book.id) updated else it },
                    annotations = state.annotations.map { if (it.bookId == book.id) relocate(it) else it })
                try { withContext(NonCancellable) { persistNow() } } catch (error: Exception) { state = previous; throw error }
                saved = true
                withContext(NonCancellable + Dispatchers.IO) { sourceWrite?.finish() }
                editedDirectory = null
                withContext(Dispatchers.IO) { removeReaderPageCaches(getApplication(), book.id) }
                message = if (findAll != null) "已替换 $replacementCount 处文字，阅读位置和标注已更新"
                    else if (book.format == BookFormat.TXT) "TXT 修改已保存，目录和标注已更新" else "正文已覆盖保存至原文件，标注已更新"
            } catch (error: CancellationException) { throw error }
              catch (error: SourceWritePermissionRequiredException) {
                  pendingSourceEdit = PendingSourceEdit(book.id, chapter, start, end, replacement, expectedText, findAll)
                  pendingSourceWriteUri = error.source
                  message = error.message
              }
              catch (error: Exception) { message = error.message ?: "正文修改未保存" }
            finally {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (!saved) try { sourceWrite?.rollback() }
                            catch (error: Exception) { message = error.message ?: "原文件恢复失败" }
                        if (!saved && sourceWrite?.recoveryRequired != true) editedDirectory?.deleteRecursively()
                    }
                } finally {
                    if (sourceLocked) cloudOperationMutex.unlock()
                    importing = false
                }
            }
        }
    }

    private data class PreparedBookReplacement(val book: Book, val count: Int, val annotation: (Annotation) -> Annotation)

    private suspend fun prepareEbookReplacement(book: Book, folder: File, query: String, replacement: String): PreparedBookReplacement {
        val edits = mutableMapOf<Int, EbookTextEdit>()
        var count = 0
        val lengths = mutableListOf<Int>()
        val chapters = book.chapters.mapIndexed { index, chapter ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val text = File(requireNotNull(chapter.textPath)).readText()
            val replacements = literalTextReplacements(text, query, replacement)
            if (replacements.isEmpty()) { lengths += text.length; chapter }
            else {
                val prepared = loadChapterContent(getApplication(), book, index)
                val edit = EbookTextEdit(index, prepared.text, replacements)
                edits[index] = edit
                count += replacements.size
                lengths += edit.text.length
                val file = File(folder, "chapter-$index.txt").apply { writeText(edit.text) }
                edit.chapter(prepared.chapter, file.absolutePath)
            }
        }
        require(count > 0) { "没有找到可替换的文字，请重新搜索" }
        val position = edits[book.chapterIndex]?.position(book.chapterIndex, book.characterOffset)
            ?: (book.chapterIndex to book.characterOffset)
        val progress = (lengths.take(position.first).sum().toFloat() + position.second) / lengths.sum().coerceAtLeast(1)
        return PreparedBookReplacement(book.copy(chapters = chapters, chapterIndex = position.first,
            characterOffset = position.second, pageIndex = 0, progress = progress.coerceIn(0f, 1f),
            contentVersion = book.contentVersion + 1, hasTextEdits = true), count) { annotation ->
            edits[annotation.chapterIndex]?.annotation(annotation) ?: annotation
        }
    }
}
