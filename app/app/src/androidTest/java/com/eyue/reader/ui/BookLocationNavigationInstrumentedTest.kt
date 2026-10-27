package com.eyue.reader.ui

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.LibraryMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookLocationNavigationInstrumentedTest {
    @Test fun systemFilesOpensEachActualContainingFolderInsteadOfItsRootOrPreviousFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val parent = "/sdcard/Download/EReadLocationTest-${System.nanoTime()}"
        fun shell(command: String) { automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        } }
        shell("mkdir -p $parent")
        try {
            repeat(3) { index ->
                val folder = "$parent/case-$index"
                val name = "location-target-$index.txt"
                shell("mkdir -p $folder")
                shell("touch $folder/$name")
                val relative = "Download/${parent.substringAfterLast('/')}/case-$index"
                val file = File(Environment.getExternalStorageDirectory(), "$relative/$name")
                val uri = when (index) {
                    0 -> null
                    1 -> Uri.fromFile(file)
                    else -> DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$relative")
                }
                val book = Book(title = "定位测试", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
                    chapters = emptyList(), sourceUri = uri?.toString(), sourcePath = if (index == 0) file.path
                    else File(context.filesDir, "books/private-working-copy.txt").path)
                val intent = requireNotNull(bookFileManagerIntent(context, book))
                assertNotEquals(context.packageName, intent.component?.packageName)
                instrumentation.runOnMainSync { context.startActivity(intent) }
                val deadline = System.nanoTime() + 15_000_000_000L
                var visible = false
                while (System.nanoTime() < deadline && !visible) {
                    val root = automation.rootInActiveWindow
                    visible = root?.findAccessibilityNodeInfosByText(name)?.any { it.text?.toString() == name } == true
                    @Suppress("DEPRECATION") root?.recycle()
                    if (!visible) Thread.sleep(100)
                }
                assertTrue("System Files did not show $folder/$name", visible)
                shell("rm $folder/$name")
                shell("rmdir $folder")
            }
        } finally {
            repeat(3) { index ->
                shell("rm -f $parent/case-$index/location-target-$index.txt")
                shell("rmdir $parent/case-$index")
            }
            shell("rmdir $parent")
        }
    }
}
