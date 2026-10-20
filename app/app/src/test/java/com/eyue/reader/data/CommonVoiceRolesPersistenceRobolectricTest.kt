package com.eyue.reader.data

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CommonVoiceRolesPersistenceRobolectricTest {
    private fun withRepository(block: (LibraryRepository, File) -> Unit) {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "common-roles-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
        try { block(LibraryRepository(context), File(context.filesDir, "library.json")) }
        finally { check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    @Test fun freshAndLegacyLibrariesReceiveDefaultsButExplicitEmptyNeverResurrectsRoles() = withRepository { repository, file ->
        assertEquals(defaultCommonVoiceRoles(), repository.load().commonVoiceRoles)
        file.writeText(JSONObject().put("books", JSONArray()).toString())
        assertEquals(defaultCommonVoiceRoles(), repository.load().commonVoiceRoles)
        file.writeText(JSONObject().put("books", JSONArray()).put("commonVoiceRoles", JSONArray()).toString())
        assertTrue(repository.load().commonVoiceRoles.isEmpty())
        repository.save(LibraryState(commonVoiceRoles = emptyList()))
        assertTrue(repository.load().commonVoiceRoles.isEmpty())
    }

    @Test fun commonAndPerBookEnabledKindsRoundTripIndependently() = withRepository { repository, _ ->
        val common = defaultCommonVoiceRoles().map { it.copy(enabled = false) }
        val role = BookVoiceRole("人物", "人物", id = "character", kind = VoiceRoleKind.CHARACTER)
        val narrator = BookVoiceRole("本书旁白", "旁白", id = "narrator", enabled = false, kind = VoiceRoleKind.NARRATOR)
        val book = Book(title = "角色测试", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = emptyList(), voiceRoles = listOf(role, narrator))
        repository.save(LibraryState(books = listOf(book), commonVoiceRoles = common))
        val restored = repository.load()
        assertEquals(common, restored.commonVoiceRoles)
        assertEquals(listOf(role, narrator), restored.books.single().voiceRoles)
    }

    @Test fun roleCleanupIncludesCommonStorageAndPersistsTheExplicitEmptyList() = withRepository { repository, _ ->
        val state = LibraryState()
        val usage = runBlocking { measureLibraryCleanup(RuntimeEnvironment.getApplication(), state) }
        assertTrue(usage.roleBytes > 0)
        val cleared = cleanLibraryRecords(state, LibraryCleanupKind.ROLES)
        assertTrue(cleared.commonVoiceRoles.isEmpty())
        repository.save(cleared)
        assertTrue(repository.load().commonVoiceRoles.isEmpty())
        assertEquals(0L, runBlocking { measureLibraryCleanup(RuntimeEnvironment.getApplication(), cleared) }.roleBytes)
    }
}
