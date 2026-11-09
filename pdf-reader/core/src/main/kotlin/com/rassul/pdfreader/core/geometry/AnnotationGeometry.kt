package com.rassul.pdfreader.core.geometry

import com.rassul.pdfreader.core.annot.Annotation
import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.core.annot.PtPoint
import kotlin.math.hypot

/** Отрезок квадратичной кривой Безье: контрольная точка (cx, cy) и конец (x, y). */
data class QuadSeg(val cx: Float, val cy: Float, val x: Float, val y: Float)

object AnnotationGeometry {

    /** Расстояние от точки до отрезка AB (проекция с ограничением параметра t в [0, 1]). */
    fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Попадание в линию толщиной widthPt с допуском tolPt (всё в пунктах). */
    fun hitsStroke(stroke: List<PtPoint>, x: Float, y: Float, widthPt: Float, tolPt: Float): Boolean {
        val r = widthPt / 2f + tolPt
        if (stroke.isEmpty()) return false
        if (stroke.size == 1) return hypot(x - stroke[0].x, y - stroke[0].y) <= r
        for (i in 1 until stroke.size) {
            val a = stroke[i - 1]; val b = stroke[i]
            if (distToSegment(x, y, a.x, a.y, b.x, b.y) <= r) return true
        }
        return false
    }

    /**
     * Попадание точки (пункты страницы) в пометку. noteSizePt — фактический размер значка
     * заметки в пунктах (на экране он может быть крупнее ICON_SIZE_PT, чтобы в него можно было попасть).
     */
    fun hits(a: Annotation, x: Float, y: Float, tolPt: Float, noteSizePt: Float = NoteAnnotation.ICON_SIZE_PT): Boolean = when (a) {
        is InkAnnotation -> {
            val b = a.bounds
            if (x < b.left - tolPt || x > b.right + tolPt || y < b.top - tolPt || y > b.bottom + tolPt) false
            else a.strokes.any { hitsStroke(it, x, y, a.widthPt, tolPt) }
        }
        is HighlightAnnotation -> a.rects.any { x >= it.left - tolPt && x <= it.right + tolPt && y >= it.top - tolPt && y <= it.bottom + tolPt }
        is NoteAnnotation -> x >= a.at.x - tolPt && x <= a.at.x + noteSizePt + tolPt && y >= a.at.y - tolPt && y <= a.at.y + noteSizePt + tolPt
    }

    /**
     * Сглаживание ломаной квадратичными кривыми «через середины»: вершины ломаной становятся
     * контрольными точками, а кривая проходит через середины соседних отрезков — так линия
     * получается гладкой (непрерывная касательная) и не «раздувается». Классический приём,
     * см., напр., «Smoothing a freehand drawing» (Path.quadTo по средним точкам), android-developers.
     * Возвращает: первую точку (moveTo) и список сегментов; последний сегмент заканчивается в
     * последней точке ломаной.
     */
    fun smooth(points: List<PtPoint>): Pair<PtPoint, List<QuadSeg>>? {
        if (points.isEmpty()) return null
        val first = points[0]
        if (points.size == 1) return first to listOf(QuadSeg(first.x, first.y, first.x + 0.01f, first.y))
        if (points.size == 2) return first to listOf(QuadSeg(first.x, first.y, points[1].x, points[1].y))
        val segs = ArrayList<QuadSeg>(points.size)
        // Первый участок — прямая до середины первого отрезка (как контрольную берём начало).
        val m0x = (points[0].x + points[1].x) / 2f; val m0y = (points[0].y + points[1].y) / 2f
        segs.add(QuadSeg(points[0].x, points[0].y, m0x, m0y))
        for (i in 1 until points.size - 1) {
            val p = points[i]; val n = points[i + 1]
            segs.add(QuadSeg(p.x, p.y, (p.x + n.x) / 2f, (p.y + n.y) / 2f))
        }
        val last = points.last()
        segs.add(QuadSeg(last.x, last.y, last.x, last.y))
        return first to segs
    }
}
