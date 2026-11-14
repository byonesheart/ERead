package com.eyue.reader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val readerDictionaryClient = OkHttpClient.Builder()
    .callTimeout(12, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()

internal fun readerDictionaryUrl(text: String) = "https://dict.cn".toHttpUrl().newBuilder()
    .addPathSegment(text.trim()).build()

internal data class ReaderDictionaryEntry(val definition: String, val pronunciation: String = "")

internal fun readerDictionaryEntry(query: String, html: String): ReaderDictionaryEntry {
    fun section(tag: String, cssClass: String) = Regex(
        """<$tag\b[^>]*\bclass\s*=\s*["'][^"']*\b$cssClass\b[^"']*["'][^>]*>(.*?)</$tag\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).find(html)?.groupValues?.get(1).orEmpty()
    fun plainText(markup: String) = HtmlCompat.fromHtml(markup.replace(Regex("""\s+"""), " "),
        HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    val english = section("ul", "dict-basic-ul").ifBlank { section("div", "basic") }
    val chinese = section("div", "layout cn")
    val entries = if (english.isNotBlank()) {
        Regex("""<li\b[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(english)
            .map { it.groupValues[1] }.filter { it.contains("<strong", ignoreCase = true) }
    } else {
        Regex("""<li\b[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(chinese)
            .map { it.groupValues[1] }
    }
    val word = query.trim()
    val pronunciation = if (Regex("""\p{IsHan}""").matches(word)) {
        Regex("""\[([^\[\]]+)\]""").findAll(plainText(section("div", "phonetic")))
            .flatMap { it.groupValues[1].split(',', '，') }
            .map(String::trim).filter(String::isNotBlank).distinct().joinToString("、")
    } else ""
    return ReaderDictionaryEntry(entries.map(::plainText).filter(String::isNotBlank)
        .distinct().take(8).joinToString("\n"), pronunciation)
}

private suspend fun lookupReaderDictionary(text: String): ReaderDictionaryEntry = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder().url(readerDictionaryUrl(text)).build()
    val call = readerDictionaryClient.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (it.code == 404) return@use ReaderDictionaryEntry("")
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    readerDictionaryEntry(text, it.peekBody(512L * 1024).string())
                }
            }
            continuation.resumeWith(result)
        }
    })
}

@Composable
internal fun ReaderDictionaryBubble(passage: ReaderSelectedPassage, onClose: () -> Unit) {
    val context = LocalContext.current
    val entry by produceState<Result<ReaderDictionaryEntry>?>(null, passage) {
        value = null
        value = try { Result.success(lookupReaderDictionary(passage.quote)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    ReaderAnnotationPopup(passage.anchor ?: Rect.Zero, onDismiss = onClose) {
        Row(Modifier.widthIn(min = 200.dp, max = 300.dp).testTag("reader_translation_bubble"),
            verticalAlignment = Alignment.CenterVertically) {
            Text("海词释义", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Text(passage.quote, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium)
        entry?.getOrNull()?.pronunciation?.takeIf(String::isNotBlank)?.let {
            Text("拼音 $it", style = MaterialTheme.typography.bodyMedium)
        }
        Text(entry?.fold(
            { it.definition.ifBlank { if (it.pronunciation.isBlank()) "海词未收录这段文字" else "简明释义暂不可用" } },
            { "查询失败，请检查网络后重试" }
        ) ?: "正在查询…")
        TextButton(onClick = {
            val uri = Uri.parse(readerDictionaryUrl(passage.quote).toString())
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show()
            }
        },
            modifier = Modifier.testTag("reader_dictionary_details")) { Text("查看详细解释") }
    }
}
