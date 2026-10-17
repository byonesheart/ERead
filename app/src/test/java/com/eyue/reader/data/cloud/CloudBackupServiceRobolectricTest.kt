package com.eyue.reader.data.cloud

import android.content.Context
import android.content.ContextWrapper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudBackupServiceRobolectricTest : CloudServiceContract() {
    override fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getCacheDir(): File = File("build/robolectric-cloud").absoluteFile.apply { mkdirs() }
    }

    override fun sourcePath(file: File): String {
        val absolute = file.absolutePath
        // On Windows, the drive colon is a URI scheme. Root-relative paths keep the actual file
        // readable on the test's working drive and exercise Android's schemeless local-file branch.
        val local = if (absolute.matches(Regex("^[A-Za-z]:[\\\\/].*"))) absolute.substring(2) else absolute
        check(File(local).canonicalFile == file.canonicalFile) { "测试来源路径没有指向原文件" }
        return local
    }
}
