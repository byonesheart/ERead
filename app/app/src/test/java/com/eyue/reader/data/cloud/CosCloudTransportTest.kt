package com.eyue.reader.data.cloud

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CosCloudTransportTest {
    private val credentials = CloudCredentials("access", "secret", "https://cos.example.invalid",
        S3Location("reader-backups", "ap-nanjing", "ERead", false, true))

    private class Store(val overwrite: Boolean = false) : Interceptor {
        val objects = ConcurrentHashMap<String, ByteArray>()
        var beforeCreate: ((String) -> Unit)? = null
        val listSizes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val key = request.url.pathSegments.joinToString("/")
            var status = 200
            val bytes = when {
                request.url.queryParameter("list-type") != null -> {
                    val prefix = request.url.queryParameter("prefix").orEmpty()
                    val size = request.url.queryParameter("max-keys")!!.toInt()
                    listSizes += size
                    val keys = objects.keys.filter { it.startsWith(prefix) }.sorted().take(size)
                    ("<ListBucketResult><IsTruncated>false</IsTruncated>" + keys.joinToString("") {
                        "<Contents><Key>$it</Key></Contents>"
                    } + "</ListBucketResult>").toByteArray()
                }
                request.method == "PUT" -> {
                    val body = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                    if (request.header("x-cos-forbid-overwrite") == "true" && !overwrite) {
                        beforeCreate?.invoke(key)
                        if (objects.putIfAbsent(key, body) != null) status = 409
                    } else objects[key] = body
                    byteArrayOf()
                }
                request.method == "DELETE" -> { objects.remove(key); byteArrayOf() }
                else -> objects[key] ?: run { status = 404; byteArrayOf() }
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(bytes.toResponseBody()).build()
        }
    }

    private fun transport(store: Store): CosCloudTransport {
        val rawCredentials = CloudCredentials(credentials.account, credentials.password, credentials.serverUrl,
            credentials.s3!!.copy(prefix = "ERead/cos-v1"))
        val raw = S3CloudTransport(rawCredentials, {}, OkHttpClient.Builder().addInterceptor(store).build())
        return CosCloudTransport(credentials, {}, raw)
    }

    @Test fun immutableVersionsRejectStaleWritersWithoutS3ConditionalHeaders() {
        val store = Store()
        val client = transport(store)
        client.testWriteAccess()
        assertTrue(store.objects.isEmpty())
        assertTrue(client.putVersionedMetadata("catalog.json", "first".toByteArray(), null))
        val first = client.versionedMetadata("catalog.json")!!
        assertTrue(client.putVersionedMetadata("catalog.json", "second".toByteArray(), first.etag))
        assertFalse(client.putVersionedMetadata("catalog.json", "stale".toByteArray(), first.etag))
        assertFalse(client.putVersionedMetadata("catalog.json", "new".toByteArray(), null))
        val latest = client.versionedMetadata("catalog.json")!!
        assertEquals("second", latest.bytes.decodeToString())
        assertTrue(client.readMetadata("catalog.json", latest.etag).unchanged)
        assertEquals(2, store.objects.size)
        assertTrue(store.listSizes.contains(1))
    }

    @Test fun twoDevicesRacingForOneGenerationHaveOnlyOneSuccessfulCommit() {
        val store = Store()
        val first = transport(store)
        val second = transport(store)
        first.testWriteAccess(); second.testWriteAccess()
        assertTrue(first.putVersionedMetadata("catalog.json", "base".toByteArray(), null))
        val version = first.versionedMetadata("catalog.json")!!.etag
        val barrier = CyclicBarrier(2)
        store.beforeCreate = { key -> if (key.contains(".cos-records-catalog.json/")) barrier.await(10, TimeUnit.SECONDS) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Boolean> { first.putVersionedMetadata("catalog.json", "A".toByteArray(), version) }
            val b = pool.submit<Boolean> { second.putVersionedMetadata("catalog.json", "B".toByteArray(), version) }
            assertEquals(1, listOf(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)).count { it })
            assertTrue(first.metadata("catalog.json")!!.decodeToString() in setOf("A", "B"))
        } finally { pool.shutdownNow() }
    }

    @Test fun ineffectiveNativeProtectionFailsBeforeCommittingAnyRecord() {
        val store = Store(overwrite = true)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            transport(store).putVersionedMetadata("catalog.json", byteArrayOf(1), null)
        }
        assertTrue(failure.message!!.contains("版本控制"))
        assertTrue(store.objects.isEmpty())
    }

    @Test fun deletingOneBookRemovesItsRecordHistoryButPreservesOtherBooks() {
        val store = Store()
        val client = transport(store)
        client.putMetadata("books/first/manifest.json", "one".toByteArray())
        client.putMetadata("books/first/manifest.json", "two".toByteArray())
        client.putMetadata("books/second/manifest.json", "other".toByteArray())
        client.deleteCollection("books/first/")
        assertNull(client.metadata("books/first/manifest.json"))
        assertEquals("other", client.metadata("books/second/manifest.json")!!.decodeToString())
    }

    @Test fun cosConfigurationSurvivesEncryptionPayloadAndHasIndependentIdentity() {
        val restored = CloudCredentialPayload.decode(CloudCredentialPayload.encode(credentials))
        assertEquals(credentials.s3, restored.s3)
        assertEquals(credentials.identity, restored.identity)
        assertNotEquals(credentials.identity, CloudCredentials(credentials.account, credentials.password,
            credentials.serverUrl, credentials.s3!!.copy(cos = false)).identity)
    }
}
