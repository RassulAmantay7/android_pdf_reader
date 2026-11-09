package com.rassul.pdfreader.core.geometry

/**
 * Прямоугольник (Float), left<=right, top<=bottom. Без Android — чтобы геометрию можно было
 * тестировать на JVM. Используется в трёх системах координат (см. Viewport):
 *  - «контент» — пиксели ленты при zoom = 1;
 *  - «экран» — пиксели View;
 *  - «страница» — пункты PDF (1/72″, ISO 32000-1 §8.3.2.3), начало слева сверху.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** Пересечение или null, если прямоугольники не пересекаются (касание краем — тоже null). */
    fun intersect(o: Box): Box? {
        val l = maxOf(left, o.left); val t = maxOf(top, o.top)
        val r = minOf(right, o.right); val b = minOf(bottom, o.bottom)
        return if (r > l && b > t) Box(l, t, r, b) else null
    }

    fun union(o: Box): Box = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    fun offset(dx: Float, dy: Float): Box = Box(left + dx, top + dy, right + dx, bottom + dy)

    fun scale(s: Float): Box = Box(left * s, top * s, right * s, bottom * s)

    fun inset(d: Float): Box = Box(left + d, top + d, right - d, bottom - d)
}
