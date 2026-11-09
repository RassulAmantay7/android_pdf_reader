package com.rassul.pdfreader.core.geometry

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Целочисленный размер растра. */
data class PixelSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * Участок страницы для резкой дорисовки.
 * region* — прямоугольник на экране (целые пиксели), он же размер Bitmap.
 * Матрица для PdfRenderer.Page.render: scale(pxPerPt) затем translate(translateX, translateY):
 *   пиксель_битмапа = точка_страницы(pt) · pxPerPt + translate.
 * (Page.render: «transform — матрица из пространства страницы в пространство битмапа»,
 *  developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#render.)
 */
data class SharpRegion(
    val regionLeft: Int, val regionTop: Int, val regionRight: Int, val regionBottom: Int,
    val pxPerPt: Float, val translateX: Float, val translateY: Float,
) {
    val width: Int get() = regionRight - regionLeft
    val height: Int get() = regionBottom - regionTop
}

object RenderGeometry {

    /**
     * Размер базового растра страницы: ширина targetWidthPx, высота по пропорциям, но не больше
     * maxPixels пикселей (иначе равномерно уменьшаем: k = √(max/area)). Минимум 1×1.
     * Округление ВВЕРХ (ceil), чтобы растр покрывал страницу целиком без полоски по краю
     * (размеры Page.getWidth/getHeight целые, а масштаб дробный).
     */
    fun baseBitmapSize(wPt: Float, hPt: Float, targetWidthPx: Float, maxPixels: Long): PixelSize {
        val w = max(1f, targetWidthPx)
        val h = max(1f, w * (if (wPt > 0f) hPt / wPt else 1f))
        val area = w.toDouble() * h
        val k = if (area > maxPixels && maxPixels > 0) sqrt(maxPixels / area).toFloat() else 1f
        return PixelSize(max(1, ceil(w * k - 1e-3f).toInt()), max(1, ceil(h * k - 1e-3f).toInt()))
    }

    /**
     * Видимый участок страницы для резкой дорисовки.
     * pageScreen — прямоугольник страницы на экране, pagePtWidth — ширина страницы в пунктах.
     * Возвращает null, если страница не видна или участок больше maxPixels.
     */
    fun sharpRegion(pageScreen: Box, pagePtWidth: Float, viewW: Float, viewH: Float, maxPixels: Long): SharpRegion? {
        val vis = pageScreen.intersect(Box(0f, 0f, viewW, viewH)) ?: return null
        val l = floor(vis.left).toInt(); val t = floor(vis.top).toInt()
        val r = ceil(vis.right).toInt(); val b = ceil(vis.bottom).toInt()
        if (r <= l || b <= t) return null
        if ((r - l).toLong() * (b - t) > maxPixels) return null
        val pxPerPt = pageScreen.width / (if (pagePtWidth > 0f) pagePtWidth else 1f)
        return SharpRegion(l, t, r, b, pxPerPt, pageScreen.left - l, pageScreen.top - t)
    }

    /** Толщина линии в пунктах по толщине в экранных пикселях: w_pt = w_px / s, s — px на pt с учётом zoom. */
    fun pxToPt(px: Float, pxPerPt: Float): Float = if (pxPerPt > 0f) px / pxPerPt else px

    /**
     * Экран → страница: (sx + scrollX − L)/s, (sy + scrollY − T)/s, где L, T — левый/верхний край
     * страницы в «экранных px без прокрутки» (контент·zoom), s — px на pt с учётом zoom.
     */
    fun screenToPage(sx: Float, sy: Float, left: Float, top: Float, s: Float, scrollX: Float, scrollY: Float): Pair<Float, Float> =
        ((sx + scrollX - left) / s) to ((sy + scrollY - top) / s)

    /** Порог: при каком отношении «экранный размер / размер базового растра» нужна резкая дорисовка. */
    fun needsSharp(displayedWidth: Float, baseBitmapWidth: Int, threshold: Float = 1.3f): Boolean =
        baseBitmapWidth > 0 && displayedWidth / baseBitmapWidth > threshold
}
