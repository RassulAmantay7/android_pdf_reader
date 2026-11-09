package com.rassul.pdfreader.reader.screen

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.rassul.pdfreader.reader.ReaderActivity
import com.rassul.pdfreader.reader.Tool
import com.rassul.pdfreader.ui.Icon
import com.rassul.pdfreader.ui.ToggleIcon
import com.rassul.pdfreader.ui.Ui

/**
 * Нижняя плавающая панель режима пометок: Перо, Маркер, Ластик, Заметка, цвет/толщина,
 * Отменить/Повторить, Готово.
 */
internal class AnnotToolbar(private val a: ReaderActivity) {
    companion object {
        val PEN_COLORS = listOf(
            0xFF212121.toInt(), // чёрный
            0xFF2962FF.toInt(), // синий
            0xFFD50000.toInt(), // красный
            0xFF00C853.toInt(), // зелёный
            0xFFFF6D00.toInt(), // оранжевый
            0xFFAA00FF.toInt(), // фиолетовый
        )
        val MARKER_COLORS = listOf(
            0xFFFFEB3B.toInt(), // жёлтый
            0xFF76FF03.toInt(), // салатовый
            0xFFFF4081.toInt(), // розовый
            0xFF40C4FF.toInt(), // голубой
            0xFFFFAB40.toInt(), // оранжевый
            0xFFE040FB.toInt(), // сиреневый
        )
        val PEN_WIDTHS = listOf("Тонкая" to 1f, "Средняя" to 2.5f, "Толстая" to 5f)
    }

    val view: View
    private val tools = ArrayList<Pair<Tool, ToggleIcon>>()
    private val colorDot: ColorDotView
    private val undoBtn: View
    private val redoBtn: View
    private var updating = false

    init {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4))
        }
        fun tool(t: Tool, icon: Icon, desc: String) {
            val ti = Ui.toggleIconButton(a, icon, desc) { checked ->
                if (!updating) select(if (checked) t else Tool.NONE)
            }
            tools += t to ti
            row.addView(ti.view)
        }
        tool(Tool.PEN, Icon.PEN, "Перо")
        tool(Tool.HIGHLIGHTER, Icon.HIGHLIGHTER, "Маркер")
        tool(Tool.ERASER, Icon.ERASER, "Ластик")
        tool(Tool.NOTE, Icon.NOTE, "Заметка")
        colorDot = ColorDotView(a, Ui.dp(a, 48), Ui.colors.outline).apply {
            contentDescription = "Цвет и толщина"
            isClickable = true
            isFocusable = true
            setOnClickListener { showColorSheet() }
        }
        row.addView(colorDot)
        row.addView(divider())
        undoBtn = Ui.iconButton(a, Icon.UNDO, "Отменить") { a.view.undo(); a.onAnnotationsEdited() }
        redoBtn = Ui.iconButton(a, Icon.REDO, "Повторить") { a.view.redo(); a.onAnnotationsEdited() }
        row.addView(undoBtn)
        row.addView(redoBtn)
        row.addView(divider())
        row.addView(Ui.iconButton(a, Icon.CHECK, "Готово") { a.setEditMode(false) })

        val scroll = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            background = Ui.panelBackground(a, 28f)
            elevation = Ui.dp(a, 6).toFloat()
            addView(row)
        }
        // центрируем панель по горизонтали
        view = FrameLayout(a).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            visibility = View.GONE
        }
        refresh()
    }

    private fun divider(): View = View(a).apply {
        setBackgroundColor(Ui.colors.outline)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(a, 1), Ui.dp(a, 28)).apply {
            marginStart = Ui.dp(a, 4); marginEnd = Ui.dp(a, 4)
        }
    }

    fun select(t: Tool) {
        a.view.tool = t
        a.view.clearSelection()
        refresh()
    }

    fun refresh() {
        val t = a.view.tool
        updating = true
        try { for ((tt, ti) in tools) if (ti.checked != (tt == t)) ti.checked = (tt == t) } finally { updating = false }
        colorDot.color = if (t == Tool.HIGHLIGHTER) a.view.highlighterColor else a.view.penColor
        val ann = a.view.annotations
        setEnabled(undoBtn, ann.canUndo)
        setEnabled(redoBtn, ann.canRedo)
    }

    private fun setEnabled(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.38f
    }

    private fun showColorSheet() {
        val marker = a.view.tool == Tool.HIGHLIGHTER
        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 16))
        }
        var dlg: Dialog? = null
        content.addView(Ui.body(a, if (marker) "Цвет маркера" else "Цвет пера", secondary = true))
        val colors = if (marker) MARKER_COLORS else PEN_COLORS
        val selected = if (marker) a.view.highlighterColor else a.view.penColor
        content.addView(Ui.colorRow(a, colors, selected) { argb ->
            if (marker) a.view.highlighterColor = argb else a.view.penColor = argb
            refresh()
            if (marker) dlg?.dismiss()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(a, 8); bottomMargin = Ui.dp(a, 16)
        })
        if (!marker) {
            content.addView(Ui.body(a, "Толщина пера", secondary = true))
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(a, 8), 0, 0)
            }
            for ((label, w) in PEN_WIDTHS) {
                val sel = kotlin.math.abs(a.view.penWidthPt - w) < 0.01f
                row.addView(Ui.button(a, label, primary = sel) {
                    a.view.penWidthPt = w
                    if (a.view.tool != Tool.PEN) select(Tool.PEN)
                    dlg?.dismiss()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = Ui.dp(a, 8)
                })
            }
            content.addView(row)
        }
        dlg = Ui.showSheet(a, if (marker) "Маркер" else "Перо", content)
    }
}
