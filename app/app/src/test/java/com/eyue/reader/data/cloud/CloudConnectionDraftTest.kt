package com.eyue.reader.data.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudConnectionDraftTest {
    private val saved = CloudCredentials("reader", "saved-password", "https://dav.example.org/files/")

    @Test fun blankPasswordReusesOnlyTheExactSavedServerAndAccount() {
        assertEquals("saved-password", resolveCloudConnectionDraft(" reader ", "", saved.serverUrl, saved).password)
        assertNotNull(runCatching { resolveCloudConnectionDraft("other", "", saved.serverUrl, saved) }.exceptionOrNull())
        assertNotNull(runCatching { resolveCloudConnectionDraft("reader", "", "https://other.example.org/files/", saved) }.exceptionOrNull())
        assertNotNull(runCatching { resolveCloudConnectionDraft("reader", "", "https://dav.example.org/other/", saved) }.exceptionOrNull())
    }

    @Test fun explicitDraftCredentialsDoNotRequireOrModifyTheStoredAccount() {
        val draft = resolveCloudConnectionDraft("new", "new-password", "https://new.example.org/dav", saved)
        assertEquals("new-password", draft.password)
        assertEquals("https://new.example.org/dav/", draft.serverUrl)
        assertEquals("saved-password", saved.password)
    }
}
