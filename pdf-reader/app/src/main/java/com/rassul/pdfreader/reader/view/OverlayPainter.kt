package com.rassul.pdfreader.reader.view

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.rassul.pdfreader.core.annot.Annotation
import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.core.annot.PtPoint
import com.rassul.pdfreader.core.geometry.AnnotationGeometry
import java.util.IdentityHashMap

/**
 * Рисование оверлеев страницы. Холст уже переведён в координаты страницы (пункты):
 * translate(левый верх страницы на экране) + scale(px на pt). Поэтому толщины задаются в пунктах
 * и масштабируются вместе с zoom (w_pt = w_px / s).
 * Цвета оверлеев — свои константы (Ui.* пока не готов).
 */
internal class OverlayPainter {
    companion object {
        const val SEARCH_FILL = 0x55FFD54F
        const val SEARCH_CURRENT_FILL = 0x88FF9800.toInt()
        const val SEARCH_CURRENT_STROKE = 0xFFE65100.toInt()
        const val SELECTION_FILL = 0x553D8BFF
        const val HANDLE_COLOR = 0xFF2F6FEB.toInt()
        const val NOTE_COLOR_DEFAULT = 0xFFFFC107.toInt()
        /** Непрозрачность заливки HighlightAnnotation на экране. */
        const val HIGHLIGHT_ALPHA = 0.40f
    }

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathCache = IdentityHashMap<InkAnnotation, Path>()
    private val tmpRect = RectF()

    /** Путь из точек со сглаживанием квадратичными кривыми (AnnotationGeometry.smooth). */
    fun buildPath(points: List<PtPoint>, into: Path = Path()): Path {
        val sm = AnnotationGeometry.smooth(points) ?: return into
        into.moveTo(sm.first.x, sm.first.y)
        for (s in sm.second) into.quadTo(s.cx, s.cy, s.x, s.y)
        return into
    }

    private fun inkPath(a: InkAnnotation): Path = pathCache.getOrPut(a) {
        val p = Path(); for (s in a.strokes) buildPath(s, p); p
    }

    /** Убрать из кэша путей пометки, которых больше нет в сеансе. */
    fun retain(alive: Collection<Annotation>) {
        if (pathCache.isEmpty()) return
        val set = java.util.Collections.newSetFromMap(IdentityHashMap<Annotation, Boolean>()).apply { addAll(alive) }
        pathCache.keys.retainAll { it in set }
    }

    fun drawInk(c: Canvas, color: Int, widthPt: Float, opacity: Float, path: Path) {
        inkPaint.color = color
        inkPaint.alpha = (Color.alpha(color) * opacity.coerceIn(0f, 1f)).toInt()
        inkPaint.strokeWidth = widthPt
        c.drawPath(path, inkPaint)
    }

    /**
     * Пометки страницы. noteSizePt — размер значка заметки в пунктах (не меньше ICON_SIZE_PT,
     * на экране — не меньше ~28dp, чтобы попасть пальцем). multiply — режим «маркер умножением»
     * (днём текст под маркером остаётся тёмным).
     */
    fun drawAnnotations(c: Canvas, list: List<Annotation>, noteSizePt: Float, multiply: Boolean) {
        for (a in list) when (a) {
            is InkAnnotation -> drawInk(c, a.color, a.widthPt, a.opacity, inkPath(a))
            is HighlightAnnotation -> {
                fillPaint.color = a.color
                fillPaint.alpha = (255 * HIGHLIGHT_ALPHA).toInt()
                fillPaint.blendMode = if (multiply) BlendMode.MULTIPLY else null
                for (r in a.rects) { tmpRect.set(r.left, r.top, r.right, r.bottom); c.drawRect(tmpRect, fillPaint) }
                fillPaint.blendMode = null
            }
            is NoteAnnotation -> drawNoteIcon(c, a.at.x, a.at.y, noteSizePt, a.color)
        }
    }

    /** Значок-стикер: квадрат со скруглением, загнутый уголок и три «строки». */
    fun drawNoteIcon(c: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val r = size * 0.18f
        notePaint.style = Paint.Style.FILL
        notePaint.color = 0x55000000
        tmpRect.set(x + size * 0.06f, y + size * 0.08f, x + size * 1.06f, y + size * 1.08f)
        c.drawRoundRect(tmpRect, r, r, notePaint) // тень
        notePaint.color = color
        tmpRect.set(x, y, x + size, y + size)
        c.drawRoundRect(tmpRect, r, r, notePaint)
        notePaint.color = 0xCC5D4037.toInt()
        notePaint.strokeWidth = size * 0.07f
        notePaint.style = Paint.Style.STROKE
        notePaint.strokeCap = Paint.Cap.ROUND
        for (i in 1..3) {
            val ly = y + size * (0.22f + 0.2f * i)
            c.drawLine(x + size * 0.22f, ly, x + size * (if (i == 3) 0.6f else 0.78f), ly, notePaint)
        }
    }

    fun drawRects(c: Canvas, rects: List<RectF>, fill: Int, stroke: Int?, strokeWidthPt: Float) {
        fillPaint.color = fill
        for (r in rects) c.drawRect(r, fillPaint)
        if (stroke != null) {
            strokePaint.color = stroke; strokePaint.strokeWidth = strokeWidthPt
            for (r in rects) c.drawRect(r, strokePaint)
        }
    }

    /** Ручка выделения (экранные координаты): «капля» под точкой (x, y). */
    fun drawHandle(c: Canvas, x: Float, y: Float, radius: Float, left: Boolean) {
        fillPaint.color = HANDLE_COLOR
        // Как у системных ручек: круг смещён наружу, острый угол указывает на край выделения.
        if (left) {
            c.drawCircle(x - radius, y + radius, radius, fillPaint)
            c.drawRect(x - radius, y, x, y + radius, fillPaint)
        } else {
            c.drawCircle(x + radius, y + radius, radius, fillPaint)
            c.drawRect(x, y, x + radius, y + radius, fillPaint)
        }
    }
}
