package com.eyue.reader.data.cloud

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.BitmapLoader
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.CloudBookMatch
import com.eyue.reader.data.cloudBookSourceMatch
import com.eyue.reader.data.hydrateBookSourceIdentity
import com.eyue.reader.data.sameTitleLocalBooks
import com.eyue.reader.data.ImportedFont
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.LibraryState
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ShelfGroup
import com.eyue.reader.data.documentFile
import com.eyue.reader.data.openDocumentStream
import com.eyue.reader.data.withLocalDocument
import com.eyue.reader.data.cloudOperationMutex
import com.eyue.reader.data.withDefaultComicMargins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private val cloudMetadataReads = Semaphore(3)

class CloudBackupService(
    private val context: Context,
    private val credentialStore: CloudCredentialStore = EncryptedCloudCredentialStore(context),
    private val transportFactory: (CloudCredentials, () -> Unit) -> CloudTransport = { credentials, check -> WebDavCloudTransport(credentials, check) },
    private val onProgress: (CloudOperationProgress) -> Unit = {}
) {
    private val repository = LibraryRepository(context)

    fun account(): String? = credentialStore.read()?.account
    fun serverUrl(): String? = credentialStore.read()?.serverUrl
    fun identity(): String? = credentialStore.read()?.identity
    fun configured(): Boolean = credentialStore.read() != null
    fun saveCredentials(account: String, password: String, serverUrl: String = DEFAULT_WEBDAV_SERVER) =
        credentialStore.write(CloudCredentials(account.trim(), password, serverUrl))
    fun clearCredentials() = credentialStore.clear()

    suspend fun restoreBookMatches(state: LibraryState, incoming: List<CloudBookInfo>): Map<Pair<String, String>, CloudBookMatch> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val check = { job.ensureActive() }
        val content = HashMap<String, String?>()
        val source = HashMap<String, String?>()
        onProgress(CloudOperationProgress("核对本地同名书籍"))
        val relevant = incoming.map { it.book }
        val identified = state.copy(books = state.books.filterNot { it.cloudOnly }.map { local ->
            if (local.sourceUri != null || relevant.any { remote -> local.id != remote.id && (
                    local.mode == remote.mode && local.title.trim().equals(remote.title.trim(), ignoreCase = true) ||
                        local.sourceFileName == remote.sourceFileName && !local.sourceFileName.isNullOrBlank() ||
                        local.sourceFileName.isNullOrBlank() && !remote.sourceFileName.isNullOrBlank()) })
                hydrateBookSourceIdentity(context, local)
            else local
        })
        buildMap {
            for (info in incoming) for (local in sameTitleLocalBooks(identified, info.book)) {
                check()
                if (cloudBookSourceMatch(local, info.book) == CloudBookMatch.SAME) {
                    put(local.id to info.book.id, CloudBookMatch.SAME)
                    continue
                }
                val identity = if (info.contentIdentity == null) null else content.getOrPut(local.id) { cloudBookContentIdentity(context, local, check) }
                put(local.id to info.book.id, when {
                    identity != null && identity == info.contentIdentity -> CloudBookMatch.SAME
                    identity != null && info.contentIdentity != null && identity.substringBefore(':') == info.contentIdentity.substringBefore(':') -> CloudBookMatch.DIFFERENT
                    info.sourceSha256 != null && !local.hasTextEdits && !info.book.hasTextEdits -> {
                        val hash = source.getOrPut(local.id) {
                            val path = if (local.sourceLinked) local.sourceUri ?: local.sourcePath else local.sourcePath ?: local.sourceUri
                            path?.let { runCatching { cloudSourceHash(context, it, check) }.getOrElse { error ->
                                if (error is CancellationException) throw error
                                null
                            } }
                        }
                        if (hash == info.sourceSha256) CloudBookMatch.SAME else CloudBookMatch.UNKNOWN
                    }
                    else -> CloudBookMatch.UNKNOWN
                })
            }
        }
    }

    suspend fun testConnection(credentials: CloudCredentials? = null) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val transport = if (credentials == null) transport { job.ensureActive() }
            else transportFactory(credentials) { job.ensureActive() }
        val cancellation = launch { try { awaitCancellation() } finally { transport.cancel() } }
        try {
            transport.ensureCollection("")
            transport.ensureCollection("books/")
            transport.metadata("catalog.json")
            Unit
        } finally { cancellation.cancel(); transport.cancel() }
    }

    suspend fun upload(state: LibraryState, book: Book, checkAllowed: () -> Unit = {}): CloudBookInfo =
        requireNotNull(uploadInternal(state, book, null, checkAllowed))

    internal suspend fun updateKnownBookRecords(state: LibraryState, book: Book, expectedBackupAt: Long,
        checkAllowed: () -> Unit): CloudBookInfo? = uploadInternal(state, book, expectedBackupAt, checkAllowed)

    private suspend fun uploadInternal(state: LibraryState, book: Book, expectedBackupAt: Long?,
        checkAllowed: () -> Unit): CloudBookInfo? = cloudOperationMutex.withBook(book.id) { withContext(Dispatchers.IO) {
        require(!book.cloudOnly) { "请先下载书籍，再上传备份" }
        CloudArchive.safeId(book.id)
        val job = currentCoroutineContext()
        val check = { job.ensureActive(); checkAllowed() }
        val credentials = requireNotNull(credentialStore.read()) { "请先配置 WebDAV 服务器、账户与密码" }
        val transport = transportFactory(credentials, check)
        val temporary = pendingDirectory(book.id, credentials.identity)
        val cancellationWatcher = launch {
            try { while (true) { delay(200); check() } }
            finally { transport.cancel() }
        }
        try {
            // A new catalog entry needs a usable version before any large transfer starts.
            val directory = "books/${book.id}/"
            val manifestPath = directory + "manifest.json"
            val known = if (expectedBackupAt != null) {
                val current = try { transport.versionedMetadata(manifestPath) }
                    catch (_: IllegalArgumentException) { return@withContext null }
                current ?: return@withContext null
                val saved = readManifest(current.bytes, book.id)
                if (saved.info.backupAt != expectedBackupAt || saved.info.book.title != book.title) return@withContext null
                val catalog = transport.metadata("catalog.json") ?: return@withContext null
                if (book.id !in readCatalog(catalog)) return@withContext null
                current to saved
            } else {
                preflightCatalog(transport, book.id)
                null
            }
            onProgress(CloudOperationProgress("整理《${book.title}》的备份"))
            val assets = LinkedHashMap<String, CloudAsset>()
            val references = HashMap<String, String>()
            fun path(source: String?): String? {
                if (source == null) return null
                val key = documentFile(source)?.canonicalPath ?: source
                return references.getOrPut(key) {
                    val extension = source.substringBefore('?').substringAfterLast('.', "bin")
                        .lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                    val name = "assets/${assets.size.toString().padStart(6, '0')}.$extension"
                    assets[name] = CloudAsset(name, source) { openDocumentStream(context, source) }
                    name
                }
            }
            val backupAt = System.currentTimeMillis()
            val source = if (book.format == BookFormat.IMAGES) null else
                (if (book.sourceLinked) book.sourceUri ?: book.sourcePath else book.sourcePath ?: book.sourceUri)
            val resourceHashes = CloudResourceHashCache(context, book.id)
            val materialized = materializeCloudBookCached(context, hydrateBookSourceIdentity(context, book), resourceHashes, check, source)
            check()
            val portable = remapBook(materialized, ::path).copy(sourcePath = path(source), sourceUri = null,
                sourceLinked = false, cloudEnabled = true, cloudOnly = false, cloudBackupAt = backupAt)
            val annotations = state.annotations.filter { it.bookId == book.id }.map { it.copy(imagePath = path(it.imagePath)) }
            val settings = remapSettings(if (book.mode == LibraryMode.COMIC) state.comicSettings else state.settings, ::path)
            val group = state.groups.firstOrNull { it.id == book.groupId && it.mode == book.mode }
            repository.epubIndexFile(book)?.takeIf { book.format == BookFormat.EPUB }?.let { sidecar ->
                assets["assets/readium-publication.json"] = CloudAsset("assets/readium-publication.json", sidecar.absolutePath) {
                    AtomicFile(sidecar).openRead()
                }
            }
            val assetList = assets.values.toList()
            val inspected = CloudArchive.inspect(assetList, check, resourceHashes)
            val contentIdentity = cloudBookContentIdentity(context, book, check, resourceHashes)
            resourceHashes.save()
            val sourceHash = inspected.firstOrNull { it.name == portable.sourcePath }?.sha256
            val previous = known?.second ?: transport.metadata(manifestPath)?.let { readManifest(it, book.id) }
            if (previous != null && previous.assets == inspected && contentBook(previous.info.book) == contentBook(portable)) {
                val info = previous.info.copy(book = portable, annotations = annotations, settings = settings,
                    group = group, backupAt = backupAt, readingSeconds = state.readingSeconds,
                    contentIdentity = contentIdentity, sourceSha256 = sourceHash)
                onProgress(CloudOperationProgress("更新《${book.title}》的云端阅读记录", 1f))
                val updated = writeManifest(info, inspected, previous.cover)
                if (known != null) {
                    if (!transport.putVersionedMetadata(manifestPath, updated, known.first.etag)) return@withContext null
                } else {
                    transport.putMetadata(manifestPath, updated)
                }
                if (known == null || !CloudBookSummary.from(known.second.info).sameIdentity(CloudBookSummary.from(info))) {
                    cloudOperationMutex.withCatalog(credentials.identity) { publishCatalog(transport, info, check) }
                }
                temporary.deleteRecursively()
                return@withContext info
            }
            if (expectedBackupAt != null) return@withContext null
            val archive = File(temporary, "book.zip")
            val contentDigest = digest(repository.writeBook(contentBook(portable)).toString())
            val cached = readPending(temporary)?.takeIf { it.contentDigest == contentDigest && it.assets == inspected &&
                archive.isFile && archive.length() == it.archiveBytes && CloudArchive.hash(archive, check) == it.archiveSha256 }
            var pending = cached ?: run {
                temporary.deleteRecursively()
                check(temporary.mkdirs())
                val revision = "backup-${UUID.randomUUID()}"
                val archiveDigest = MessageDigest.getInstance("SHA-256")
                CloudArchive.write(archive, assetList, { items ->
                    require(items == inspected) { "备份时源文件发生变化，请重试" }
                    writeMetadata(portable, annotations, settings, group, backupAt, items, state.readingSeconds)
                        .toString().toByteArray(Charsets.UTF_8)
                }, archiveDigest, check)
                check()
                PendingUpload(revision, archive.length(), archiveDigest.digest().joinToString("") { "%02x".format(it) },
                    contentDigest, inspected)
                    .also { writePending(temporary, it) }
            }
            val revision = pending.revision
            val bytes = pending.archiveBytes
            transport.ensureCollection("")
            transport.ensureCollection("books/")
            transport.ensureCollection(directory)
            var done = 0L
            val parts = CloudArchive.parts(bytes, revision, pending.partBytes)
            for ((index, part) in parts.withIndex()) {
                check()
                val previous = done
                if (index >= pending.completedParts) {
                    transport.upload(directory + part.name, archive, done, part.bytes) {
                        check()
                        onProgress(CloudOperationProgress("上传《${book.title}》", (previous + it).toFloat() / bytes))
                    }
                    pending = pending.copy(completedParts = index + 1)
                    // Only a successful server response confirms a block for the next run.
                    writePendingProgress(temporary, pending)
                }
                done += part.bytes
                onProgress(CloudOperationProgress("上传《${book.title}》", done.toFloat() / bytes))
            }
            val cloudCover = book.coverPath?.let { cover ->
                check()
                val thumbnail = File(temporary, "cover.jpg")
                withLocalDocument(context, cover) { local ->
                    val bitmap = requireNotNull(BitmapLoader.decodeThumbnail(local)) { "封面无法读取" }
                    try { thumbnail.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
                    finally { bitmap.recycle() }
                }
                require(thumbnail.length() <= 4L * 1024 * 1024) { "封面过大" }
                val name = "$revision-cover.jpg"
                if (!pending.coverUploaded) {
                    transport.upload(directory + name, thumbnail, 0, thumbnail.length()) { check() }
                    pending = pending.copy(coverUploaded = true)
                    writePendingProgress(temporary, pending)
                }
                name
            }
            val info = CloudBookInfo(portable, annotations, settings, group, backupAt, revision, bytes, pending.archiveSha256, parts,
                state.readingSeconds, contentIdentity, sourceHash)
            check()
            onProgress(CloudOperationProgress("提交云端图书记录", 1f))
            // The old manifest remains valid until all parts and the cover are uploaded successfully.
            transport.putMetadata(directory + "manifest.json", writeManifest(info, inspected, cloudCover))
            cloudOperationMutex.withCatalog(credentials.identity) { publishCatalog(transport, info, check) }
            temporary.deleteRecursively()
            info
        } catch (error: Exception) {
            // Closing a cancelled socket can surface IOException; cancellation takes precedence.
            check()
            throw error
        } finally { cancellationWatcher.cancel(); transport.cancel() }
    } }

    suspend fun bookStorageBytes(ids: Set<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        val credentials = requireNotNull(credentialStore.read()) { "请先配置 WebDAV 网盘" }
        val work = currentCoroutineContext()
        val transport = transportFactory(credentials) {
            work.ensureActive()
            require(credentialStore.read()?.identity == credentials.identity) { "WebDAV 配置已更改，请重新打开删除确认" }
        }
        try {
            buildMap {
                for (id in ids) {
                    CloudArchive.safeId(id)
                    val bytes = transport.metadata("books/$id/manifest.json") ?: continue
                    val manifest = readManifest(bytes, id)
                    val coverBytes = manifest.cover?.let { transport.metadata("books/$id/$it", 4 * 1024 * 1024)?.size } ?: 0
                    put(id, manifest.info.archiveBytes + bytes.size + coverBytes)
                }
            }
        } finally { transport.cancel() }
    }

    suspend fun list(expectedIdentity: String? = null): List<CloudBookInfo> = list(expectedIdentity, includeCovers = true)

    suspend fun list(expectedIdentity: String?, includeCovers: Boolean): List<CloudBookInfo> =
        scanCatalog(expectedIdentity, includeCovers = includeCovers)

    suspend fun uploadCandidates(account: String, selected: List<Book>, linkedIds: Set<String>,
        verify: Boolean = false): List<CloudBookInfo> = withContext(Dispatchers.IO) {
        val books = selected.map { hydrateBookSourceIdentity(context, it) }
        scanCatalog(account, includeCovers = false, verify = verify) { entry ->
            entry.id in linkedIds || books.any(entry::matches)
        }
    }

    private suspend fun scanCatalog(expectedIdentity: String?, includeCovers: Boolean, verify: Boolean = true,
        candidate: ((CloudBookSummary) -> Boolean)? = null): List<CloudBookInfo> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val credentials = requireNotNull(credentialStore.read()) { "请先配置 WebDAV 服务器、账户与密码" }
        require(expectedIdentity == null || credentials.identity == expectedIdentity) { "WebDAV 配置已更改，请重新还原书籍信息" }
        val check = { job.ensureActive() }
        val transport = transportFactory(credentials, check)
        val cancellation = launch { try { awaitCancellation() } finally { transport.cancel() } }
        val cache = CloudMetadataCache(context, credentials.identity)
        onProgress(CloudOperationProgress("读取云端书架"))
        try {
            val saved = cache.read(transport, "catalog.json", "index", parse = CloudCatalog::read)
            val catalog = saved ?: CloudCatalog(legacyIds(transport))
            val ids = catalog.ids.filter { id ->
                verify || catalog.summaries[id]?.let { candidate?.invoke(it) } != false
            }
            val verifiedTags = if (verify && ids.size >= 4 && cache.hasBookRecords()) transport.manifestETags() else emptyMap()
            val covers = cloudCoverDirectory(credentials)
            val observed = LinkedHashMap<String, CloudBookSummary>()
            onProgress(CloudOperationProgress("读取云端书籍信息 0 / ${ids.size}", if (ids.isEmpty()) 1f else 0f))
            var done = 0
            val books = buildList {
                for (batch in ids.chunked(3)) {
                    val rows = coroutineScope {
                        batch.map { id -> async {
                            cloudMetadataReads.withPermit {
                                cache.read(transport, "books/$id/manifest.json", id, verifiedTags[id]) { bytes ->
                                    require(bytes.size <= CLOUD_METADATA_LIMIT) { "云端图书记录过大" }
                                    val json = repository.readCloudShelfManifest(bytes.inputStream().reader(Charsets.UTF_8))
                                    val row = manifestSummary(json, id)
                                    val info = if (candidate == null || candidate(row)) readManifest(json, id).info else null
                                    row to info
                                }
                            }
                        } }.awaitAll()
                    }
                    for (record in rows) {
                        if (record != null) {
                            val (row, info) = record
                            observed[row.id] = row
                            if (info != null) {
                                val file = coverFile(covers, info)
                                val cover = if (file?.isFile == true) file.absolutePath
                                    else if (includeCovers) readCloudCover(transport, covers, info, check) else null
                                add(info.copy(book = info.book.copy(coverPath = cover, cloudOnly = true,
                                    cloudEnabled = true, sourceUri = null, sourceLinked = false, cloudBackupAt = info.backupAt)))
                            }
                        }
                        done++
                        onProgress(CloudOperationProgress("读取云端书籍信息 $done / ${ids.size}", done.toFloat() / ids.size))
                    }
                }
            }
            require(credentialStore.read()?.identity == credentials.identity) { "WebDAV 配置已更改，请重新还原书籍信息" }
            if (verify && saved != null && observed.any { (id, row) -> saved.summaries[id]?.sameIdentity(row) != true }) {
                cloudOperationMutex.withCatalog(credentials.identity) {
                    try {
                        val latest = transport.versionedMetadata("catalog.json")
                        if (latest != null && CloudCatalog.read(latest.bytes) == saved) {
                            transport.putVersionedMetadata("catalog.json", saved.copy(summaries = saved.summaries + observed).write(), latest.etag)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
            }
            books
        } finally {
            cancellation.cancel()
            transport.cancel()
            cache.prune()
        }
    }

    internal suspend fun loadCloudCovers(account: String, books: List<CloudBookInfo>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            val credentials = credentialStore.read()?.takeIf { it.identity == account } ?: return@withContext emptyMap()
            val transport = transportFactory(credentials) { job.ensureActive() }
            val cancellation = launch { try { awaitCancellation() } finally { transport.cancel() } }
            val directory = cloudCoverDirectory(credentials)
            try {
                buildMap {
                    for (info in books) {
                        job.ensureActive()
                        val cover = try {
                            cloudMetadataReads.withPermit { readCloudCover(transport, directory, info) { job.ensureActive() } }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                        if (cover != null) put(info.book.id, cover)
                    }
                }
            } finally { cancellation.cancel(); transport.cancel() }
        }

    private fun coverFile(directory: File, info: CloudBookInfo): File? = info.cloudCover?.let {
        File(directory, "${info.book.id}-${info.revision}.jpg")
    }

    private fun readCloudCover(transport: CloudTransport, directory: File, info: CloudBookInfo, check: () -> Unit): String? {
        val file = coverFile(directory, info) ?: return null
        if (!file.isFile) {
            check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
            val partial = File(directory, "${info.book.id}-${UUID.randomUUID()}.tmp")
            try {
                transport.download("books/${info.book.id}/${info.cloudCover}", partial, false, 4L * 1024 * 1024) { check() }
                check(partial.renameTo(file) || file.isFile) { "封面保存失败" }
            } finally { partial.delete() }
        }
        return file.absolutePath
    }

    /** The caller may hold the short catalog lock while validating and merging a metadata snapshot. */
    suspend fun catalogBookIds(expectedIdentity: String): Set<String> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val credentials = requireNotNull(credentialStore.read()) { "请先配置 WebDAV 网盘" }
        require(credentials.identity == expectedIdentity) { "WebDAV 配置已更改，请重新还原书籍信息" }
        val transport = transportFactory(credentials) { job.ensureActive() }
        try {
            val index = CloudMetadataCache(context, credentials.identity).read(transport, "catalog.json", "index", parse = CloudCatalog::read)
            (index?.ids ?: legacyIds(transport)).toSet()
        }
        finally { transport.cancel() }
    }

    suspend fun deleteBook(bookId: String) = cloudOperationMutex.withBook(bookId) { withContext(Dispatchers.IO) {
        val id = CloudArchive.safeId(bookId)
        val job = currentCoroutineContext()
        val check = { job.ensureActive() }
        val credentials = requireNotNull(credentialStore.read()) { "请先配置 WebDAV 服务器、账户与密码" }
        val transport = transportFactory(credentials, check)
        cloudOperationMutex.withCatalog(credentials.identity) {
        onProgress(CloudOperationProgress("检查云端图书记录"))
        // Validate the catalog and its conditional version before deleting any book content.
        var catalog = transport.versionedMetadata("catalog.json")
        catalog?.let { readCatalog(it.bytes) }
        check()
        onProgress(CloudOperationProgress("删除云端图书备份"))
        transport.deleteCollection("books/$id/")
        repeat(4) {
            check()
            val current = catalog
            if (current == null || id !in readCatalog(current.bytes)) {
                discardPendingUpload(id)
                discardCloudCovers(id)
                return@withCatalog
            }
            val index = CloudCatalog.read(current.bytes)
            val bytes = index.copy(ids = index.ids - id, summaries = index.summaries - id).write()
            onProgress(CloudOperationProgress("更新云端书籍索引"))
            if (transport.putVersionedMetadata("catalog.json", bytes, current.etag)) {
                discardPendingUpload(id)
                discardCloudCovers(id)
                return@withCatalog
            }
            catalog = transport.versionedMetadata("catalog.json")
        }
        error("其他设备正在更新云端书籍索引，请重新删除云端备份")
        }
    } }

    /** Update authoritative reading records without transferring or deleting the book's content archive. */
    suspend fun clearBookRecords(bookId: String, kind: com.eyue.reader.data.LibraryCleanupKind): CloudBookInfo? = cloudOperationMutex.withBook(bookId) { withContext(Dispatchers.IO) {
        val id = CloudArchive.safeId(bookId)
        if (kind == com.eyue.reader.data.LibraryCleanupKind.AI_AUDIO) return@withContext null
        val job = currentCoroutineContext()
        val transport = transport { job.ensureActive() }
        val cancellation = launch { try { awaitCancellation() } finally { transport.cancel() } }
        try {
            val path = "books/$id/manifest.json"
            repeat(4) {
                job.ensureActive()
                val current = transport.versionedMetadata(path) ?: return@withContext null
                val manifest = readManifest(current.bytes, id)
                val old = manifest.info
                val clean = com.eyue.reader.data.cleanLibraryRecords(com.eyue.reader.data.LibraryState(
                    books = listOf(old.book), annotations = old.annotations), kind)
                val updated = old.copy(book = clean.books.single(), annotations = clean.annotations, backupAt = System.currentTimeMillis())
                if (transport.putVersionedMetadata(path, writeManifest(updated, manifest.assets, manifest.cover), current.etag)) return@withContext updated
            }
            error("其他设备正在更新云端阅读记录，请重新清理")
        } finally { cancellation.cancel(); transport.cancel() }
    } }

    private fun discardCloudCovers(bookId: String) {
        val credentials = credentialStore.read() ?: return
        val covers = cloudCoverDirectory(credentials).canonicalFile
        val stamp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val owned = Regex("${Regex.escape(bookId)}-(?:backup-$stamp\\.jpg|$stamp\\.tmp)")
        covers.listFiles()?.filter { it.isFile && owned.matches(it.name) && it.canonicalFile.parentFile == covers }
            ?.forEach { check(it.delete() || !it.exists()) { "云端备份已删除，本地封面缓存清理失败" } }
    }

    suspend fun download(info: CloudBookInfo): CloudRestoredBook = download(info.book.id)

    suspend fun download(bookId: String): CloudRestoredBook = cloudOperationMutex.withBook(bookId) { withContext(Dispatchers.IO) {
        CloudArchive.safeId(bookId)
        val job = currentCoroutineContext()
        val check = { job.ensureActive() }
        val transport = transport(check)
        val directory = "books/$bookId/"
        val manifest = readManifest(requireNotNull(transport.metadata(directory + "manifest.json")) { "云端图书信息不存在" }, bookId)
        val info = manifest.info
        val archive = File.createTempFile("cloud-download-", ".zip", context.cacheDir)
        val parent = File(context.filesDir, "books/$bookId").canonicalFile
        val books = File(context.filesDir, "books").canonicalFile
        require(parent.parentFile == books)
        check(parent.mkdirs() || parent.isDirectory)
        val staging = File(parent, ".restore-${UUID.randomUUID()}")
        val restored = File(parent, "cloud-${UUID.randomUUID()}")
        try {
            var done = 0L
            for (part in info.parts) {
                check()
                val previous = done
                val count = transport.download(directory + part.name, archive, done > 0, part.bytes) {
                    check()
                    onProgress(CloudOperationProgress("下载《${info.book.title}》", (previous + it).toFloat() / info.archiveBytes))
                }
                require(count == part.bytes) { "云端备份分块不完整" }
                done += count
            }
            require(archive.length() == info.archiveBytes && CloudArchive.hash(archive, check) == info.archiveSha256) { "云端备份校验失败，未更改本地书籍" }
            onProgress(CloudOperationProgress("还原《${info.book.title}》的内容和记录"))
            val metadata = CloudArchive.extract(archive, staging, manifest.assets, check)
            val parsed = readMetadata(JSONObject(metadata.toString(Charsets.UTF_8)))
            require(parsed.book.id == bookId && contentBook(parsed.book) == contentBook(info.book)) { "备份图书内容不匹配" }
            require(readAssets(JSONObject(metadata.toString(Charsets.UTF_8))) == manifest.assets) { "备份文件清单不匹配" }
            check()
            check(staging.renameTo(restored)) { "图书内容保存失败" }
            val assetIndex = manifest.assets.associateBy { it.name }
            fun path(relative: String?): String? {
                if (relative == null) return null
                val record = assetIndex[relative] ?: error("备份引用了不存在的文件")
                val file = File(restored, CloudArchive.safeName(record.name)).canonicalFile
                require(file.path.startsWith(restored.canonicalPath + File.separator) && file.isFile)
                return file.absolutePath
            }
            val book = remapBook(info.book, ::path).copy(sourcePath = path(info.book.sourcePath), sourceUri = null,
                sourceLinked = false, cloudOnly = false, cloudEnabled = true, cloudBackupAt = info.backupAt)
            val annotations = info.annotations.map { it.copy(imagePath = path(it.imagePath)) }
            val fontDirectory = File(context.filesDir, "fonts").apply { check(mkdirs() || isDirectory) }
            val fontPaths = HashMap<String, String>()
            fun font(relative: String?): String? {
                if (relative == null) return null
                return fontPaths.getOrPut(relative) {
                    val record = assetIndex[relative] ?: error("备份字体缺失")
                    val extension = relative.substringAfterLast('.', "ttf")
                    val settingDirectory = if (extension in setOf("jpg", "jpeg", "png", "webp"))
                        File(context.filesDir, "backgrounds").apply { check(mkdirs() || isDirectory) } else fontDirectory
                    val destination = File(settingDirectory, "cloud-${record.sha256}.$extension")
                    if (!destination.exists()) {
                        val partial = File.createTempFile("restore-setting-", ".tmp", settingDirectory)
                        try {
                            File(requireNotNull(path(relative))).inputStream().use { input -> partial.outputStream().use { output ->
                                CloudArchive.copy(input, output, record.bytes, check)
                            } }
                            if (!partial.renameTo(destination)) {
                                // Another book may have restored the same content-addressed font/background.
                                check(destination.isFile && destination.length() == record.bytes &&
                                    CloudArchive.hash(destination, check) == record.sha256) { "阅读资源还原失败" }
                            }
                        } finally { partial.delete() }
                    }
                    destination.absolutePath
                }
            }
            val settings = remapSettings(info.settings, ::font)
            CloudRestoredBook(CloudDownloadLocation(context).publish(book, check), annotations, settings, info.group, info.backupAt, info.readingSeconds)
        } catch (error: Throwable) {
            // This directory belongs solely to this operation; previously readable files are untouched.
            if (staging.exists()) staging.deleteRecursively()
            if (restored.exists()) restored.deleteRecursively()
            throw error
        } finally { archive.delete() }
    } }

    private fun transport(check: () -> Unit): CloudTransport = transportFactory(
        requireNotNull(credentialStore.read()) { "请先配置 WebDAV 服务器、账户与密码" }, check)

    private fun cloudCoverDirectory(credentials: CloudCredentials): File {
        val root = File(context.filesDir, "cloud-covers")
        return File(root, digest(credentials.identity))
    }

    fun discardPendingUpload(bookId: String) {
        CloudArchive.safeId(bookId)
        val credentials = credentialStore.read() ?: return
        val root = File(context.filesDir, "cloud-pending").canonicalFile
        val bookDirectory = File(root, bookId).canonicalFile
        val directory = pendingDirectory(bookId, credentials.identity)
        require(bookDirectory.parentFile == root && directory.parentFile == bookDirectory)
        directory.deleteRecursively()
        if (bookDirectory.isDirectory && bookDirectory.list()?.isEmpty() == true) bookDirectory.delete()
    }

    private fun pendingDirectory(bookId: String, account: String): File {
        val root = File(context.filesDir, "cloud-pending").canonicalFile
        val bookDirectory = File(root, CloudArchive.safeId(bookId)).canonicalFile
        val directory = File(bookDirectory, digest(account)).canonicalFile
        require(bookDirectory.parentFile == root && directory.parentFile == bookDirectory)
        return directory
    }

    private data class PendingUpload(val revision: String, val archiveBytes: Long, val archiveSha256: String,
                                     val contentDigest: String, val assets: List<CloudAssetRecord>,
                                     val completedParts: Int = 0, val coverUploaded: Boolean = false,
                                     val partBytes: Long = CLOUD_PART_BYTES)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun readPending(directory: File): PendingUpload? {
        val file = AtomicFile(File(directory, "checkpoint.json"))
        if (!file.baseFile.exists() && !File(directory, "checkpoint.json.bak").exists()) return null
        return try {
            val bytes = file.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                CloudArchive.copy(input, output, CLOUD_METADATA_LIMIT.toLong())
                output.toByteArray()
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val version = json.getInt("version")
            require(version in 1..2)
            val revision = CloudArchive.safeId(json.getString("revision"))
            val archiveBytes = json.getLong("archiveBytes")
            require(archiveBytes in 1..CLOUD_ARCHIVE_LIMIT)
            val archiveSha256 = json.getString("archiveSha256")
            val contentDigest = json.getString("contentDigest")
            require(listOf(archiveSha256, contentDigest).all { it.matches(Regex("[a-f0-9]{64}")) })
            val completed = json.getInt("completedParts")
            val partBytes = if (version == 1) 4L * 1024 * 1024 else json.getLong("partBytes")
            require(partBytes == CLOUD_PART_BYTES || partBytes == 4L * 1024 * 1024)
            val partCount = CloudArchive.parts(archiveBytes, revision, partBytes).size
            require(completed in 0..partCount)
            val base = PendingUpload(revision, archiveBytes, archiveSha256, contentDigest, readAssets(json), completed,
                json.getBoolean("coverUploaded"), partBytes)
            val progress = runCatching {
                JSONObject(AtomicFile(File(directory, "progress.json")).readFully().decodeToString())
            }.getOrNull()
            val saved = progress?.optInt("completedParts", -1) ?: -1
            if (progress?.optString("revision") == revision && saved in completed..partCount)
                base.copy(completedParts = saved, coverUploaded = base.coverUploaded || progress.optBoolean("coverUploaded"))
            else base
        } catch (_: Exception) { null }
    }

    private fun writePending(directory: File, pending: PendingUpload) {
        val bytes = JSONObject().put("version", 2).put("revision", pending.revision).put("partBytes", pending.partBytes)
            .put("archiveBytes", pending.archiveBytes).put("archiveSha256", pending.archiveSha256)
            .put("contentDigest", pending.contentDigest).put("completedParts", pending.completedParts)
            .put("coverUploaded", pending.coverUploaded).put("assets", JSONArray(pending.assets.map {
                JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256)
            })).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= CLOUD_METADATA_LIMIT) { "图书断点记录过大" }
        writeCheckpoint(File(directory, "checkpoint.json"), bytes)
    }

    private fun writePendingProgress(directory: File, pending: PendingUpload) {
        val bytes = JSONObject().put("revision", pending.revision).put("completedParts", pending.completedParts)
            .put("coverUploaded", pending.coverUploaded).toString().toByteArray(Charsets.UTF_8)
        writeCheckpoint(File(directory, "progress.json"), bytes)
    }

    private fun writeCheckpoint(path: File, bytes: ByteArray) {
        val file = AtomicFile(path)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private fun readCatalog(bytes: ByteArray): List<String> = CloudCatalog.read(bytes).ids

    private fun legacyIds(transport: CloudTransport): List<String> = transport.collections("books/").also {
        // Jianguoyun limits one PROPFIND response to 750 entries including the parent.
        require(it.size < 749) { "云端旧书籍目录达到列举上限，无法保证完整还原" }
    }

    private fun preflightCatalog(transport: CloudTransport, bookId: String) {
        val bytes = transport.metadata("catalog.json")
        if (bytes != null && bookId in readCatalog(bytes)) return
        val versioned = transport.versionedMetadata("catalog.json")
        if (versioned != null) {
            val ids = readCatalog(versioned.bytes)
            require(bookId in ids || ids.size < 10_000) { "云端书籍数量超过限制" }
        }
    }

    private fun publishCatalog(transport: CloudTransport, info: CloudBookInfo, check: () -> Unit) {
        val entry = CloudBookSummary.from(info)
        var registered = false
        repeat(4) {
            check()
            val remote = transport.readMetadata("catalog.json", null)
            val snapshot = remote.bytes?.let(CloudCatalog::read)
            registered = snapshot?.ids?.contains(entry.id) == true
            val previous = snapshot?.summaries?.get(entry.id)
            // A progress-only write leaves the catalogue unchanged. Manifest ETags validate the records.
            if (previous != null && previous.sameIdentity(entry)) return
            val tag = runCatching { cloudStrongETag(remote.etag) }.getOrNull()
            val current = try {
                if (tag != null && remote.bytes != null) CloudVersionedMetadata(remote.bytes, tag)
                else transport.versionedMetadata("catalog.json")
            } catch (error: IllegalArgumentException) {
                if (registered) return
                throw error
            }
            val catalog = current?.let { CloudCatalog.read(it.bytes) } ?: CloudCatalog(legacyIds(transport))
            val ids = (catalog.ids + entry.id).distinct()
            require(ids.size <= 10_000) { "云端书籍数量超过限制" }
            val updated = catalog.copy(ids = ids, summaries = catalog.summaries + (entry.id to entry)).write()
            try {
                if (transport.putVersionedMetadata("catalog.json", updated, current?.etag)) return
            } catch (error: CloudHttpException) {
                // An existing record is already committed; a summary remains an optional lookup hint.
                if (registered && !error.retryable) return
                throw error
            }
        }
        if (registered) return
        error("其他设备正在更新云端书籍索引，请稍后重新上传")
    }

    private fun remapBook(book: Book, path: (String?) -> String?): Book {
        val cover = path(book.coverPath)
        val chapters = book.chapters.map { chapter -> chapter.copy(textPath = path(chapter.textPath),
            pages = chapter.pages.map { it.copy(path = requireNotNull(path(it.path)), textPath = path(it.textPath)) },
            inlineImages = chapter.inlineImages.map { it.copy(path = requireNotNull(path(it.path))) },
            sourceFootnotes = chapter.sourceFootnotes.map { it.copy(imagePath = path(it.imagePath)) }) }
        return book.copy(coverPath = cover, chapters = chapters, positionImagePath = path(book.positionImagePath))
    }

    private fun remapSettings(settings: ReaderSettings, path: (String?) -> String?): ReaderSettings {
        val fonts = settings.fonts.map { ImportedFont(it.name, requireNotNull(path(it.path))) }
        return settings.copy(fonts = fonts, fontPath = path(settings.fontPath), backgroundImagePath = path(settings.backgroundImagePath),
            appearanceProfiles = settings.appearanceProfiles.map { it.mapPaths(path) })
    }

    private fun contentBook(book: Book): Book = book.copy(title = "", author = "", chapterIndex = 0, pageIndex = 0,
        characterOffset = 0, progress = 0f, lastReadAt = 0, addedAt = 0, isDemo = false, positionImagePath = null,
        groupId = null, cloudEnabled = false, cloudOnly = false, cloudBackupAt = 0, voiceRoles = emptyList(),
        sourceFileName = null, sourceFileBytes = null)

    private fun writeManifest(info: CloudBookInfo, records: List<CloudAssetRecord>, cover: String?): ByteArray =
        writeMetadata(info.book, info.annotations, info.settings, info.group, info.backupAt, records, info.readingSeconds)
            .put("revision", info.revision).put("archiveBytes", info.archiveBytes).put("archiveSha256", info.archiveSha256)
            .put("contentIdentity", info.contentIdentity)
            .put("parts", JSONArray(info.parts.map { JSONObject().put("name", it.name).put("bytes", it.bytes) }))
            .put("cloudCover", cover).toString().toByteArray(Charsets.UTF_8)

    private fun writeMetadata(book: Book, annotations: List<Annotation>, settings: ReaderSettings, group: ShelfGroup?, backupAt: Long,
                              records: List<CloudAssetRecord>, readingSeconds: Long): JSONObject = JSONObject().put("version", 1)
        .put("book", repository.writeBook(book)).put("annotations", JSONArray(annotations.map(repository::writeAnnotation)))
        .put("settings", repository.writeSettings(settings)).put("backupAt", backupAt).put("readingSeconds", readingSeconds)
        .put("group", group?.let { JSONObject().put("id", it.id).put("name", it.name).put("mode", it.mode.name) })
        .put("assets", JSONArray(records.map { JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256) }))

    private fun readMetadata(json: JSONObject): CloudRestoredBook {
        require(json.getInt("version") == 1) { "云端备份版本不受支持" }
        val book = repository.readBook(json.getJSONObject("book"))
        CloudArchive.safeId(book.id)
        require(book.sourceUri == null && !book.sourceLinked && book.chapters.isNotEmpty()) { "云端图书记录损坏" }
        require(book.progress.isFinite() && book.progress in 0f..1f && book.chapterIndex in book.chapters.indices &&
            book.pageIndex >= 0 && book.characterOffset >= 0) { "云端阅读进度无效" }
        val array = json.getJSONArray("annotations")
        val annotations = (0 until array.length()).map { repository.readAnnotation(array.getJSONObject(it)) }
        require(annotations.all { it.bookId == book.id && it.chapterIndex in book.chapters.indices && it.characterOffset >= 0 && it.pageIndex >= 0 }) { "云端标注记录损坏" }
        val group = json.optJSONObject("group")?.let {
            ShelfGroup(it.getString("name"), LibraryMode.valueOf(it.getString("mode")), it.getString("id"))
        }
        require(group == null || group.mode == book.mode) { "云端分组记录损坏" }
        val defaults = if (book.mode == LibraryMode.COMIC) ReaderSettings().withDefaultComicMargins() else ReaderSettings()
        val settings = repository.readSettings(json.getJSONObject("settings"), defaults)
        val backupAt = json.getLong("backupAt")
        require(backupAt > 0)
        val readingSeconds = json.optLong("readingSeconds", 0)
        require(readingSeconds >= 0)
        return CloudRestoredBook(book, annotations, settings, group, backupAt, readingSeconds)
    }

    private data class Manifest(val info: CloudBookInfo, val assets: List<CloudAssetRecord>, val cover: String?)

    private fun manifestSummary(json: JSONObject, id: String): CloudBookSummary {
        require(json.getInt("version") == 1) { "云端备份版本不受支持" }
        val book = json.getJSONObject("book")
        require(book.getString("id") == id) { "云端图书标识不匹配" }
        val fileName = book.optString("sourceFileName").takeIf { !book.isNull("sourceFileName") && it.isNotBlank() }
        var size = book.optLong("sourceFileBytes", -1).takeIf { it >= 0 }
        if (size == null && fileName != null && book.optString("format") != BookFormat.IMAGES.name) {
            val assets = json.getJSONArray("assets")
            val source = book.optString("sourcePath")
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.getString("name") == source) {
                    size = asset.getLong("bytes").takeIf { it >= 0 }
                    break
                }
            }
        }
        val mode = LibraryMode.entries.firstOrNull { it.name == book.optString("mode") } ?: LibraryMode.NOVEL
        return CloudBookSummary(id, book.getString("title"), mode, fileName, size, CloudArchive.safeId(json.getString("revision")),
            json.getLong("backupAt"))
    }

    private fun readManifest(bytes: ByteArray, id: String): Manifest {
        require(bytes.size <= CLOUD_METADATA_LIMIT) { "云端图书记录过大" }
        return readManifest(JSONObject(bytes.decodeToString()), id)
    }

    private fun readManifest(json: JSONObject, id: String): Manifest {
        val data = readMetadata(json)
        require(data.book.id == id) { "云端图书标识不匹配" }
        val revision = json.getString("revision")
        CloudArchive.safeId(revision)
        val array = json.getJSONArray("parts")
        require(array.length() in 1..CLOUD_PART_LIMIT)
        val parts = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            CloudArchivePart(item.getString("name"), item.getLong("bytes")).also {
                require(it.name == "$revision-${index.toString().padStart(4, '0')}.bin" && it.bytes in 1..CLOUD_LEGACY_PART_BYTES) { "云端分块记录损坏" }
            }
        }
        val archiveBytes = json.getLong("archiveBytes")
        val sha256 = json.getString("archiveSha256")
        require(archiveBytes in 1..CLOUD_ARCHIVE_LIMIT && parts.sumOf { it.bytes } == archiveBytes && sha256.matches(Regex("[a-f0-9]{64}"))) { "云端备份校验信息损坏" }
        val assets = readAssets(json)
        val names = assets.map { it.name }.toHashSet()
        fun relative(path: String?): String? = path?.also { require(it in names) { "云端记录含有无效文件路径" } }
        remapBook(data.book, ::relative)
        relative(data.book.sourcePath)
        data.annotations.forEach { relative(it.imagePath) }
        remapSettings(data.settings, ::relative)
        val cover = if (json.isNull("cloudCover")) null else json.optString("cloudCover").takeIf { it.isNotEmpty() }
        require(cover == null || cover == "$revision-cover.jpg") { "云端封面路径无效" }
        val identity = if (json.isNull("contentIdentity")) null else json.optString("contentIdentity").takeIf { it.isNotEmpty() }
        require(identity == null || identity.matches(Regex("(?:text|pages)-v1:[a-f0-9]{64}"))) { "云端正文校验信息损坏" }
        val sourceBytes = assets.firstOrNull { it.name == data.book.sourcePath }?.bytes
        val identified = if (data.book.sourceFileBytes == null && data.book.format != BookFormat.IMAGES)
            data.book.copy(sourceFileBytes = sourceBytes) else data.book
        return Manifest(CloudBookInfo(identified, data.annotations, data.settings, data.group, data.backupAt,
            revision, archiveBytes, sha256, parts, data.readingSeconds, identity,
            assets.firstOrNull { it.name == data.book.sourcePath }?.sha256, cloudCover = cover), assets, cover)
    }

    private fun readAssets(json: JSONObject): List<CloudAssetRecord> {
        val array = json.getJSONArray("assets")
        require(array.length() < CLOUD_ENTRY_LIMIT)
        val assets = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            CloudAssetRecord(CloudArchive.safeName(item.getString("name")), item.getLong("bytes"), item.getString("sha256"))
        }
        require(assets.map { it.name }.toSet().size == assets.size && assets.all { it.name.startsWith("assets/") && it.bytes >= 0 && it.sha256.matches(Regex("[a-f0-9]{64}")) }) { "云端文件清单损坏" }
        require(assets.fold(0L) { total, record -> Math.addExact(total, record.bytes) } <= CLOUD_ARCHIVE_LIMIT)
        return assets
    }
}
