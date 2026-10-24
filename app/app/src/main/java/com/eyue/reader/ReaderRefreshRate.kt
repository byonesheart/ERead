package com.eyue.reader

import android.view.Display
import android.view.Window

/** A window preference; Android still applies power, thermal and user refresh-rate limits. */
internal fun requestReaderRefreshRate(window: Window, display: Display?, einkMode: Boolean) {
    val rate = if (einkMode || display == null) 0f else {
        val current = display.mode
        val rates = display.supportedModes.asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate }
            .filter { it.isFinite() && it > 0f }
            .toList()
        // Prefer the closest supported high-refresh tier at/above 120 Hz, including
        // nominal 120 Hz panels reporting 119.88 Hz. A 60/144 Hz panel should use 144 Hz.
        rates.filter { it >= 119.5f }.minOrNull() ?: rates.maxOrNull() ?: 0f
    }
    val attributes = window.attributes
    if (attributes.preferredRefreshRate == rate) return
    // Request only a refresh rate, never another resolution or a forced display mode.
    attributes.preferredRefreshRate = rate
    window.attributes = attributes
}
