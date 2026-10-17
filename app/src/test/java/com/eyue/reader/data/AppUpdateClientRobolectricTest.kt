package com.eyue.reader.data

import kotlinx.coroutines.runBlocking
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
}
