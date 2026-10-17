package com.eyue.reader

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSettingsProfileTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ConsentGrantRule()).around(compose)
    private lateinit var vm: ReaderViewModel
    private var originalNovel: ReaderSettings? = null
    private var originalComic: ReaderSettings? = null
    private var originalMode: LibraryMode? = null

    @Before fun rememberProfiles() {
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        compose.waitUntil(30_000) { !vm.loading }
        compose.runOnIdle {
            originalNovel = vm.state.settings
            originalComic = vm.state.comicSettings
            originalMode = vm.mode
            vm.close()
        }
    }

    @After fun restoreProfiles() {
        val novel = originalNovel ?: return
        val comic = originalComic?.copy(nightMode = novel.nightMode) ?: return
        compose.runOnIdle {
            vm.close()
            vm.mode = LibraryMode.COMIC
            vm.settings(comic)
            vm.mode = LibraryMode.NOVEL
            vm.settings(novel)
            originalMode?.let { vm.mode = it }
            vm.flush()
        }
        awaitPersisted(novel, comic)
    }

    @Test fun novelAndComicProfilesStayIndependentWhenSwitchedAndReloaded() {
        val novel = requireNotNull(originalNovel).copy(background = ReaderBackground.GREEN,
            brightness = .27f, readingMode = ReadingMode.PAGED, volumePaging = false,
            fontSize = 28f, lineHeight = 1.6f, bold = true, nightMode = false)
        val comic = requireNotNull(originalComic).copy(background = ReaderBackground.WHITE,
            brightness = .71f, readingMode = ReadingMode.SCROLL, volumePaging = true,
            fontSize = 17f, lineHeight = 2f, bold = false, nightMode = false)
        compose.runOnIdle {
            vm.mode = LibraryMode.NOVEL
            vm.settings(novel)
            vm.mode = LibraryMode.COMIC
            assertEquals(requireNotNull(originalComic).copy(nightMode = false), vm.readerSettings)
            vm.settings(comic)
            assertEquals(comic, vm.readerSettings)
            vm.mode = LibraryMode.NOVEL
            assertEquals(novel, vm.readerSettings)
            assertEquals(comic, vm.state.comicSettings)
            vm.mode = LibraryMode.COMIC
            assertEquals(comic, vm.readerSettings)
            assertEquals(novel, vm.state.settings)
            vm.flush()
        }
        awaitPersisted(novel, comic)
        val reloaded = LibraryRepository(compose.activity).load()
        assertEquals(novel, reloaded.settings)
        assertEquals(comic, reloaded.comicSettings)
    }

    @Test fun nightChangesFromEitherModeStayGlobalWithoutOverwritingProfileValues() {
        val novel = requireNotNull(originalNovel).copy(background = ReaderBackground.GREEN,
            brightness = .31f, readingMode = ReadingMode.PAGED, volumePaging = false, nightMode = false)
        val comic = requireNotNull(originalComic).copy(background = ReaderBackground.WHITE,
            brightness = .68f, readingMode = ReadingMode.SCROLL, volumePaging = true, nightMode = false)
        compose.runOnIdle {
            vm.mode = LibraryMode.NOVEL
            vm.settings(novel)
            vm.mode = LibraryMode.COMIC
            vm.settings(comic)
            vm.settings(vm.readerSettings.copy(nightMode = true))
            assertEquals(comic.copy(nightMode = true), vm.readerSettings)
            assertEquals(novel.copy(nightMode = true), vm.state.settings)
            vm.mode = LibraryMode.NOVEL
            assertEquals(novel.copy(nightMode = true), vm.readerSettings)
            assertEquals(comic.copy(nightMode = true), vm.state.comicSettings)
            vm.flush()
        }
        awaitPersisted(novel.copy(nightMode = true), comic.copy(nightMode = true))

        compose.runOnIdle {
            vm.settings(vm.readerSettings.copy(nightMode = false))
            assertEquals(novel, vm.readerSettings)
            assertEquals(comic, vm.state.comicSettings)
            vm.mode = LibraryMode.COMIC
            assertEquals(comic, vm.readerSettings)
            vm.flush()
        }
        awaitPersisted(novel, comic)
        val reloaded = LibraryRepository(compose.activity).load()
        assertEquals(novel, reloaded.settings)
        assertEquals(comic, reloaded.comicSettings)
    }

    private fun awaitPersisted(novel: ReaderSettings, comic: ReaderSettings) {
        compose.waitUntil(10_000) {
            LibraryRepository(compose.activity).load().let { it.settings == novel && it.comicSettings == comic }
        }
    }
}
