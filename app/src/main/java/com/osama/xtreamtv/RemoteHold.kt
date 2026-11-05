package com.osama.xtreamtv

import android.view.KeyEvent

/**
 * Review item 1.1D: a direction button held down sends a new move 20 times a second, and a gliding
 * scroll plus a growing highlight for every one of them pile up. While a direction is held the list
 * moves straight to the next row and the highlight grows at once; its shadow and soft growth come
 * back when the button is let go. (Holding back the posters until then was tried and dropped: they
 * all arriving together made a half-second pause.)
 */
object RemoteHold {
    /** Test builds only: switched off to measure the difference (scroll measurements). */
    @Volatile var enabled = true

    /** True while an arrow button is being held (its repeats are arriving). */
    @Volatile var active = false
        private set

    private val released = ArrayList<() -> Unit>()

    /** Runs when the held button is let go (once per hold). */
    fun onRelease(action: () -> Unit) {
        synchronized(released) { released += action }
    }

    fun removeOnRelease(action: () -> Unit) {
        synchronized(released) { released -= action }
    }

    /** Every key, from the window of every screen (see Diag). */
    fun onKey(event: KeyEvent) {
        if (event.keyCode !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return
        if (!enabled) return
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            active = true
        } else if (event.action == KeyEvent.ACTION_UP && active) {
            active = false
            val actions = synchronized(released) { released.toList() }
            for (a in actions) a()
        }
    }
}
