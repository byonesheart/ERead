package com.eyue.reader.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdateClientRobolectricTest {
    @Test fun includesValidatedCloudMirrorsAndExtractionCodes() {
        val update = parseAppUpdate("""{"versionCode":31,"versionName":"next","mirrors":[
            {"name":"百度网盘","url":"https://pan.baidu.com/s/example?pwd=READ","extractionCode":"READ"},
            {"name":"夸克网盘","url":"https://pan.quark.cn/s/example?pwd=Jhr4","extractionCode":"Jhr4"}]}""", 30)
        assertTrue(update.available)
        assertEquals(listOf("READ", "Jhr4"), update.mirrors.map { it.extractionCode })
        assertTrue(parseAppUpdate(manifest(), 29).mirrors.isEmpty())
        listOf("https://pan.baidu.com.evil.invalid/s/x", "http://pan.quark.cn/s/x", "https://user@pan.baidu.com/s/x").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                parseAppUpdate("""{"versionCode":31,"versionName":"next","mirrors":[{"name":"网盘","url":"$invalid"}]}""", 30)
            }
        }
    }

    private fun manifest(code: Int = 30, url: String = "https://github.com/byonesheart/ERead/releases/download/v1.9.1/ERead.apk") =
        """{"versionCode":$code,"versionName":"1.9.1","downloadUrl":"$url","changelog":["更新一","更新二"]}"""

    @Test fun comparesAndroidVersionCodesAndPreservesReleaseNotes() {
        val update = parseAppUpdate(manifest(), 29)
        assertTrue(update.available)
        assertEquals("1.9.1", update.versionName)
        assertEquals("• 更新一\n• 更新二", update.changelog)
        assertFalse(parseAppUpdate(manifest(29), 29).available)
        assertFalse(parseAppUpdate(manifest(28), 29).available)
    }

    @Test fun rejectsExternalCredentialedAndNonHttpsDownloads() {
        listOf("http://github.com/byonesheart/ERead/releases/download/v1/a.apk",
            "https://example.com/a.apk", "https://github.com.evil.invalid/byonesheart/ERead/releases/download/v1/a.apk",
            "https://user@github.com/byonesheart/ERead/releases/download/v1/a.apk",
            "https://github.com/byonesheart/ERead/releases/download/../a.apk").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { parseAppUpdate(manifest(url = url), 29) }
        }
    }

    @Test fun manualCheckUsesOnlyPublicManifestAndNoCredentials() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals(UPDATE_MANIFEST_URL, chain.request().url.toString())
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(manifest().toResponseBody()).build()
        }.build()
        assertEquals(0, requests)
        assertTrue(AppUpdateClient(client).check(29).available)
        assertEquals(1, requests)
    }

    @Test fun missingManifestIsReportedWithoutInventingAnUpdate() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("missing")
                .body("".toResponseBody()).build()
        }.build()
        val result = AppUpdateClient(client).check(29)
        assertFalse(result.available)
        assertFalse(result.published)
        assertTrue(result.changelog.contains("尚未发布"))
        assertNull(result.downloadUrl)
    }

    @Test fun httpFailuresAreVisible() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("limited")
                .body("".toResponseBody()).build()
        }.build()
        try { AppUpdateClient(client).check(29); fail("Expected a visible error") }
        catch (failure: java.io.IOException) { assertTrue(failure.message!!.contains("稍后重试")) }
    }

    @Test fun websiteFailuresFallBackForNetworkHttpAndInvalidManifests() = runBlocking {
        for (scenario in listOf("network", "timeout", "http", "missing", "html", "oversize", "invalid-link")) {
            val requests = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request.url.toString()
                assertNull(request.header("Authorization"))
                assertNull(request.header("Cookie"))
                if (request.url.toString() == UPDATE_MANIFEST_URL) {
                    if (scenario == "network") throw java.io.IOException("offline")
                    if (scenario == "timeout") throw java.net.SocketTimeoutException("timeout")
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(if (scenario == "http") 503 else if (scenario == "missing") 404 else 200).message("website")
                        .body(when (scenario) {
                            "html" -> "<html>not a manifest</html>"
                            "oversize" -> " ".repeat(512 * 1024 + 1)
                            "invalid-link" -> manifest(url = "https://evil.invalid/file.apk")
                            else -> ""
                        }.toResponseBody()).build()
                } else Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("GitHub")
                    .body(manifest().toResponseBody()).build()
            }.build()
            assertTrue("$scenario should use the valid GitHub manifest", AppUpdateClient(client).check(29).available)
            assertEquals(listOf(UPDATE_MANIFEST_URL, GITHUB_UPDATE_MANIFEST_URL), requests)
        }
    }

    @Test fun validWebsiteWithoutNewVersionDoesNotContactGithub() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(manifest(29).toResponseBody()).build()
        }.build()
        assertFalse(AppUpdateClient(client).check(29).available)
        assertEquals(listOf(UPDATE_MANIFEST_URL), requests)
    }

    @Test fun cancellationDoesNotLaunchTheFallbackRequest() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString(); entered.countDown()
            released.await(5, java.util.concurrent.TimeUnit.SECONDS)
            throw java.io.IOException("cancelled")
        }.build()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            AppUpdateClient(client).check(29)
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel(); released.countDown(); job.join()
        assertEquals(listOf(UPDATE_MANIFEST_URL), requests)
    }
}
