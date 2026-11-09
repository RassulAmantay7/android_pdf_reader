package com.rassul.pdfreader.core.annot

/*
 * Модель пометок, которые пользователь создаёт в текущем сеансе (ещё не записаны в PDF).
 *
 * СИСТЕМА КООРДИНАТ (общая для всего проекта, см. docs/contracts.md):
 *   «Координаты страницы» = пункты PDF (1 pt = 1/72 дюйма), начало — ЛЕВЫЙ ВЕРХНИЙ угол
 *   видимой (уже повёрнутой) страницы, ось Y вниз. Это та же система, что у
 *   android.graphics.pdf (PdfRenderer: PageMatchBounds, PageSelection, FormWidgetInfo).
 *   Перевод в пользовательское пространство PDF (начало внизу слева, /MediaBox, /Rotate)
 *   делает только PdfAnnotationWriter.
 */

/** Точка в координатах страницы (пункты, начало слева сверху). */
data class PtPoint(val x: Float, val y: Float)

/** Прямоугольник в координатах страницы (пункты, начало слева сверху), left<right, top<bottom. */
data class PtRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun union(o: PtRect) = PtRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** Цвет ARGB как в android.graphics.Color (0xAARRGGBB). */
typealias Argb = Int

sealed class Annotation {
    abstract val pageIndex: Int
    abstract val bounds: PtRect
}

/** Рисунок пером или маркером «от руки». Записывается как /Ink. */
data class InkAnnotation(
    override val pageIndex: Int,
    val strokes: List<List<PtPoint>>,
    val color: Argb,
    /** Толщина линии в пунктах. */
    val widthPt: Float,
    /** Непрозрачность 0..1 (маркер ≈ 0.35). */
    val opacity: Float = 1f,
) : Annotation() {
    override val bounds: PtRect = run {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in strokes) for (p in s) { l = minOf(l, p.x); t = minOf(t, p.y); r = maxOf(r, p.x); b = maxOf(b, p.y) }
        if (l > r) PtRect(0f, 0f, 0f, 0f) else { val h = widthPt / 2; PtRect(l - h, t - h, r + h, b + h) }
    }
}

/** Выделение текста маркером. Каждый прямоугольник — одна строка выделения. Записывается как /Highlight. */
data class HighlightAnnotation(
    override val pageIndex: Int,
    val rects: List<PtRect>,
    val color: Argb,
    /** Выделенный текст (для /Contents), может быть пустым. */
    val text: String = "",
) : Annotation() {
    override val bounds: PtRect = rects.reduceOrNull { a, b -> a.union(b) } ?: PtRect(0f, 0f, 0f, 0f)
}

/** Заметка-«стикер» в точке. Записывается как /Text (иконка /Note) с /Contents. */
data class NoteAnnotation(
    override val pageIndex: Int,
    /** Левый верхний угол иконки заметки. */
    val at: PtPoint,
    val text: String,
    val color: Argb,
) : Annotation() {
    override val bounds: PtRect = PtRect(at.x, at.y, at.x + ICON_SIZE_PT, at.y + ICON_SIZE_PT)
    companion object { const val ICON_SIZE_PT = 20f }
}

/**
 * Список пометок сеанса с отменой/повтором. Не потокобезопасен — использовать из UI-потока.
 */
class AnnotationSession {
    private val items = ArrayList<Annotation>()
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()

    private sealed class Op {
        data class Add(val a: Annotation) : Op()
        data class Remove(val a: Annotation, val index: Int) : Op()
        data class Replace(val old: Annotation, val new: Annotation, val index: Int) : Op()
    }

    val all: List<Annotation> get() = items.toList()
    val isEmpty: Boolean get() = items.isEmpty()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun onPage(pageIndex: Int): List<Annotation> = items.filter { it.pageIndex == pageIndex }

    fun add(a: Annotation) { items.add(a); push(Op.Add(a)) }

    fun remove(a: Annotation): Boolean {
        val i = items.indexOf(a); if (i < 0) return false
        items.removeAt(i); push(Op.Remove(a, i)); return true
    }

    fun replace(old: Annotation, new: Annotation): Boolean {
        val i = items.indexOf(old); if (i < 0) return false
        items[i] = new; push(Op.Replace(old, new, i)); return true
    }

    fun undo(): Boolean {
        val op = undoStack.removeLastOrNull() ?: return false
        when (op) {
            is Op.Add -> items.remove(op.a)
            is Op.Remove -> items.add(op.index.coerceAtMost(items.size), op.a)
            is Op.Replace -> { val i = items.indexOf(op.new); if (i >= 0) items[i] = op.old }
        }
        redoStack.addLast(op); return true
    }

    fun redo(): Boolean {
        val op = redoStack.removeLastOrNull() ?: return false
        when (op) {
            is Op.Add -> items.add(op.a)
            is Op.Remove -> items.remove(op.a)
            is Op.Replace -> { val i = items.indexOf(op.old); if (i >= 0) items[i] = op.new }
        }
        undoStack.addLast(op); return true
    }

    /** После успешного сохранения в файл. */
    fun clear() { items.clear(); undoStack.clear(); redoStack.clear() }

    private fun push(op: Op) { undoStack.addLast(op); redoStack.clear() }
}
