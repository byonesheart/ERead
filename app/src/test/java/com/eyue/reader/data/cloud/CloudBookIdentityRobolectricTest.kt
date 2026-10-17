package com.eyue.reader.data.cloud

import android.content.Context
import com.eyue.reader.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudBookIdentityRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "identity-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun novel(vararg text: String) = Book(title = "同名书", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        chapters = text.map { Chapter(title = "章", textPath = File(root, "${UUID.randomUUID()}.txt").apply { writeText(it) }.absolutePath) })
    private fun info(book: Book, identity: String? = null, sourceHash: String? = null) =
        CloudBookInfo(book, emptyList(), ReaderSettings(), null, 1, "rev", 1, "hash", emptyList(),
            contentIdentity = identity, sourceSha256 = sourceHash)

    @Test fun textSignatureIgnoresWhitespaceAndChapterSplittingButKeepsUnicodeAndContent() {
        val original = novel("标题\n甲乙😀\n丙丁")
        val split = novel("标题\n\n甲乙😀", "  丙丁\n")
        assertEquals(cloudBookContentIdentity(context, original), cloudBookContentIdentity(context, split))
        assertNotEquals(cloudBookContentIdentity(context, original), cloudBookContentIdentity(context, novel("标题甲乙😀丙戊")))
    }

    @Test fun sameContentAndDifferentContentAreDecidedWithoutReadingUnavailableCloudPaths() = runBlocking {
        val local = novel("正文内容")
        val remote = local.copy(id = "remote", chapters = listOf(Chapter(title = "章", textPath = "assets/000001.txt")))
        val service = CloudBackupService(context)
        val same = service.restoreBookMatches(LibraryState(books = listOf(local)), listOf(info(remote, cloudBookContentIdentity(context, local))))
        assertEquals(CloudBookMatch.SAME, same[local.id to remote.id])
        val different = service.restoreBookMatches(LibraryState(books = listOf(local)), listOf(info(remote, cloudBookContentIdentity(context, novel("其他正文")))))
        assertEquals(CloudBookMatch.DIFFERENT, different[local.id to remote.id])
    }

    @Test fun oldManifestUsesExactOriginalHashOrRemainsUnknownWhenEvidenceIsMissing() = runBlocking {
        val source = File(root, "original.mobi").apply { writeText("original bytes") }
        val local = novel("正文").copy(sourcePath = source.absolutePath)
        val remote = local.copy(id = "remote", sourcePath = "assets/000001.mobi")
        val service = CloudBackupService(context)
        val known = service.restoreBookMatches(LibraryState(books = listOf(local)), listOf(info(remote, sourceHash = cloudSourceHash(context, source.absolutePath))))
        assertEquals(CloudBookMatch.SAME, known[local.id to remote.id])
        val unknown = service.restoreBookMatches(LibraryState(books = listOf(local)), listOf(info(remote)))
        assertEquals(CloudBookMatch.UNKNOWN, unknown[local.id to remote.id])
    }

    @Test fun editedCachedTextOverridesMatchingOriginalFileForDuplicateDetection() = runBlocking {
        val source = File(root, "original.mobi").apply { writeText("unchanged original MOBI bytes") }
        val original = novel("修改前的正文").copy(format = BookFormat.MOBI, sourcePath = source.absolutePath)
        val edited = novel("修改后的正文").copy(format = BookFormat.MOBI, sourcePath = source.absolutePath)
        val remote = original.copy(id = "remote", sourcePath = "assets/000001.mobi")
        val matches = CloudBackupService(context).restoreBookMatches(LibraryState(books = listOf(edited)),
            listOf(info(remote, cloudBookContentIdentity(context, original), cloudSourceHash(context, source.absolutePath))))
        assertEquals(CloudBookMatch.DIFFERENT, matches[edited.id to remote.id])
        val unknown = CloudBackupService(context).restoreBookMatches(LibraryState(books = listOf(edited.copy(hasTextEdits = true))),
            listOf(info(remote, sourceHash = cloudSourceHash(context, source.absolutePath))))
        assertEquals(CloudBookMatch.UNKNOWN, unknown[edited.id to remote.id])
    }

    @Test fun comicSignatureIgnoresFolderNamesButIncludesPageOrderAndPdfIndices() {
        val first = File(root, "first.pdf").apply { writeText("same PDF bytes") }
        val copy = File(root, "copy.pdf").apply { writeBytes(first.readBytes()) }
        val book = Book(title = "漫画", format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            chapters = listOf(Chapter(title = "一", pages = listOf(PageSource(first.absolutePath, 0), PageSource(first.absolutePath, 1)))))
        val renamed = book.copy(chapters = listOf(Chapter(title = "不同名字", pages = listOf(PageSource(copy.absolutePath, 0), PageSource(copy.absolutePath, 1)))))
        assertEquals(cloudBookContentIdentity(context, book), cloudBookContentIdentity(context, renamed))
        assertNotEquals(cloudBookContentIdentity(context, book), cloudBookContentIdentity(context,
            renamed.copy(chapters = renamed.chapters.map { it.copy(pages = it.pages.reversed()) })))
        assertNull(cloudBookContentIdentity(context, novel(" ")))
    }
}
