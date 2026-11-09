package com.rassul.pdfreader.reader.screen

import android.app.Dialog
import com.rassul.pdfreader.core.pdf.OutlineItem
import com.rassul.pdfreader.core.pdf.PdfFormatException
import com.rassul.pdfreader.core.pdf.PdfOutlineReader
import com.rassul.pdfreader.reader.PdfDoc
import com.rassul.pdfreader.reader.ReaderActivity
import com.rassul.pdfreader.ui.SheetItem
import com.rassul.pdfreader.ui.Ui

/** Оглавление (/Outlines) документа: чтение байтов файла в фоне + PdfOutlineReader. */
internal class OutlineController(private val a: ReaderActivity) {
    companion object {
        /** Не больше 100 МБ и не больше четверти кучи (массив байтов файла + разбор). */
        val LIMIT: Long get() = minOf(100L * FileIo.MB, Runtime.getRuntime().maxMemory() / 4)
    }

    private var cacheFor: PdfDoc? = null
    private var cache: List<OutlineItem>? = null
    private var loading = false

    fun reset() { cache = null; cacheFor = null; loading = false }

    fun show() {
        val d = a.doc ?: return
        val cached = cache
        if (cached != null && cacheFor === d) { present(d, cached); return }
        if (loading) return
        loading = true
        a.showBusy("Читаем оглавление…")
        val uri = d.uri
        a.bg.runIo {
            var items: List<OutlineItem>? = null
            var error: String? = null
            try {
                val bytes = FileIo.readAllCapped(a.applicationContext, uri, LIMIT)
                items = PdfOutlineReader.read(bytes)
            } catch (t: Throwable) {
                error = when (t) {
                    is TooLargeException -> "Оглавление недоступно для очень больших файлов"
                    is PdfFormatException -> t.message ?: "Не удалось прочитать оглавление"
                    is SecurityException -> "Нет доступа к файлу"
                    is java.io.IOException -> "Не удалось прочитать файл"
                    is OutOfMemoryError -> "Оглавление недоступно для очень больших файлов"
                    else -> "Не удалось прочитать оглавление"
                }
            }
            a.bg.ui {
                loading = false
                a.hideBusy()
                if (a.doc !== d) return@ui
                if (error != null) { Ui.toast(a, error!!); return@ui }
                cache = items; cacheFor = d
                present(d, items ?: emptyList())
            }
        }
    }

    private fun present(d: PdfDoc, items: List<OutlineItem>) {
        if (items.isEmpty()) { Ui.toast(a, "В документе нет оглавления"); return }
        var dlg: Dialog? = null
        val out = ArrayList<SheetItem>()
        fun add(list: List<OutlineItem>, depth: Int) {
            for (it in list) {
                val page = it.pageIndex
                out += SheetItem(
                    text = it.title.trim().ifEmpty { "(без названия)" },
                    secondary = page?.let { p -> "стр. ${p + 1}" },
                    level = maxOf(it.level, depth),
                ) {
                    dlg?.dismiss()
                    if (page != null && page in 0 until d.pageCount) a.view.goToPage(page)
                    else Ui.toast(a, "Не удалось определить страницу этого раздела")
                }
                if (it.children.isNotEmpty()) add(it.children, depth + 1)
            }
        }
        add(items, 0)
        dlg = Ui.showListSheet(a, "Оглавление", out)
    }
}
