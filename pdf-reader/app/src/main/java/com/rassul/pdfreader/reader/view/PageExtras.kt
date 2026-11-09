package com.rassul.pdfreader.reader.view

import android.graphics.pdf.content.PdfPageGotoLinkContent
import android.graphics.pdf.content.PdfPageLinkContent
import android.graphics.pdf.models.FormWidgetInfo

/**
 * Ссылки и поля формы страницы (координаты — пункты страницы, как у PdfRenderer).
 * Загружаются на worker при первом касании страницы и кэшируются (сброс — invalidatePage).
 */
internal class PageExtras(
    val links: List<PdfPageLinkContent>,
    val gotos: List<PdfPageGotoLinkContent>,
    val widgets: List<FormWidgetInfo>,
) {
    fun externalLinkAt(x: Float, y: Float): PdfPageLinkContent? =
        links.firstOrNull { l -> l.bounds.any { it.contains(x, y) } }

    fun gotoAt(x: Float, y: Float): PdfPageGotoLinkContent? =
        gotos.firstOrNull { l -> l.bounds.any { it.contains(x, y) } }

    fun widgetAt(x: Float, y: Float): FormWidgetInfo? =
        widgets.firstOrNull { w ->
            !w.isReadOnly && w.widgetType != FormWidgetInfo.WIDGET_TYPE_UNKNOWN &&
                w.widgetRect.let { r -> x >= r.left && x <= r.right && y >= r.top && y <= r.bottom }
        }
}

/** Небольшой LRU для PageExtras (UI-поток). */
internal class PageExtrasCache(private val max: Int = 32) {
    private val map = object : LinkedHashMap<Int, PageExtras>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageExtras>?): Boolean = size > max
    }
    operator fun get(page: Int): PageExtras? = map[page]
    fun put(page: Int, e: PageExtras) { map[page] = e }
    fun remove(page: Int) { map.remove(page) }
    fun clear() = map.clear()
}
