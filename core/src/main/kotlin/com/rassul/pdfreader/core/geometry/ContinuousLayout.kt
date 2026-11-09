package com.rassul.pdfreader.core.geometry

/**
 * Раскладка вертикальной ленты страниц (режим CONTINUOUS) при zoom = 1.
 *
 * Каждая страница вписывается «по ширине»: ширина = viewWidth − 2·m (m = sideMargin), высота —
 * по пропорциям (страницы разного размера и альбомные читаются одинаково удобно):
 *   s_i = (W_view − 2m)/w_i;  T_0 = m;  T_{i+1} = T_i + h_i·s_i + g;  высота = T_last + h_last·s + m.
 * Зазор между страницами g = gap; в полосе зазора «страницы нет» (pageUnder → −1).
 * Ширина контента = viewWidth. При zoom z всё умножается на z (Viewport), так что поля и зазоры
 * масштабируются вместе со страницами и формула фокуса масштабирования точна; для надёжности
 * View всё равно масштабирует через якорь на странице (zoomAnchored).
 *
 * pageScale[i] — пикселей контента на пункт PDF (1 pt = 1/72″, ISO 32000-1 §8.3.2.3)
 * для страницы i при zoom = 1.
 */
class ContinuousLayout(
    pageWidthsPt: FloatArray,
    pageHeightsPt: FloatArray,
    val viewWidth: Float,
    val gap: Float,
    val sideMargin: Float = 0f,
) {
    val pageCount: Int = pageWidthsPt.size
    private val tops = FloatArray(pageCount)
    private val heights = FloatArray(pageCount)
    private val scales = FloatArray(pageCount)
    private val pageWidthPx = maxOf(1f, viewWidth - 2 * sideMargin)
    val contentWidth: Float = viewWidth
    val contentHeight: Float

    init {
        require(pageHeightsPt.size == pageCount) { "sizes mismatch" }
        // Накопление T_i в Double: при тысячах страниц сумма во Float набирает ошибку
        // (ulp(3·10^6) = 0.25 px на каждом шаге); храним уже округлённое значение.
        var y = sideMargin.toDouble()
        for (i in 0 until pageCount) {
            val wPt = pageWidthsPt[i].takeIf { it > 0f } ?: 1f
            val hPt = pageHeightsPt[i].takeIf { it > 0f } ?: 1f
            val s = pageWidthPx / wPt
            scales[i] = s
            tops[i] = y.toFloat()
            heights[i] = hPt * s
            y += heights[i].toDouble() + gap
        }
        contentHeight = if (pageCount == 0) 0f else (y - gap + sideMargin).toFloat()
    }

    fun pageScale(i: Int): Float = scales[i]
    fun pageTop(i: Int): Float = tops[i]
    fun pageHeight(i: Int): Float = heights[i]
    fun pageBox(i: Int): Box = Box(sideMargin, tops[i], sideMargin + pageWidthPx, tops[i] + heights[i])

    /** Страница, чья полоса [top − gap/2, bottom + gap/2) содержит y (с ограничением на края). */
    fun pageAt(contentY: Float): Int {
        if (pageCount == 0) return -1
        var lo = 0; var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] - gap / 2f <= contentY) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Страницы, пересекающие полосу [top, bottom] (координаты контента). Пусто, если нет страниц. */
    fun visibleRange(top: Float, bottom: Float): IntRange {
        if (pageCount == 0 || bottom < top) return IntRange.EMPTY
        var first = pageAt(top)
        if (tops[first] + heights[first] < top && first < pageCount - 1) first++
        var last = pageAt(bottom)
        if (tops[last] > bottom && last > first) last--
        return first..maxOf(first, last)
    }

    /** Самая видимая страница (по высоте видимой части); при равенстве — верхняя. */
    fun mostVisiblePage(top: Float, bottom: Float): Int {
        if (pageCount == 0) return -1
        var best = pageAt(top); var bestH = -1f
        for (i in visibleRange(top, bottom)) {
            val h = minOf(bottom, tops[i] + heights[i]) - maxOf(top, tops[i])
            if (h > bestH + 0.5f) { best = i; bestH = h }
        }
        return best
    }

    /**
     * Позиция чтения: страница под верхним краем окна и доля её высоты, ушедшая выше края
     * (0 — верх страницы у края окна). Отрицательная доля невозможна: ограничиваем [0, 1].
     */
    fun positionAt(contentTop: Float): Pair<Int, Float> {
        if (pageCount == 0) return 0 to 0f
        val p = pageAt(contentTop)
        val f = ((contentTop - tops[p]) / heights[p]).coerceIn(0f, 1f)
        return p to f
    }

    /**
     * Обратное к positionAt: Y контента для (страница, доля). Если доля 0 — оставляем сверху
     * половину зазора (ровно граница полосы страницы, так что positionAt вернёт ту же страницу).
     */
    fun contentYFor(page: Int, fraction: Float): Float {
        if (pageCount == 0) return 0f
        val p = page.coerceIn(0, pageCount - 1)
        val f = fraction.coerceIn(0f, 1f)
        return if (f == 0f) (if (p == 0) 0f else tops[p] - gap / 2f) else tops[p] + f * heights[p]
    }

    /** Страница под точкой контента или −1, если точка в зазоре/поле (двоичный поиск). */
    fun pageUnder(cx: Float, cy: Float): Int {
        if (pageCount == 0) return -1
        val p = pageAt(cy)
        return if (pageBox(p).contains(cx, cy)) p else -1
    }

    /**
     * Масштабирование с якорем: точка страницы под фокусом (fx, fy) до жеста остаётся под ним
     * после смены масштаба. Якорь (i, u, v) = экран→страница(f); затем подбираем scroll так,
     * чтобы страница→экран(i, u, v) = f. Если фокус в зазоре — якорь в координатах контента.
     * Результат не ограничен (clamped() — у вызывающего).
     */
    fun zoomAnchored(vp: Viewport, newZoom: Float, fx: Float, fy: Float): Viewport {
        val cx = vp.toContentX(fx); val cy = vp.toContentY(fy)
        val i = pageUnder(cx, cy)
        val (ax, ay) = if (i >= 0) {
            val u = (cx - sideMargin) / scales[i]; val v = (cy - tops[i]) / scales[i]
            pointToContent(i, u, v)
        } else cx to cy
        return vp.copy(zoom = newZoom, scrollX = ax * newZoom - fx, scrollY = ay * newZoom - fy)
    }

    /** Точка страницы (пункты) → координаты контента. */
    fun pointToContent(page: Int, xPt: Float, yPt: Float): Pair<Float, Float> =
        (sideMargin + xPt * scales[page]) to (tops[page] + yPt * scales[page])
}
