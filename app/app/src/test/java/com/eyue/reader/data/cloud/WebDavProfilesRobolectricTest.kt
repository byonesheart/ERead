package com.eyue.reader.data.cloud

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WebDavProfilesRobolectricTest {
    @Test fun legacyBackupAndImportConfigurationsCanBothBeSelectedWithoutLosingCredentials() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = CloudCredentials("reader", "legacy-secret")
        val import = CloudCredentials("reader", "import-secret", "https://books.example.invalid/dav/")
        val active = TestStore(legacy)
        val stores = mutableMapOf<String, TestStore>()
        fun profiles() = WebDavProfiles(context, active) { stores.getOrPut(it) { TestStore() } }

        val original = profiles().list().single()
        assertTrue(original.active)
        assertSame(legacy, profiles().read(original.id))
        val other = profiles().save(import, "书库")
        assertFalse(other.active)
        assertSame(legacy, active.read())
        profiles().activate(other.id)
        assertEquals(listOf("书库", "reader"), profiles().list().map { it.name })
        assertSame(legacy, profiles().read(original.id))
        assertSame(import, profiles().find("reader", import.serverUrl))
        assertThrows(IllegalArgumentException::class.java) { profiles().remove(other) }

        profiles().disconnect()
        assertNull(active.read())
        assertTrue(profiles().list().none { it.active })
        assertSame(import, profiles().read(other.id))
        profiles().activate(original.id)
        assertSame(legacy, active.read())
    }

    private class TestStore(var value: CloudCredentials? = null) : CloudCredentialStore {
        override fun read() = value
        override fun write(credentials: CloudCredentials) { value = credentials }
        override fun clear() { value = null }
    }
}
