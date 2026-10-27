package com.eyue.reader.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.eyue.reader.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookLocationRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager = "com.android.documentsui"
    private val book get() = Book(title = "位置", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
        chapters = emptyList(), sourcePath = File(Environment.getExternalStorageDirectory(), "Books/中文 空格/位置.epub").absolutePath)

    @Test fun localFileIsAnActualViewTargetRatherThanOnlyAnInitialLocationHint() {
        val document = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Books/中文 空格/位置.epub")
        registerManager(document)
        val intent = requireNotNull(bookFileManagerIntent(context, book))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, intent.type)
        assertEquals(document, intent.data)
        assertEquals(ComponentName(manager, "$manager.FilesActivity"), intent.component)
        assertFalse(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        assertEquals(document, shadowOf(context).nextStartedActivity.data)
    }

    @Test fun localOriginalRemainsReachableWhenEpubHasAPrivateWorkingCopy() {
        // Use an Android file URI; Uri.fromFile on the Windows test host embeds a drive letter.
        ShadowEnvironment.setExternalStorageDirectory(File("/storage/emulated/0").canonicalFile.toPath())
        val original = Uri.parse("file:///storage/emulated/0/小说/原书.mobi")
        val document = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:小说/原书.mobi")
        registerManager(document)
        val intent = requireNotNull(bookFileManagerIntent(context, book.copy(sourceUri = original.toString(),
            sourcePath = File(context.filesDir, "books/id/source.mobi").absolutePath)))
        assertEquals(document, intent.data)
    }

    @Test fun documentProviderFileIdsAreKeptIntactForTheSystemNavigatorToResolveTheirParent() {
        val authority = "com.android.providers.downloads.documents"
        registerProvider(authority)
        val document = DocumentsContract.buildDocumentUri(authority, "msf:471")
        registerManager(document)
        val intent = requireNotNull(bookFileManagerIntent(context, book.copy(sourceUri = document.toString(),
            sourcePath = File(context.filesDir, "books/source.epub").absolutePath)))
        assertEquals(document, intent.data)
    }

    @Test fun removableStorageDocumentUrisKeepTheirVolumeAndEscapedNames() {
        val authority = "com.android.externalstorage.documents"
        registerProvider(authority)
        val document = DocumentsContract.buildDocumentUri(authority, "12AB-34CD:小说/书名 #1.epub")
        registerManager(document)
        assertEquals(document, requireNotNull(bookFileManagerIntent(context, book.copy(sourceUri = document.toString()))).data)
    }

    @Test fun comicTreeUriOpensTheSelectedFolderAndNotTheStorageRoot() {
        val authority = "com.android.externalstorage.documents"
        registerProvider(authority)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "primary:漫画/第一册")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        registerManager(document)
        val intent = requireNotNull(bookFileManagerIntent(context, book.copy(format = BookFormat.IMAGES,
            mode = LibraryMode.COMIC, sourceUri = tree.toString(), sourcePath = null)))
        assertEquals(document, intent.data)
    }

    @Test fun documentInsideTreeKeepsTheSelectedDocumentInsteadOfJumpingToTheTreeRoot() {
        val authority = "com.android.externalstorage.documents"
        registerProvider(authority)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "primary:小说")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:小说/作者/书.epub")
        registerManager(document)
        assertEquals(document, requireNotNull(bookFileManagerIntent(context, book.copy(sourceUri = document.toString()))).data)
    }

    @Test fun readerAndGenericViewersCannotInterceptFolderNavigation() {
        val document = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Books/中文 空格/位置.epub")
        registerManager(document, context.packageName)
        registerManager(document, "generic.viewer", picker = false, system = false)
        registerManager(document)
        assertEquals(manager, requireNotNull(bookFileManagerIntent(context, book)).component?.packageName)
    }

    @Test fun unsupportedNavigationIsDisabledInsteadOfOpeningAnUnrelatedPickerRoot() {
        assertNull(bookFileManagerIntent(context, book))
        val document = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Books/中文 空格/位置.epub")
        registerManager(document, context.packageName)
        assertNull(bookFileManagerIntent(context, book))
    }

    @Test fun privateCloudAndDemoLocationsAreNeverExposedToOtherApps() {
        assertNull(bookFileManagerIntent(context, book.copy(sourcePath = File(context.filesDir, "books/private.epub").absolutePath)))
        assertNull(bookFileManagerIntent(context, book.copy(cloudOnly = true)))
        assertNull(bookFileManagerIntent(context, book.copy(isDemo = true)))
    }

    private fun registerProvider(authority: String) {
        val component = ComponentName(authority, "$authority.Provider")
        shadowOf(context.packageManager).addProviderIfNotPresent(component).apply {
            this.authority = authority
            exported = true
            grantUriPermissions = true
        }
        shadowOf(context.packageManager).addIntentFilterForProvider(component, IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
    }

    private fun registerManager(document: Uri, name: String = manager, picker: Boolean = true, system: Boolean = true) {
        val activity = ResolveInfo().apply {
            isDefault = true
            activityInfo = ActivityInfo().apply {
                packageName = name
                this.name = "$name.FilesActivity"
                exported = true
                applicationInfo = ApplicationInfo().apply {
                    packageName = name
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            }
        }
        val packages = shadowOf(context.packageManager)
        packages.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW).setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR), activity)
        if (picker) packages.addResolveInfoForIntent(Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), activity)
    }
}
