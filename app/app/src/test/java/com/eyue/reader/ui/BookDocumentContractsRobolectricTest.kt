package com.eyue.reader.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookDocumentContractsRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source = Uri.parse("content://books.provider/document/original.epub")

    @Test fun importingRequestsPersistentReadAndWriteAndKeepsMultipleSelection() {
        val contract = OpenBookDocuments()
        val intent = contract.createIntent(context, arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertWriteFlags(intent)
        val other = Uri.parse("content://books.provider/document/another.mobi")
        val selected = Intent().apply { clipData = ClipData.newRawUri("books", source).apply { addItem(ClipData.Item(other)) } }
        assertEquals(listOf(source, other), contract.parseResult(Activity.RESULT_OK, selected))
        assertTrue(contract.parseResult(Activity.RESULT_CANCELED, selected).isEmpty())
    }

    @Test fun writeAuthorizationStartsAtTheOriginalDocumentAndCancellationDoesNotReturnAFile() {
        val contract = GrantBookWriteAccess()
        val intent = contract.createIntent(context, source.toString())
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(source, intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        assertWriteFlags(intent)
        assertEquals(source, contract.parseResult(Activity.RESULT_OK, Intent().setData(source)))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(source)))
    }

    @Test fun renameFolderAuthorizationRequestsPersistentAccessToTheContainingFolder() {
        val contract = GrantBookRenameFolder()
        val intent = contract.createIntent(context, source.toString())
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(source, intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        assertWriteFlags(intent)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
        val tree = DocumentsContract.buildTreeDocumentUri("books.provider", "folder")
        assertEquals(tree, contract.parseResult(Activity.RESULT_OK, Intent().setData(tree)))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(tree)))
    }

    @Test fun scanningRequestsOnlyPersistentReadAccess() {
        val request = GrantBookWriteAccess(write = false).createIntent(context, source.toString())
        assertTrue(request.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(request.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals(0, request.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun retainingImportAccessIncludesWriteAccessForFutureEdits() {
        retainBookAccess(context, source)
        val permission = context.contentResolver.persistedUriPermissions.single { it.uri == source }
        assertTrue(permission.isReadPermission)
        assertTrue(permission.isWritePermission)
        context.contentResolver.releasePersistableUriPermission(source,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun externallyOfferedReadOnlyAccessIsNeverUpgradedAndTemporaryAccessIsNotRetained() {
        val resolver = context.contentResolver
        val readOnly = Uri.parse("content://books.provider/document/read-only.epub")
        com.eyue.reader.data.retainDocumentAccess(context, readOnly,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val grant = resolver.persistedUriPermissions.single { it.uri == readOnly }
        assertTrue(grant.isReadPermission)
        assertFalse(grant.isWritePermission)
        val temporary = Uri.parse("content://books.provider/document/temporary.epub")
        com.eyue.reader.data.retainDocumentAccess(context, temporary, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(resolver.persistedUriPermissions.none { it.uri == temporary })
        val request = com.eyue.reader.externalFilesFromIntent(Intent(Intent.ACTION_VIEW, readOnly)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))!!
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, request.grantFlags)
    }

    private fun assertWriteFlags(intent: Intent) {
        for (flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) assertTrue(intent.flags and flag != 0)
    }
}
