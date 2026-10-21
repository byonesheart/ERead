package com.eyue.reader.ui

import android.graphics.Bitmap
import android.os.Build
import android.system.Os
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.eyue.reader.data.BitmapLoader
import com.eyue.reader.data.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal data class BookCoverKey(val path: String, val contentVersion: Int, val cloudOnly: Boolean)

/** Shared by the home and shelf. Eviction releases our reference, never a bitmap still being drawn. */
internal class BookCoverCache(
    maximumBytes: Int = minOf(Runtime.getRuntime().maxMemory() / 32, 8L * 1024 * 1024).toInt().coerceAtLeast(1),
    private val decode: (File) -> Bitmap? = BitmapLoader::decodeThumbnail,
    private val version: (File) -> String? = ::bookCoverVersion
) {
    private data class Entry(val version: String, val bitmap: Bitmap)
    private val cache = object : LruCache<BookCoverKey, Entry>(maximumBytes) {
        override fun sizeOf(key: BookCoverKey, value: Entry): Int = value.bitmap.allocationByteCount.coerceAtLeast(1)
    }
    private val loads = Array(16) { Mutex() }

    fun peek(key: BookCoverKey?): Bitmap? = key?.let { cache.get(it)?.bitmap?.takeUnless(Bitmap::isRecycled) }

    suspend fun load(key: BookCoverKey?): Bitmap? = withContext(Dispatchers.IO) {
        if (key == null) return@withContext null
        loads[(key.path.hashCode() and Int.MAX_VALUE) % loads.size].withLock {
            try {
                val file = File(key.path)
                val before = version(file) ?: run { invalidate(key.path); return@withLock null }
                cache.get(key)?.takeIf { it.version == before && !it.bitmap.isRecycled }?.let { return@withLock it.bitmap }
                val localKey = key.copy(cloudOnly = false)
                val local = cache.get(localKey)?.takeIf { it.version == before && !it.bitmap.isRecycled }?.bitmap
                    ?: decode(file)?.also { bitmap ->
                        currentCoroutineContext().ensureActive()
                        if (version(file) == before) cache.put(localKey, Entry(before, bitmap))
                    } ?: run { invalidate(key.path); return@withLock null }
                val result = if (key.cloudOnly) cloudBookCover(local) else local
                currentCoroutineContext().ensureActive()
                if (version(file) != before) { invalidate(key.path); return@withLock null }
                cache.put(key, Entry(before, result))
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { invalidate(key.path); null }
        }
    }

    private fun invalidate(path: String) {
        cache.snapshot().keys.filter { it.path == path }.forEach(cache::remove)
    }
}

private fun bookCoverVersion(file: File): String? {
    if (!file.isFile) return null
    val stat = Os.stat(file.path)
    return if (Build.VERSION.SDK_INT >= 27) {
        "${file.absolutePath}:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${file.lastModified()}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
    } else "${file.absolutePath}:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${file.lastModified()}:${stat.st_ctime}"
}

internal val LocalBookCoverCache = staticCompositionLocalOf { SharedBookCoverCache }
private val SharedBookCoverCache = BookCoverCache()

@Composable
internal fun rememberBookCoverBitmap(book: Book): State<Bitmap?> {
    val cache = LocalBookCoverCache.current
    val key = remember(book.coverPath, book.contentVersion, book.cloudOnly) {
        book.coverPath?.let { BookCoverKey(it, book.contentVersion, book.cloudOnly) }
    }
    // A warm remount starts with the image, rather than one frame of its placeholder.
    val bitmap = remember(cache, key) { mutableStateOf(cache.peek(key)) }
    LaunchedEffect(cache, key) { bitmap.value = cache.load(key) }
    return bitmap
}
