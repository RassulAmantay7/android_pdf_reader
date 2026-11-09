package com.rassul.pdfreader.core.geometry

import kotlin.math.abs
import kotlin.math.min

/**
 * Геометрия режима PAGED (одна страница на экран, горизонтальное листание).
 * Контентом Viewport в этом режиме служит сама страница, вписанная целиком: [0, fitW]×[0, fitH].
 */
object PagedGeometry {

    /** Вписать страницу wPt×hPt целиком в окно viewW×viewH с полями margin. Возвращает (ширина, высота, px/pt). */
    fun fit(wPt: Float, hPt: Float, viewW: Float, viewH: Float, margin: Float = 0f): Triple<Float, Float, Float> {
        val w = if (wPt > 0f) wPt else 1f
        val h = if (hPt > 0f) hPt else 1f
        val aw = maxOf(1f, viewW - 2 * margin)
        val ah = maxOf(1f, viewH - 2 * margin)
        val s = min(aw / w, ah / h)
        return Triple(w * s, h * s, s)
    }

    /** Прямоугольник страницы, вписанной в «слот» шириной viewW, сдвинутый на slotOffsetX (экран, zoom = 1). */
    fun slotBox(fitW: Float, fitH: Float, viewW: Float, viewH: Float, slotOffsetX: Float): Box {
        val l = slotOffsetX + (viewW - fitW) / 2f
        val t = (viewH - fitH) / 2f
        return Box(l, t, l + fitW, t + fitH)
    }

    /**
     * «Резиновое» сопротивление при оттягивании за первую/последнюю страницу:
     * смещение растёт всё медленнее (как iOS rubber-band: x·d·c/(d + c·x), c = 0.55).
     * Источник формулы: «UIScrollView rubber band» (G. Allen, 2013), часто цитируемая аппроксимация.
     */
    fun rubberBand(offset: Float, dimension: Float, c: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        val x = abs(offset)
        val r = (x * dimension * c) / (dimension + c * x)
        return if (offset < 0) -r else r
    }

    /**
     * Решение о перелистывании после отпускания пальца.
     * flipOffset > 0 — страницу тянут вправо (к предыдущей), < 0 — влево (к следующей).
     * velocityX — px/с (как у VelocityTracker). Возвращает −1 (назад), +1 (вперёд) или 0 (вернуть).
     */
    fun decideFlip(
        flipOffset: Float, velocityX: Float, viewW: Float,
        hasPrev: Boolean, hasNext: Boolean,
        distanceFraction: Float = 0.25f, minFlingVelocity: Float = 800f,
    ): Int {
        val dir = when {
            // Быстрый жест в ту же сторону, что и смещение, — листаем, даже если сдвиг мал.
            abs(velocityX) >= minFlingVelocity && velocityX * flipOffset >= 0f && flipOffset != 0f ->
                if (velocityX < 0f) 1 else -1
            abs(velocityX) >= minFlingVelocity && velocityX * flipOffset < 0f -> 0
            flipOffset <= -viewW * distanceFraction -> 1
            flipOffset >= viewW * distanceFraction -> -1
            else -> 0
        }
        return when {
            dir == 1 && !hasNext -> 0
            dir == -1 && !hasPrev -> 0
            else -> dir
        }
    }
}
