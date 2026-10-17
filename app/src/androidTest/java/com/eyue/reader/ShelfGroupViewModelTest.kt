package com.eyue.reader

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShelfGroupViewModelTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ConsentGrantRule()).around(compose)
    private lateinit var vm: ReaderViewModel
    private var originalMode: LibraryMode? = null
    private var memberships: Map<String, String?> = emptyMap()
    private val added = mutableListOf<String>()

    @Before fun setUp() {
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        compose.waitUntil(30_000) { !vm.loading }
        compose.runOnIdle {
            vm.close()
            originalMode = vm.mode
            memberships = vm.state.books.associate { it.id to it.groupId }
        }
    }

    @After fun restore() {
        if (originalMode == null) return
        compose.runOnIdle {
            memberships.forEach { (book, group) -> vm.moveBookToGroup(book, group) }
            added.forEach { id -> vm.state.groups.find { it.id == id }?.let { group ->
                vm.mode = group.mode
                vm.deleteGroup(id)
            } }
            vm.mode = requireNotNull(originalMode)
            vm.message = null
            vm.flush()
        }
        compose.waitUntil(10_000) { LibraryRepository(compose.activity).load().groups.none { it.id in added } }
    }

    @Test fun modesCanUseTheSameGroupNameButCannotMoveBooksAcrossModes() {
        compose.runOnIdle {
            val name = "收藏-${System.nanoTime()}"
            val novel = vm.state.books.first { it.mode == LibraryMode.NOVEL }
            val comic = vm.state.books.first { it.mode == LibraryMode.COMIC }
            val notes = vm.state.annotations
            val novelGroup = create(name, LibraryMode.NOVEL)
            val count = vm.state.groups.size
            vm.createGroup("  $name  ")
            assertEquals(count, vm.state.groups.size)
            val comicGroup = create(name, LibraryMode.COMIC)
            assertFalse(novelGroup == comicGroup)
            vm.moveBookToGroup(novel.id, comicGroup)
            assertEquals(novel, vm.state.books.first { it.id == novel.id })
            vm.moveBookToGroup(novel.id, novelGroup)
            vm.moveBookToGroup(comic.id, comicGroup)
            assertEquals(novel.copy(groupId = novelGroup), vm.state.books.first { it.id == novel.id })
            assertEquals(comic.copy(groupId = comicGroup), vm.state.books.first { it.id == comic.id })
            vm.renameGroup(comicGroup, "漫画收藏")
            assertEquals(name, vm.state.groups.first { it.id == novelGroup }.name)
            assertEquals("漫画收藏", vm.state.groups.first { it.id == comicGroup }.name)
            assertEquals(notes, vm.state.annotations)
            vm.flush()
        }
        compose.waitUntil(10_000) {
            LibraryRepository(compose.activity).load().let { restored ->
                restored.groups.count { it.id in added } == 2 &&
                    restored.books.filter { it.id in memberships }.map { it.groupId } == vm.state.books.filter { it.id in memberships }.map { it.groupId }
            }
        }
    }

    @Test fun deletingGroupKeepsBooksFilesProgressAndAnnotationsAndReturnsThemToUngrouped() {
        compose.runOnIdle {
            val book = vm.state.books.first { it.mode == LibraryMode.NOVEL }
            val notes = vm.state.annotations
            val cachedFiles = book.chapters.mapNotNull { it.textPath }.map(::File).filter { it.exists() }
            val group = create("待读-${System.nanoTime()}", LibraryMode.NOVEL)
            vm.moveBookToGroup(book.id, group)
            vm.moveBookToGroup(book.id, null)
            assertEquals(book.copy(groupId = null), vm.state.books.first { it.id == book.id })
            vm.moveBookToGroup(book.id, group)
            vm.deleteGroup(group)
            assertTrue(vm.state.groups.none { it.id == group })
            assertEquals(book.copy(groupId = null), vm.state.books.first { it.id == book.id })
            assertEquals(notes, vm.state.annotations)
            assertTrue(cachedFiles.all { it.exists() })
            vm.flush()
        }
        compose.waitUntil(10_000) { LibraryRepository(compose.activity).load().let { restored ->
            restored.groups.none { it.id in added } && restored.books.none { it.groupId in added }
        } }
    }

    private fun create(name: String, mode: LibraryMode): String {
        vm.mode = mode
        vm.createGroup(name)
        val id = vm.state.groups.first { it.mode == mode && it.name == name }.id
        added += id
        return id
    }
}
