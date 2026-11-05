package com.osama.xtreamtv

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/**
 * A text field that says when its on-screen keyboard opens (OK or a click on the field) and closes
 * (Back). TV keyboards don't all report this to the app, but every one of them works this way.
 */
class KeyboardEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onKeyboard: ((Boolean) -> Unit)? = null

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) onKeyboard?.invoke(true)
        return super.onKeyUp(keyCode, event)
    }

    override fun performClick(): Boolean {
        onKeyboard?.invoke(true)
        return super.performClick()
    }

    /** Back closes the keyboard before the app ever hears about the key. */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) onKeyboard?.invoke(false)
        return super.onKeyPreIme(keyCode, event)
    }
}
