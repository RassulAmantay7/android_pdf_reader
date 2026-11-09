package com.rassul.pdfreader.reader.screen

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Point
import android.graphics.pdf.models.FormEditRecord
import android.graphics.pdf.models.FormWidgetInfo
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import com.rassul.pdfreader.reader.ReaderActivity
import com.rassul.pdfreader.ui.Icon
import com.rassul.pdfreader.ui.SheetItem
import com.rassul.pdfreader.ui.Ui
import java.util.concurrent.RejectedExecutionException

/**
 * Заполнение полей AcroForm через PdfRenderer.Page.applyEdit (API 35).
 * FormEditRecord.Builder(type, pageNumber, widgetIndex) — порядок сверен по javap.
 */
internal class FormController(private val a: ReaderActivity) {

    fun onWidgetTapped(page: Int, w: FormWidgetInfo) {
        if (a.doc == null) return
        if (w.isReadOnly) { Ui.toast(a, "Поле только для чтения"); return }
        when (w.widgetType) {
            FormWidgetInfo.WIDGET_TYPE_TEXTFIELD -> editText(page, w)
            FormWidgetInfo.WIDGET_TYPE_CHECKBOX,
            FormWidgetInfo.WIDGET_TYPE_RADIOBUTTON,
            FormWidgetInfo.WIDGET_TYPE_PUSHBUTTON -> click(page, w)
            FormWidgetInfo.WIDGET_TYPE_COMBOBOX,
            FormWidgetInfo.WIDGET_TYPE_LISTBOX -> choose(page, w)
            FormWidgetInfo.WIDGET_TYPE_SIGNATURE -> Ui.toast(a, "Поле электронной подписи не поддерживается")
            else -> Ui.toast(a, "Этот тип поля не поддерживается")
        }
    }

    private fun label(w: FormWidgetInfo, fallback: String): String =
        w.accessibilityLabel?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: fallback

    private fun editText(page: Int, w: FormWidgetInfo, title: String = label(w, "Текстовое поле")) {
        val max = w.maxLength
        Ui.showPrompt(
            a, title,
            hint = if (max > 0) "Введите текст (до $max символов)" else "Введите текст",
            initial = w.textValue ?: "",
            multiline = w.isMultiLineText,
            okText = "Готово",
            validate = { s -> if (max > 0 && s.length > max) "Не больше $max символов" else null },
        ) { text ->
            apply(page, FormEditRecord.Builder(FormEditRecord.EDIT_TYPE_SET_TEXT, page, w.widgetIndex).setText(text).build())
        }
    }

    private fun click(page: Int, w: FormWidgetInfo) {
        val r = w.widgetRect
        val rec = FormEditRecord.Builder(FormEditRecord.EDIT_TYPE_CLICK, page, w.widgetIndex)
            .setClickPoint(Point(r.centerX(), r.centerY()))
            .build()
        apply(page, rec)
    }

    private fun choose(page: Int, w: FormWidgetInfo) {
        val items = w.listItems ?: emptyList()
        val editableCombo = w.widgetType == FormWidgetInfo.WIDGET_TYPE_COMBOBOX && w.isEditableText
        val title = label(w, if (w.widgetType == FormWidgetInfo.WIDGET_TYPE_COMBOBOX) "Выпадающий список" else "Список")
        if (items.isEmpty()) {
            if (editableCombo) editText(page, w, title) else Ui.toast(a, "В списке нет вариантов")
            return
        }
        if (w.isMultiSelect) { chooseMulti(page, w, title); return }
        var dlg: Dialog? = null
        val sheet = ArrayList<SheetItem>()
        items.forEachIndexed { i, item ->
            sheet += SheetItem(
                text = item.label?.ifBlank { "(пусто)" } ?: "(пусто)",
                icon = if (item.isSelected) Icon.CHECK else null,
            ) {
                dlg?.dismiss()
                apply(page, FormEditRecord.Builder(FormEditRecord.EDIT_TYPE_SET_INDICES, page, w.widgetIndex)
                    .setSelectedIndices(intArrayOf(i)).build())
            }
        }
        if (editableCombo) {
            sheet += SheetItem("Свой вариант…", icon = Icon.EDIT) { dlg?.dismiss(); editText(page, w, title) }
        }
        dlg = Ui.showListSheet(a, title, sheet)
    }

    private fun chooseMulti(page: Int, w: FormWidgetInfo, title: String) {
        val c = Ui.colors
        val items = w.listItems
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val boxes = items.map { item ->
            CheckBox(a).apply {
                text = item.label?.ifBlank { "(пусто)" } ?: "(пусто)"
                isChecked = item.isSelected
                setTextColor(c.text)
                textSize = 16f
                buttonTintList = ColorStateList.valueOf(c.primary)
                minHeight = Ui.dp(a, 48)
            }.also { list.addView(it) }
        }
        val scroll = ScrollView(a).apply { addView(list) }
        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16))
        }
        val maxH = (a.resources.displayMetrics.heightPixels * 0.5f).toInt()
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (items.size * Ui.dp(a, 48) > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.body(a, "Можно выбрать несколько вариантов", secondary = true).apply {
            setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8))
        })
        var dlg: Dialog? = null
        content.addView(Ui.button(a, "Готово", primary = true) {
            dlg?.dismiss()
            val idx = boxes.indices.filter { boxes[it].isChecked }.toIntArray()
            apply(page, FormEditRecord.Builder(FormEditRecord.EDIT_TYPE_SET_INDICES, page, w.widgetIndex)
                .setSelectedIndices(idx).build())
        })
        dlg = Ui.showSheet(a, title, content)
    }

    private fun apply(page: Int, rec: FormEditRecord) {
        val d = a.doc ?: return
        try {
            d.worker.execute {
                val ok = try { d.runOnPage(page) { it.applyEdit(rec) }; true } catch (_: Throwable) { false }
                a.bg.ui {
                    if (a.doc !== d) return@ui
                    if (ok) {
                        d.formsModified = true
                        a.view.invalidatePage(page)
                        a.updateDirty()
                    } else {
                        Ui.toast(a, "Не удалось изменить поле")
                    }
                }
            }
        } catch (_: RejectedExecutionException) { }
    }
}
