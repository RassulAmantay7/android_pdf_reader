package com.rassul.pdfreader.core.geometry

/**
 * Окно просмотра над «контентом» (лента страниц или одна страница) размером contentW×contentH
 * пикселей при zoom = 1.
 *
 * Преобразование (аффинное, без поворота):
 *     screen = content · zoom − scroll
 *     content = (screen + scroll) / zoom
 * scroll — в «экранных» пикселях (т.е. уже умноженных на zoom). Если контент меньше окна
 * по оси, scroll отрицательный и контент центрируется (см. clampAxis).
 */
data class Viewport(
    val viewW: Float,
    val viewH: Float,
    val contentW: Float,
    val contentH: Float,
    val zoom: Float,
    val scrollX: Float,
    val scrollY: Float,
) {
    fun toScreenX(cx: Float): Float = cx * zoom - scrollX
    fun toScreenY(cy: Float): Float = cy * zoom - scrollY
    fun toContentX(sx: Float): Float = (sx + scrollX) / zoom
    fun toContentY(sy: Float): Float = (sy + scrollY) / zoom

    fun toScreen(b: Box): Box = Box(toScreenX(b.left), toScreenY(b.top), toScreenX(b.right), toScreenY(b.bottom))
    fun toContent(b: Box): Box = Box(toContentX(b.left), toContentY(b.top), toContentX(b.right), toContentY(b.bottom))

    /** Видимая часть контента (в координатах контента). */
    val visibleContent: Box get() = Box(toContentX(0f), toContentY(0f), toContentX(viewW), toContentY(viewH))

    val minScrollX: Float get() = axisMin(contentW * zoom, viewW)
    val maxScrollX: Float get() = axisMax(contentW * zoom, viewW)
    val minScrollY: Float get() = axisMin(contentH * zoom, viewH)
    val maxScrollY: Float get() = axisMax(contentH * zoom, viewH)

    /** Ограничить прокрутку границами контента (с центрированием, если контент меньше окна). */
    fun clamped(): Viewport = copy(
        scrollX = clampAxis(scrollX, contentW * zoom, viewW),
        scrollY = clampAxis(scrollY, contentH * zoom, viewH),
    )

    fun withScroll(x: Float, y: Float): Viewport = copy(scrollX = x, scrollY = y)

    /**
     * Сменить масштаб так, чтобы точка контента под фокусом (fx, fy — экранные) осталась под ним.
     * Из screen = c·z − s: c = (f + s)/z ⇒ s' = c·z' − f = (f + s)·z'/z − f.
     * Результат НЕ ограничен (clamped() вызывающий решает сам).
     */
    fun zoomAround(newZoom: Float, fx: Float, fy: Float): Viewport {
        val k = newZoom / zoom
        return copy(zoom = newZoom, scrollX = (fx + scrollX) * k - fx, scrollY = (fy + scrollY) * k - fy)
    }

    companion object {
        /** Нижняя граница scroll по оси: при контенте меньше окна — отрицательная (центрирование). */
        fun axisMin(scaledContent: Float, view: Float): Float =
            if (scaledContent <= view) -(view - scaledContent) / 2f else 0f

        fun axisMax(scaledContent: Float, view: Float): Float =
            if (scaledContent <= view) -(view - scaledContent) / 2f else scaledContent - view

        fun clampAxis(scroll: Float, scaledContent: Float, view: Float): Float =
            scroll.coerceIn(axisMin(scaledContent, view), axisMax(scaledContent, view))

        /**
         * Сдвиг прокрутки на delta с «переливом»: возвращает (новый scroll в границах, остаток,
         * который не поместился). Остаток в режиме PAGED идёт на перелистывание.
         */
        fun panWithOverflow(scroll: Float, delta: Float, min: Float, max: Float): Pair<Float, Float> {
            val want = scroll + delta
            val got = want.coerceIn(min, max)
            return got to (want - got)
        }
    }
}

/** Ограничение масштаба. */
fun clampZoom(z: Float, min: Float, max: Float): Float = if (z.isNaN()) min else z.coerceIn(min, max)

/** Двойной тап: из «почти исходного» масштаба — в увеличенный, иначе — обратно в 1×. */
fun doubleTapTargetZoom(current: Float, zoomed: Float = 2.5f, base: Float = 1f): Float =
    if (current < (base + zoomed) / 2f) zoomed else base

/**
 * Прокрутка по оси, при которой точка pos (экранные px БЕЗ учёта scroll, т.е. content·zoom)
 * оказывается видна с отступом margin. Если уже видна — scroll не меняется, иначе точка
 * ставится на треть окна (чтобы был виден и контекст над ней).
 */
fun scrollToShow(scroll: Float, pos: Float, view: Float, margin: Float): Float {
    val m = minOf(margin, view / 4f)
    return if (pos >= scroll + m && pos <= scroll + view - m) scroll else pos - view / 3f
}
