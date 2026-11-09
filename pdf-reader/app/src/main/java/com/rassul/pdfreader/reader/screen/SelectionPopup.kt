package com.rassul.pdfreader.reader.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.rassul.pdfreader.reader.ReaderActivity
import com.rassul.pdfreader.reader.TextSelection
import com.rassul.pdfreader.ui.Ui

/** Плавающее меню над выделенным текстом: Копировать / Выделить маркером / Поиск. */
internal class SelectionPopup(private val a: ReaderActivity) {
    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.panelBackground(a, 16f)
        elevation = Ui.dp(a, 8).toFloat()
        setPadding(Ui.dp(a, 4), Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2))
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
    }
    private var selection: TextSelection? = null
    val visible: Boolean get() = view.visibility == View.VISIBLE

    init {
        view.addView(Ui.button(a, "Копировать", primary = false) { copy() })
        view.addView(Ui.button(a, "Выделить маркером", primary = false) { highlight() })
        view.addView(Ui.button(a, "Поиск", primary = false) { search() })
    }

    fun show(sel: TextSelection) {
        selection = sel
        val parent = view.parent as? View ?: return
        val pw = parent.width; val ph = parent.height
        if (pw <= 0 || ph <= 0) return
        val margin = Ui.dp(a, 8)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(pw - 2 * margin, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = view.measuredWidth; val h = view.measuredHeight
        val anchor = sel.anchorOnScreen
        val gap = Ui.dp(a, 12)
        val topLimit = a.topOverlayBottom() + margin
        var y = anchor.top - h - gap
        if (y < topLimit) y = anchor.bottom + gap
        y = y.coerceIn(topLimit.toFloat(), (ph - h - margin).toFloat().coerceAtLeast(topLimit.toFloat()))
        val x = (anchor.centerX() - w / 2f).coerceIn(margin.toFloat(), (pw - w - margin).toFloat().coerceAtLeast(margin.toFloat()))
        view.translationX = x
        view.translationY = y
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    fun hide() {
        selection = null
        view.visibility = View.GONE
    }

    private fun copy() {
        val s = selection ?: return
        a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Текст из PDF", s.text))
        dismissSelection()
    }

    private fun highlight() {
        if (selection == null) return
        a.view.highlightSelection()
        dismissSelection()
        a.onAnnotationsEdited()
    }

    private fun search() {
        val s = selection ?: return
        dismissSelection()
        a.search.open(s.text)
    }

    private fun dismissSelection() {
        hide()
        a.view.clearSelection()
    }
}
