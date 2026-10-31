@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import android.app.Activity
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eyue.reader.ReaderViewModel
import com.eyue.reader.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun EReadApp(vm: ReaderViewModel, volumeEvents: Flow<Int>, updateSession: AppUpdateSession = viewModel()) {
    val systemDark = isSystemInDarkTheme()
    SideEffect { vm.updateSystemTheme(systemDark) }
    val dark = vm.state.themeMode.isDark(systemDark)
    CompositionLocalProvider(LocalSourceChangeIds provides vm.localSourceChangedIds, LocalSourceReimport provides { book ->
        (book.sourceUri ?: book.sourcePath)?.let { vm.importFiles(listOf(android.net.Uri.parse(it)), book.mode) }
    }) {
    EReadTheme(dark = dark, eink = vm.state.einkMode, style = vm.state.appTheme) {
        val state = vm.state.withResolvedTheme(systemDark)
        val settings = vm.readerSettings.copy(nightMode = dark)
        val density = LocalDensity.current
        val context = LocalContext.current
        val developerPreferences = remember(context) { DeveloperModePreferences(context) }
        LaunchedEffect(updateSession, developerPreferences) {
            updateSession.configureDeveloperMode(developerPreferences.enabled)
            updateSession.checkOnStartup()
        }
        val ebookSession = remember(context) { EbookReaderSession(context) }
        DisposableEffect(ebookSession) { onDispose { ebookSession.close() } }
        val direction = LocalLayoutDirection.current
        var safeViewport by remember { mutableStateOf(IntSize.Zero) }
        Box(Modifier.fillMaxSize().safeDrawingPadding().onSizeChanged { safeViewport = it })
        SideEffect {
            vm.setReaderViewport(readerBodyViewport(density, safeViewport),
                Density(density.density, density.fontScale), direction)
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var showImport by remember { mutableStateOf(false) }
        var showNotes by remember { mutableStateOf(false) }
        var showChapterRules by remember { mutableStateOf(false) }
        var missingSpeechConfig by remember { mutableStateOf(false) }
        var cacheSpeechSettings by remember { mutableStateOf(false) }
        var voiceRolesBookId by remember { mutableStateOf<String?>(null) }
        var commonVoiceRolesOpen by remember { mutableStateOf(false) }
        var selectedRoleText by remember { mutableStateOf<String?>(null) }
        var pendingNotificationTask by remember { mutableStateOf<(() -> Unit)?>(null) }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingNotificationTask; pendingNotificationTask = null
            if (!granted) vm.message = "通知权限未开启，转化进度可在目录查看"
            action?.invoke()
        }
        fun startNotifiedTask(action: () -> Unit) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingNotificationTask = action; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else action()
        }
        fun generateSpeech(bookId: String, chapters: Set<Int>? = null) {
            if (vm.aiSpeechConfig == null) missingSpeechConfig = true
            else startNotifiedTask { vm.generateAiSpeech(bookId, chapters) }
        }
        var selectedImportMode by rememberSaveable { mutableStateOf(vm.mode) }
        var selectedFontMode by rememberSaveable { mutableStateOf(vm.mode) }
        var selectedBackgroundMode by rememberSaveable { mutableStateOf(vm.mode) }
        var pendingLegacyDownloads by remember { mutableStateOf<List<Book>>(emptyList()) }
        val legacyDownloadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val targets = pendingLegacyDownloads; pendingLegacyDownloads = emptyList()
            if (granted) targets.forEach(vm::downloadCloudBook)
            else vm.message = "未获得下载目录写入权限，可在 WebDAV 设置中另选文件夹"
        }
        fun cloudBook(target: Book) {
            if (!vm.cloudConfigured) {
                tab = 2
                vm.message = "请先在设置中配置 WebDAV 网盘"
            } else if (target.cloudOnly && Build.VERSION.SDK_INT <= 28 &&
                com.eyue.reader.data.cloud.CloudDownloadLocation(context).tree == null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                val requestPermission = pendingLegacyDownloads.isEmpty()
                pendingLegacyDownloads = (pendingLegacyDownloads + target).distinctBy { it.id }
                if (requestPermission) legacyDownloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else if (target.cloudOnly) vm.downloadCloudBook(target)
            else vm.uploadCloudBook(target)
        }
        LaunchedEffect(vm.pendingImports.lastOrNull()?.id) {
            if (vm.activeBookId == null) vm.pendingImports.lastOrNull()?.let { tab = 1; vm.mode = it.mode }
        }
        val files = rememberLauncherForActivityResult(OpenBookDocuments()) {
            it.forEach { uri -> retainBookAccess(context, uri) }
            if (it.isNotEmpty()) vm.mode = selectedImportMode
            vm.importFiles(it, selectedImportMode)
        }
        var scanSourceBookId by rememberSaveable { mutableStateOf<String?>(null) }
        val scanSourceAccess = rememberLauncherForActivityResult(GrantBookWriteAccess(write = false)) { uri ->
            val bookId = scanSourceBookId
            scanSourceBookId = null
            if (uri != null && bookId != null) {
                retainBookAccess(context, uri)
                vm.grantScanSource(bookId, uri)
            }
        }
        val sourceWriteAccess = rememberLauncherForActivityResult(GrantBookWriteAccess()) { uri ->
            if (uri == null) vm.cancelSourceWrite() else {
                retainBookAccess(context, uri)
                vm.resumeSourceWrite(uri)
            }
        }
        val sourceRenameFolderAccess = rememberLauncherForActivityResult(GrantBookRenameFolder()) { uri ->
            if (uri == null) vm.cancelSourceRenameFolder() else {
                retainBookAccess(context, uri)
                vm.resumeSourceRenameFolder(uri)
            }
        }
        var sourceRenameFolderPickerOpen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(vm.pendingSourceRenameFolder) { if (vm.pendingSourceRenameFolder == null) sourceRenameFolderPickerOpen = false }
        vm.pendingSourceRenameFolder?.takeIf { !sourceRenameFolderPickerOpen }?.let { request ->
            AlertDialog(onDismissRequest = vm::cancelSourceRenameFolder, modifier = Modifier.testTag("source_rename_folder_dialog"),
                title = { Text("选择原文件所在文件夹") },
                text = { Text("当前文件入口不支持重命名。请选择“${request.sourceName}”所在的文件夹并授权；核对内容一致后，将修改源文件名和书架名称。") },
                confirmButton = { TextButton(onClick = {
                    sourceRenameFolderPickerOpen = true
                    sourceRenameFolderAccess.launch(request.source)
                }, modifier = Modifier.testTag("source_rename_choose_folder")) { Text("选择文件夹") } },
                dismissButton = { TextButton(onClick = vm::cancelSourceRenameFolder) { Text("取消") } })
        }
        var sourceWritePickerOpen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(vm.pendingSourceWriteUri) {
            val uri = vm.pendingSourceWriteUri
            if (uri == null) sourceWritePickerOpen = false
            else if (!sourceWritePickerOpen) {
                sourceWritePickerOpen = true
                sourceWriteAccess.launch(uri)
            }
        }
        val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::importFolder) }
        val font = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importFont(uri, selectedFontMode) } }
        fun importFont() { selectedFontMode = vm.mode; font.launch(arrayOf("*/*")) }
        val backgroundImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { uri -> vm.importBackground(uri, selectedBackgroundMode) }
        }
        fun importBackground() { selectedBackgroundMode = vm.mode; backgroundImage.launch(arrayOf("image/*")) }
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(vm.message) {
            vm.message?.let { message ->
                if (vm.activeBookId != null) withTimeoutOrNull(1000) { snack.showSnackbar(message) }
                else snack.showSnackbar(message)
                if (vm.message == message) vm.message = null
            }
        }
        val book = vm.activeReaderBook
        SideEffect { updateSession.setSettingsVisible(book == null && tab == 2) }
        val view = LocalView.current
        val activity = remember(view) {
            var context = view.context
            while (context is ContextWrapper && context !is Activity) context = context.baseContext
            context as? Activity
        }
        val window = activity?.window
        var leavingDraftIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
        var finishAfterDrafts by rememberSaveable { mutableStateOf(false) }
        fun finishLeaving() {
            leavingDraftIds = emptyList()
            if (finishAfterDrafts) activity?.finish() else vm.close()
        }
        fun saveBeforeLeaving(ids: List<String>) {
            if (ids.isEmpty()) finishLeaving()
            else vm.saveBookDraft(ids.first()) { saveBeforeLeaving(ids.drop(1)) }
        }
        fun closeReader() {
            val id = vm.activeBookId
            if (id != null && (vm.hasBookDraft(id) || id in vm.editingBookIds)) {
                finishAfterDrafts = false
                leavingDraftIds = listOf(id)
            } else vm.close()
        }
        BackHandler(enabled = book == null && (vm.unsavedBookIds.isNotEmpty() || vm.editingBookIds.isNotEmpty())) {
            finishAfterDrafts = true
            leavingDraftIds = (vm.unsavedBookIds + vm.editingBookIds).toList()
        }
        SideEffect {
            if (book == null && window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !state.settings.nightMode
                    isAppearanceLightNavigationBars = !state.settings.nightMode
                }
            }
        }
        if (book != null) {
            var showSpeechSettings by remember(book.id) { mutableStateOf(false) }
            var resumeSearch by remember(book.id) { mutableStateOf<ReaderSearchDraft?>(null) }
            key(book.id, book.contentVersion, book.chapters) {
                val speechFactory: (android.content.Context) -> ReaderSpeechController = remember(state.speechEngine, vm.aiSpeechConfig, state.aiSpeechFallback,
                    if (state.speechEngine == SpeechEngine.AI && vm.aiSpeechConfig?.rolePromptEnabled == true) book.voiceRoles else null,
                    if (state.speechEngine == SpeechEngine.AI && vm.aiSpeechConfig?.rolePromptEnabled == true) state.commonVoiceRoles else null) {
                    { context: android.content.Context -> if (state.speechEngine == SpeechEngine.AI) AiReaderSpeech(context, bookId = book.id,
                        fallbackEnabled = state.aiSpeechFallback, rolesProvider = { book.voiceRoles }, commonRolesProvider = { state.commonVoiceRoles },
                        bookProvider = { book }, onCacheChanged = vm::notifyAiSpeechCacheChanged) else ReaderSpeech(context) }
                }
                CompositionLocalProvider(LocalReaderSpeechFactory provides speechFactory) {
                ReaderScreen(book, settings, vm.readerAnnotations(book.id),
                    onClose = ::closeReader, ebookSession = ebookSession,
                    onProgress = { chapter, page, offset, progress, imagePath -> vm.progress(chapter, page, offset, progress, book.id, imagePath) },
                    onSettings = vm::settings,
                    onAddAnnotation = vm::annotate, onDeleteAnnotation = vm::deleteAnnotation,
                    onImportFont = ::importFont, volumeEvents = volumeEvents,
                    onEditTxt = vm::editTxt, onChapterRules = { showChapterRules = true }, onImportBackground = ::importBackground,
                    onReplaceMatch = { result, expected, replacement ->
                        resumeSearch = ReaderSearchDraft(expected, replacement)
                        vm.replaceBookMatch(result.chapter, result.offset, expected, replacement)
                    }, onReplaceAll = { query, replacement ->
                        resumeSearch = ReaderSearchDraft(query, replacement)
                        vm.replaceAllBookMatches(query, replacement)
                    }, textEditBusy = vm.textEditBusy, resumeSearch = resumeSearch, onSearchClosed = { resumeSearch = null },
                    onSpeechSettings = { showSpeechSettings = true },
                    onGenerateSpeech = if (book.mode == LibraryMode.NOVEL && !book.cloudOnly) { chapters -> generateSpeech(book.id, chapters) } else null,
                    speechGenerationBusy = vm.aiSpeechGenerations[book.id]?.running == true,
                    speechCacheConfig = vm.aiSpeechConfig, speechCacheRevision = vm.aiSpeechCacheRevision,
                    commonVoiceRoles = state.commonVoiceRoles,
                    onResetDefaults = { vm.resetReaderSettings(book.mode) }, speechGeneration = vm.aiSpeechGenerations[book.id],
                    onCancelSpeech = { vm.cancelAiSpeechGeneration(book.id) },
                    einkMode = state.einkMode, tabletDualPage = state.tabletDualPage,
                    hasUnsavedText = vm.hasBookDraft(book.id), saveProgress = vm.bookSaveProgress[book.id],
                    onSaveText = { vm.saveBookDraft(book.id) },
                    onRoleSelection = if (book.mode == LibraryMode.NOVEL) { text ->
                        selectedRoleText = text; voiceRolesBookId = book.id
                    } else null)
                }
            }
            if (showSpeechSettings) SpeechEngineDialog(state.speechEngine, vm.aiSpeechConfig, vm::setSpeechEngine,
                vm::saveAiSpeech, { showSpeechSettings = false }, profiles = vm.aiSpeechProfiles,
                onSelectProfile = vm::selectAiSpeechProfile, onSaveProfile = vm::saveAiSpeechProfile,
                onTestConnection = vm::testAiSpeechConnection,
                fallbackEnabled = state.aiSpeechFallback, onFallbackEnabled = vm::setAiSpeechFallback,
                onBookRoles = { selectedRoleText = null; voiceRolesBookId = book.id; showSpeechSettings = false })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(snack, Modifier.navigationBarsPadding().padding(12.dp))
            }
        } else if (vm.openingExternalImport != null) {
            val opening = requireNotNull(vm.openingExternalImport)
            BackHandler { vm.dismissExternalOpening() }
            Column(Modifier.fillMaxSize().padding(32.dp).testTag("external_reader_opening"),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(opening.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(opening.progress?.stage ?: if (opening.queued) "等待打开书籍" else "正在打开书籍")
                opening.progress?.fraction?.let { fraction ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snack) }, bottomBar = {
                if (state.appTheme == AppThemeStyle.XINGCAI) XingcaiNavigationBar(tab, updateSession.hasUnreadUpdate) { i ->
                    tab = i
                    if (i == 2) updateSession.setSettingsVisible(true)
                } else NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("阅读" to Icons.AutoMirrored.Outlined.MenuBook, "书架" to Icons.Outlined.LibraryBooks,
                        "设置" to Icons.Outlined.Tune).forEachIndexed { i, entry ->
                        NavigationBarItem(selected = tab == i, onClick = {
                            tab = i
                            if (i == 2) updateSession.setSettingsVisible(true)
                        },
                            icon = {
                                BadgedBox(badge = {
                                    if (i == 2 && updateSession.hasUnreadUpdate) Badge(
                                        Modifier.testTag("settings_update_badge"), containerColor = Color(0xFFD64045))
                                }) { Icon(entry.second, entry.first, Modifier.size(23.dp)) }
                            },
                            label = { Text(entry.first, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Olive,
                                selectedTextColor = Olive, indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.fillMaxSize().widthIn(max = 720.dp)) {
                        if (tab != 2) ModeTabs(vm.mode, { vm.mode = it }, { showNotes = true }, { showImport = true },
                            importEnabled = !vm.loading, nightMode = dark, onToggleNight = vm::toggleNightMode)
                        if (vm.loading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Olive) }
                        } else {
                            val pending = vm.pendingImport?.takeIf { it.mode == vm.mode }
                            val books = state.books.filter { it.mode == vm.mode }
                            when (tab) {
                                0 -> ReadingHome(books, state.readingSeconds, vm::open, { tab = 1 }, { showImport = true },
                                    onCloud = ::cloudBook, cloudBusy = vm.cloudBusy, cloudBookId = vm.cloudBookId,
                                    cloudTaskLabel = vm.cloudTaskLabel, cloudFraction = vm.cloudFraction,
                                    onCancelCloud = vm::cancelCloudUpload, cloudCanCancel = vm.cloudUploadCancellable,
                                    cloudTransfers = vm.cloudTransfers, cloudDirtyBookIds = vm.cloudDirtyBookIds,
                                    showRecentReading = state.showRecentReading)
                                1 -> key(vm.mode) {
                                    Bookshelf(books, query, { query = it }, vm::open, vm::deleteBook, { showImport = true },
                                        state.groups.filter { it.mode == vm.mode }, vm::createGroup, vm::renameGroup,
                                        vm::deleteGroup, vm::moveBookToGroup, pending = pending,
                                        onCloud = ::cloudBook, cloudBusy = vm.cloudBusy, cloudBookId = vm.cloudBookId,
                                        cloudTaskLabel = vm.cloudTaskLabel, cloudFraction = vm.cloudFraction,
                                        onCancelCloud = vm::cancelCloudUpload, onDeleteCloud = vm::deleteCloudBook,
                                        cloudCanCancel = vm.cloudUploadCancellable, onClearAiSpeechCache = vm::clearAiSpeechCache, onCloudBatch = { books ->
                                            if (!vm.cloudConfigured) { tab = 2; vm.message = "请先配置 WebDAV 网盘" } else vm.transferCloudBooks(books)
                                        }, onRestoreCloudBookInfo = {
                                            if (!vm.cloudConfigured) { tab = 2; vm.message = "请先配置 WebDAV 网盘" }
                                            else vm.restoreCloudBookInfo()
                                        }, onGenerateSpeech = { id -> generateSpeech(id) },
                                        onVoiceRoles = { id -> selectedRoleText = null; voiceRolesBookId = id },
                                        onRemoveWithCloud = vm::removeBook, onClearSpeechWithCloud = { book, _ -> vm.clearAiSpeechCache(book) },
                                        cloudConfigured = vm.cloudConfigured, meteredNetwork = vm.meteredNetwork,
                                        speechCacheRevision = vm.aiSpeechCacheRevision, cloudTransfers = vm.cloudTransfers,
                                        cloudDirtyBookIds = vm.cloudDirtyBookIds, pendingImports = vm.pendingImports.filter { it.mode == vm.mode },
                                        onRemoveBatch = vm::removeBooks, restoreProgress = vm.cloudRestoreProgress,
                                        onRenameBook = vm::renameBook)
                                }
                                else -> SettingsScreen(state, { vm.settings(vm.readerSettings.copy(nightMode = it.nightMode)) }, vm::saveChapterRule,
                                    ::importFont, vm::clearHistory, mode = LibraryMode.NOVEL, cloudAccount = vm.cloudAccount,
                                    cloudServerUrl = vm.cloudServerUrl,
                                    cloudConfigured = vm.cloudConfigured, cloudBusy = vm.cloudBusy, cloudTaskLabel = vm.cloudTaskLabel,
                                    cloudFraction = vm.cloudFraction, onSaveCloudAccount = vm::saveCloudAccount,
                                    onTestCloudConnection = vm::testCloudConnection, onCloudAutoBackup = vm::setCloudAutoBackup,
                                    onRestoreCloudBookInfo = vm::restoreCloudBookInfo, onDisconnectCloud = vm::disconnectCloud,
                                    onCloudAutoBackupMetered = vm::setCloudAutoBackupMetered,
                                    aiSpeechConfig = vm.aiSpeechConfig, onSpeechEngine = vm::setSpeechEngine, onSaveAiSpeech = vm::saveAiSpeech,
                                    aiSpeechProfiles = vm.aiSpeechProfiles, onSelectAiSpeechProfile = vm::selectAiSpeechProfile,
                                    onSaveAiSpeechProfile = vm::saveAiSpeechProfile,
                                    onTestAiSpeechConnection = vm::testAiSpeechConnection,
                                    onThemeMode = vm::setThemeMode, onClearAiSpeechCaches = vm::clearAiSpeechCaches,
                                    onTestCloudDraftConnection = vm::testCloudDraftConnection,
                                    onAiSpeechFallback = vm::setAiSpeechFallback,
                                    onBookVoiceRoles = { id -> selectedRoleText = null; voiceRolesBookId = id },
                                    onCleanup = vm::cleanupLibrary, speechCacheRevision = vm.aiSpeechCacheRevision,
                                    meteredNetwork = vm.meteredNetwork, onCommonVoiceRoles = { commonVoiceRolesOpen = true },
                                    onEinkMode = vm::setEinkMode, onTabletDualPage = vm::setTabletDualPage,
                                    restoreProgress = vm.cloudRestoreProgress,
                                    onAppTheme = vm::setAppTheme, availableUpdate = updateSession.availableUpdate,
                                    onCheckUpdate = updateSession::checkNow, onShowRecentReading = vm::setShowRecentReading, onScanLocalChanges = vm::setScanLocalChanges,
                                    localSourceScanIssues = vm.localSourceScanIssues, scanningLocalSources = vm.scanningLocalSources,
                                    onGrantScanSource = { book ->
                                        scanSourceBookId = book.id
                                        scanSourceAccess.launch(book.sourceUri ?: book.sourcePath.orEmpty())
                                    },
                                    developerMode = updateSession.developerMode, onEnableDeveloperMode = {
                                        runCatching { developerPreferences.enable() }.onSuccess {
                                            updateSession.configureDeveloperMode(true)
                                            updateSession.checkOnStartup()
                                        }.onFailure { error ->
                                            android.widget.Toast.makeText(context, error.message ?: "设置保存失败，请重试",
                                                android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (missingSpeechConfig) AlertDialog(onDismissRequest = { missingSpeechConfig = false },
            modifier = Modifier.testTag("speech_cache_missing_config"), title = { Text("尚未配置 AI 语音") },
            text = { Text("生成语音缓存需要先配置 AI 服务器、模型和声音。请前往朗读引擎设置完成配置。") },
            confirmButton = { TextButton(onClick = { missingSpeechConfig = false; cacheSpeechSettings = true }) { Text("去配置") } },
            dismissButton = { TextButton(onClick = { missingSpeechConfig = false }) { Text("取消") } })
        if (cacheSpeechSettings) SpeechEngineDialog(state.speechEngine, vm.aiSpeechConfig, vm::setSpeechEngine,
            vm::saveAiSpeech, { cacheSpeechSettings = false }, profiles = vm.aiSpeechProfiles,
            onSelectProfile = vm::selectAiSpeechProfile, onSaveProfile = vm::saveAiSpeechProfile,
            onTestConnection = vm::testAiSpeechConnection,
            fallbackEnabled = state.aiSpeechFallback, onFallbackEnabled = vm::setAiSpeechFallback)
        if (showImport) {
            ModalBottomSheet(onDismissRequest = { showImport = false }, containerColor = Paper) {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                    Text("把故事带进来", fontFamily = FontFamily.Serif, fontSize = 26.sp)
                    Text("优先直接读取原文件，仅保存必要的阅读缓存", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                    ImportOption(Icons.Outlined.Description, "导入文件", "EPUB、TXT、MOBI、PDF、图片 · 自动分类，支持多选") {
                        selectedImportMode = vm.mode; showImport = false; tab = 1
                        files.launch(arrayOf("*/*"))
                    }
                    ImportOption(Icons.Outlined.FolderOpen, "导入漫画文件夹", "多分支分章分节，单一子目录递归合并") {
                        showImport = false; tab = 1; vm.mode = LibraryMode.COMIC; folder.launch(null)
                    }
                }
            }
        }
        if (showNotes) {
            NotebookDialog(state, onDismiss = { showNotes = false }, onDelete = vm::deleteAnnotation, onOpen = { annotation ->
                state.books.find { it.id == annotation.bookId }?.let { target ->
                    vm.open(target.copy(chapterIndex = annotation.chapterIndex, pageIndex = annotation.pageIndex,
                        characterOffset = annotation.characterOffset, positionImagePath = annotation.imagePath))
                }
                showNotes = false
            })
        }
        if (vm.pendingImportConflict != null) ImportBookConflictDialog(requireNotNull(vm.pendingImportConflict), vm::resolveImportConflict)
        else if (vm.pendingCloudUpload != null) CloudRestoreDialog(vm.cloudUploadConflicts, vm.meteredNetwork, vm.cloudBusy,
            vm::cancelCloudUploadConflict, vm::resolveCloudUploadSelections, uploading = true)
        else if (vm.pendingCloudRestore != null) CloudRestoreDialog(vm.cloudRestoreConflicts, vm.meteredNetwork, vm.cloudBusy,
            vm::cancelCloudRestore, vm::resolveCloudRestoreSelections)
        vm.duplicateImportTitle?.let { title ->
            AlertDialog(onDismissRequest = vm::acknowledgeDuplicateImport,
                title = { Text("书籍已存在") }, text = { Text("《$title》的文件名和文件大小与书架中的书籍一致，已跳过重复导入。") },
                confirmButton = { TextButton(onClick = vm::acknowledgeDuplicateImport) { Text("知道了") } })
        }
        if (showChapterRules) {
            ChapterRulesDialog(state.settings.chapterRules, state.settings.activeChapterRuleId,
                onDismiss = { showChapterRules = false }, onSavePreset = vm::saveChapterRulePreset,
                onSelect = { showChapterRules = false; vm.selectChapterRule(it) }, onDelete = vm::deleteChapterRule)
        }
        state.books.firstOrNull { it.id == voiceRolesBookId }?.let { target ->
            BookVoiceRolesDialog(target, { vm.saveBookVoiceRoles(target.id, it) },
                { voiceRolesBookId = null; selectedRoleText = null }, selectedRoleText, saving = vm.savingBookVoiceRoles)
        }
        if (commonVoiceRolesOpen) BookVoiceRolesDialog(
            Book(id = "__common_roles__", title = "通用角色", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
                chapters = emptyList(), voiceRoles = state.commonVoiceRoles), vm::saveCommonVoiceRoles,
            { commonVoiceRolesOpen = false }, saving = vm.savingBookVoiceRoles)
        if (vm.rebuildingContents) {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("正在更新目录") },
                text = { Text("正在迁移阅读位置、书签和笔记") })
        }
        if (leavingDraftIds.isNotEmpty()) ReaderUnsavedChangesDialog(
            titles = leavingDraftIds.mapNotNull { id -> state.books.find { it.id == id }?.title },
            saving = leavingDraftIds.any { it in vm.editingBookIds || it in vm.bookSaveProgress },
            onSave = { saveBeforeLeaving(leavingDraftIds.filter(vm::hasBookDraft)) },
            onDiscard = { leavingDraftIds.forEach(vm::discardBookDraft); finishLeaving() },
            onCancel = { leavingDraftIds = emptyList() },
            preparing = leavingDraftIds.any { it in vm.editingBookIds && it !in vm.bookSaveProgress },
            progress = leavingDraftIds.firstNotNullOfOrNull { vm.bookSaveProgress[it] })
    }
    }
}

@Composable internal fun ModeTabs(mode: LibraryMode, onMode: (LibraryMode) -> Unit, onNotes: () -> Unit,
    onImport: () -> Unit = {}, importEnabled: Boolean = true, nightMode: Boolean = false, onToggleNight: () -> Unit = {}) {
    if (LocalAppThemeStyle.current == AppThemeStyle.XINGCAI) {
        XingcaiModeTabs(mode, onMode, onNotes, onImport, importEnabled, nightMode, onToggleNight)
        return
    }
    Row(Modifier.padding(start = 24.dp, end = 16.dp, top = 8.dp).fillMaxWidth().height(48.dp).testTag("mode_tabs"),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(44.dp).clip(appShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp)) {
            listOf(LibraryMode.NOVEL to "小说", LibraryMode.COMIC to "漫画").forEach { (value, label) ->
                val selected = mode == value
                Row(Modifier.weight(1f).fillMaxHeight().clip(appShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { onMode(value) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(if (value == LibraryMode.NOVEL) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.AutoStories,
                        null, Modifier.size(16.dp), tint = if (selected) Olive else Muted)
                    Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Olive else Muted, modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
        IconButton(onClick = onToggleNight, modifier = Modifier.testTag("top_night_mode")) {
            Icon(if (nightMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                if (nightMode) "切换到日间模式" else "切换到夜间模式", tint = Ink)
        }
        IconButton(onClick = onNotes, modifier = Modifier.testTag("notebook_entry")) {
            Icon(Icons.Outlined.Bookmarks, "书签与笔记", tint = Ink)
        }
        IconButton(onClick = onImport, enabled = importEnabled, modifier = Modifier.testTag("top_import")) {
            Icon(Icons.Outlined.Add, "导入书籍", tint = if (importEnabled) Ink else Muted)
        }
    }
}

@Composable private fun ImportOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(appShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(17.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, appShape(11.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Olive, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(title, fontSize = 15.sp); Text(subtitle, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)) }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}
