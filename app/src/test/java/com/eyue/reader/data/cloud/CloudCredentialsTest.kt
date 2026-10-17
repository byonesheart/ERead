package com.eyue.reader.data.cloud

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class CloudCredentialsTest {
    @Test fun oldTwoFieldPayloadKeepsTheExistingProviderAndResumeIdentity() {
        val old = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { it.writeUTF("reader@example.com"); it.writeUTF("application-password") }
        }.toByteArray()
        val restored = CloudCredentialPayload.decode(old)
        assertEquals(DEFAULT_WEBDAV_SERVER, restored.serverUrl)
        assertEquals("reader@example.com", restored.identity)
        assertEquals("application-password", restored.password)
        assertEquals("https://dav.jianguoyun.com/dav/ERead/", restored.rootUrl)
    }

    @Test fun serverNormalizationAndPayloadRoundTripScopeAccountIdentity() {
        val first = CloudCredentials("same-account", "password", " HTTPS://EXAMPLE.COM:443/dav ")
        val equivalent = CloudCredentials("same-account", "password", "https://example.com/dav/")
        val otherServer = CloudCredentials("same-account", "password", "https://example.com/other/")
        val otherAccount = CloudCredentials("other-account", "password", first.serverUrl)
        val restored = CloudCredentialPayload.decode(CloudCredentialPayload.encode(first))
        assertEquals("https://example.com/dav/", restored.serverUrl)
        assertEquals(first.identity, equivalent.identity)
        assertEquals(first.identity, restored.identity)
        assertNotEquals(first.identity, otherServer.identity)
        assertNotEquals(first.identity, otherAccount.identity)
        assertEquals("http://192.168.1.2:8080/webdav/", normalizeWebDavServer("http://192.168.1.2:8080/webdav"))
    }

    @Test fun invalidServerCannotEmbedCredentialsOrChangeTheRequestTarget() {
        for (server in listOf("", "example.com/dav", "ftp://example.com", "https://u:p@example.com/dav",
            "https://example.com/dav?token=x", "https://example.com/dav#fragment", "https://example.com\\dav",
            "https://example.com/\npath")) {
            assertThrows(IllegalArgumentException::class.java) { CloudCredentials("account", "password", server) }
        }
    }
}
