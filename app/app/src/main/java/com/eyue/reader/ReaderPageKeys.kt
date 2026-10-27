package com.eyue.reader

import android.view.KeyEvent

internal fun readerPageKeyDirection(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_1 -> -1
    KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_2 -> 1
    else -> null
}
