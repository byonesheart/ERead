package com.eyue.reader.ui

import android.content.Context
import android.view.ViewGroup
import java.io.Closeable

/** Activity-owned: keep only this book's current and adjacent chapter DOMs. */
internal class EbookReaderSession(private val context: Context) : Closeable {
    private data class Entry(val book: String, val chapter: Int, val view: EbookWebView,
        var owner: Any? = null, var leased: Boolean = false)
    private val entries = linkedMapOf<String, Entry>()

    fun acquire(key: String, chapter: Int, retentionCenter: Int = chapter, owner: Any? = null): EbookWebView {
        val book = key.substringBefore(':')
        val obsolete = entries.filter { (id, entry) -> !entry.view.pageReady && !entry.leased || entry.book != book ||
            id.contains(":continuous:") != key.contains(":continuous:") || kotlin.math.abs(entry.chapter - retentionCenter) > 1 ||
            entry.chapter == chapter && id != key }.keys.toList()
        obsolete.forEach { entries.remove(it)?.view?.dispose() }
        val entry = entries.getOrPut(key) { Entry(book, chapter, EbookWebView(context)) }
        entry.owner = owner; entry.leased = true
        return entry.view.also {
            (it.parent as? ViewGroup)?.removeView(it)
            it.onResume()
        }
    }

    fun release(view: EbookWebView, owner: Any? = null) {
        val entry = entries.values.firstOrNull { it.view === view }
        if (entry != null && entry.owner !== owner) return
        entry?.leased = false
        if (view.disposed) return
        view.onBridgeEvent = null
        view.convertText = null
        view.webViewClient = android.webkit.WebViewClient()
        view.finishTurn()
        if (!view.pageReady) view.stopLoading()
        view.onPause()
    }

    override fun close() {
        entries.values.forEach { it.view.dispose() }
        entries.clear()
    }

    private fun EbookWebView.dispose() {
        if (disposed) return
        release(this, entries.values.firstOrNull { it.view === this }?.owner)
        disposed = true
        (parent as? ViewGroup)?.removeView(this)
        removeJavascriptInterface("__ERead")
        destroy()
    }
}
