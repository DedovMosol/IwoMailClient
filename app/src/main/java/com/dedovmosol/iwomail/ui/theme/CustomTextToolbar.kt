package com.dedovmosol.iwomail.ui.theme

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Кастомный TextToolbar с правильным порядком кнопок:
 * Вырезать → Копировать → Вставить
 */
class CustomTextToolbar(private val view: View) : TextToolbar {
    
    private var actionMode: ActionMode? = null
    private var onCopyRequested: (() -> Unit)? = null
    private var onPasteRequested: (() -> Unit)? = null
    private var onCutRequested: (() -> Unit)? = null
    private var onSelectAllRequested: (() -> Unit)? = null
    
    override val status: TextToolbarStatus
        get() = if (actionMode != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
    
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested
        
        if (actionMode == null) {
            actionMode = view.startActionMode(
                object : ActionMode.Callback2() {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        // Порядок: Вырезать → Копировать → Вставить → Выбрать всё
                        var order = 0
                        
                        if (onCutRequested != null) {
                            menu.add(Menu.NONE, MENU_ITEM_CUT, order++, android.R.string.cut)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        
                        if (onCopyRequested != null) {
                            menu.add(Menu.NONE, MENU_ITEM_COPY, order++, android.R.string.copy)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        
                        if (onPasteRequested != null) {
                            menu.add(Menu.NONE, MENU_ITEM_PASTE, order++, android.R.string.paste)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        
                        if (onSelectAllRequested != null) {
                            menu.add(Menu.NONE, MENU_ITEM_SELECT_ALL, order++, android.R.string.selectAll)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        
                        return true
                    }
                    
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                    
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                        when (item.itemId) {
                            MENU_ITEM_CUT -> onCutRequested?.invoke()
                            MENU_ITEM_COPY -> onCopyRequested?.invoke()
                            MENU_ITEM_PASTE -> onPasteRequested?.invoke()
                            MENU_ITEM_SELECT_ALL -> onSelectAllRequested?.invoke()
                            else -> return false
                        }
                        mode.finish()
                        return true
                    }
                    
                    override fun onDestroyActionMode(mode: ActionMode) {
                        actionMode = null
                    }
                    
                    override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                        outRect.set(
                            rect.left.toInt(),
                            rect.top.toInt(),
                            rect.right.toInt(),
                            rect.bottom.toInt()
                        )
                    }
                },
                ActionMode.TYPE_FLOATING
            )
        } else {
            actionMode?.invalidate()
        }
    }
    
    override fun hide() {
        actionMode?.finish()
        actionMode = null
    }
    
    companion object {
        private const val MENU_ITEM_CUT = 0
        private const val MENU_ITEM_COPY = 1
        private const val MENU_ITEM_PASTE = 2
        private const val MENU_ITEM_SELECT_ALL = 3
    }
}
