package com.osama.xtreamtv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The one place that decides where the remote moves between parts of a screen. A screen lists its
 * sections and the exits of each; this applies the rules:
 * - R1 boundary: at a section's edge, a key with no exit leaves the highlight in place;
 * - R2 explicit exits: the highlight only leaves a section through a listed exit, never by Android's
 *   "nearest view" search (the cause of jumps like Settings to the last favorite);
 * - R3 selection lists: entering a list marked with [choices] lands on its selected item;
 * - R4 focus return: coming back to a section lands on the item last highlighted there;
 * - R5 entry: otherwise the section's first item.
 */
class FocusMap(private val focused: () -> View?) {

    inner class Section(
        val roots: () -> List<View>,
        /** Left and Right belong to the view itself (the featured banner changes its item). */
        val ownsSideKeys: Boolean = false,
    ) {
        val exits = HashMap<Int, () -> View?>()
        var last: View? = null

        fun holds(v: View): Boolean {
            val list = roots()
            var p: View? = v
            while (p != null) {
                if (list.any { it === p }) return true
                p = p.parent as? View
            }
            return false
        }

        /** The view to land on when coming in (R3, R4, R5); null when nothing here can be highlighted. */
        fun entry(): View? {
            last?.let { if (it.isAttachedToWindow && it.isShown && holds(it)) return it }
            return roots().asSequence().filter { it.isShown }.mapNotNull(::firstFocusable).firstOrNull()
        }

        fun forget() {
            last = null
        }

        val shown: Boolean get() = roots().any { it.isShown && firstFocusable(it) != null }
    }

    private val sections = ArrayList<Section>()

    fun section(ownsSideKeys: Boolean = false, roots: () -> List<View>): Section =
        Section(roots, ownsSideKeys).also { sections.add(it) }

    /** Call when the highlight moves, so sections remember their last item (R4). */
    fun note(new: View?) {
        new ?: return
        sections.firstOrNull { it.holds(new) }?.last = new
    }

    /** Up and Down go to the nearest section above or below that has something to highlight. */
    fun stack(list: List<Section>) {
        fun top(s: Section): Int {
            val at = IntArray(2)
            s.roots().firstOrNull { it.isShown }?.getLocationOnScreen(at)
            return at[1]
        }
        for (s in list) {
            s.exits[View.FOCUS_UP] = { list.filter { it.shown }.sortedBy(::top).let { o -> o.getOrNull(o.indexOf(s) - 1) }?.entry() }
            s.exits[View.FOCUS_DOWN] = { list.filter { it.shown }.sortedBy(::top).let { o -> o.getOrNull(o.indexOf(s) + 1) }?.entry() }
        }
    }

    /** True when the key was dealt with here. Call first in dispatchKeyEvent. */
    fun handle(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val from = focused() ?: return false
        val here = sections.firstOrNull { it.holds(from) } ?: return false
        val sideways = dir == View.FOCUS_LEFT || dir == View.FOCUS_RIGHT
        if (sideways && here.ownsSideKeys) return false
        // Left and Right move the cursor while there is text to move through.
        if (sideways && from is EditText) {
            val edge = if (dir == View.FOCUS_LEFT) 0 else from.length()
            if (from.selectionStart != edge) return false
        }
        val next = from.focusSearch(dir)
        if (next != null && here.holds(next) && !(next === from && atListEnd(from, dir) && dir in here.exits)) {
            if (next !== from) (selectedOf(next, from) ?: next).requestFocus(dir)
            return true
        }
        if (scrollGrid(from, dir)) return true
        here.last = from
        here.exits[dir]?.invoke()?.requestFocus(dir)
        return true // R1: no exit, stay.
    }

    /** On the last item of a list in this direction: the list keeps the highlight, but an exit may apply. */
    private fun atListEnd(from: View, dir: Int): Boolean {
        var child = from
        var p = from.parent as? View
        while (p != null && p !is RecyclerView) {
            child = p
            p = p.parent as? View
        }
        val rv = p as? RecyclerView ?: return false
        val list = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return false
        val at = rv.getChildAdapterPosition(child)
        val sideways = list.orientation == RecyclerView.HORIZONTAL
        return when (dir) {
            View.FOCUS_LEFT -> sideways && at == 0
            View.FOCUS_UP -> !sideways && at == 0
            View.FOCUS_RIGHT -> sideways && at == list.itemCount - 1
            else -> !sideways && at == list.itemCount - 1
        }
    }

    /** A grid row that isn't built yet: bring it in rather than treat this as the grid's edge. */
    private fun scrollGrid(from: View, dir: Int): Boolean {
        if (dir != View.FOCUS_UP && dir != View.FOCUS_DOWN) return false
        var child = from
        var p = from.parent as? View
        while (p != null && p !is RecyclerView) {
            child = p
            p = p.parent as? View
        }
        val rv = p as? RecyclerView ?: return false
        val grid = rv.layoutManager as? GridLayoutManager ?: return false
        val at = rv.getChildAdapterPosition(child)
        val atEdge = if (dir == View.FOCUS_UP) at < grid.spanCount else at / grid.spanCount >= (grid.itemCount - 1) / grid.spanCount
        if (at == RecyclerView.NO_POSITION || atEdge) return false
        rv.smoothScrollBy(0, (if (dir == View.FOCUS_UP) -1 else 1) * child.height)
        return true
    }

    companion object {
        /** A list of choices: entering it from outside lands on the selected one (R3). */
        fun choices(list: View) = list.setTag(R.id.focus_choices, true)

        private fun selectedOf(next: View, from: View): View? {
            var p: View? = next
            while (p != null) {
                if (p.getTag(R.id.focus_choices) == true) {
                    var f: View? = from
                    while (f != null) {
                        if (f === p) return null
                        f = f.parent as? View
                    }
                    return activated(p)
                }
                p = p.parent as? View
            }
            return null
        }

        /** The selected focusable view inside [v]. */
        fun activated(v: View): View? {
            if (v.isFocusable && v.isActivated && v.isShown) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) activated(v.getChildAt(i))?.let { return it }
            return null
        }

        fun firstFocusable(v: View): View? {
            if (!v.isShown) return null
            if (v.isFocusable && v !is RecyclerView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
            return null
        }
    }
}
