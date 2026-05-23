package com.skarm.launcher

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible 1px view whose only job is to receive soft-keyboard (IME) input and
 * forward it to SK via [NativeBridge]. SK renders its own text fields, so we don't
 * need a real editor — the IME just needs *some* focusable text-editor view to
 * target. The SurfaceView can't host an InputConnection without being subclassed,
 * so keeping this separate isolates IME plumbing from the render surface.
 */
class ImeBridgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // VISIBLE_PASSWORD: no autocorrect/composing region, so the IME commits each
        // character directly (we have no Editable to compose against). Action Done
        // maps to Enter so login forms can submit from the keyboard.
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return SkInputConnection(this)
    }
}

/**
 * Translates IME edits into SK's GLFW input path: printable text as char input,
 * backspace/enter/delete as GLFW key transitions. There's no local [android.text.Editable]
 * — we forward and forget, since SK owns the actual text-field state.
 */
private class SkInputConnection(view: View) : BaseInputConnection(view, false) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.codePoints()?.forEach { NativeBridge.onCharInput(it) }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // VISIBLE_PASSWORD shouldn't compose; if an IME does anyway, treat as commit.
        return commitText(text, newCursorPosition)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { tapKey(GLFW_KEY_BACKSPACE) }
        repeat(afterLength) { tapKey(GLFW_KEY_DELETE) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> tapKey(GLFW_KEY_BACKSPACE)
            KeyEvent.KEYCODE_FORWARD_DEL -> tapKey(GLFW_KEY_DELETE)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> tapKey(GLFW_KEY_ENTER)
            else -> {
                val u = event.unicodeChar
                if (u != 0) NativeBridge.onCharInput(u)
            }
        }
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        tapKey(GLFW_KEY_ENTER) // Done/Go/Send -> submit
        return true
    }

    private fun tapKey(glfwKey: Int) {
        NativeBridge.onKeyEvent(glfwKey, 1, 0)
        NativeBridge.onKeyEvent(glfwKey, 0, 0)
    }

    private companion object {
        // GLFW keycodes (mirror sklauncher.c / GameActivity.glfwKeyCode).
        const val GLFW_KEY_ENTER = 257
        const val GLFW_KEY_BACKSPACE = 259
        const val GLFW_KEY_DELETE = 261
    }
}
