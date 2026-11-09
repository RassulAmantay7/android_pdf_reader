package com.rassul.pdfreader.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/*
 * Набор иконок: векторные пути в сетке 24×24, обводка 2 (в единицах сетки), скругления —
 * в духе Material Symbols Rounded / Lucide. Пути хранятся строками SVG path data —
 * их же экспортирует HTML-модель (scratch) для визуальной проверки.
 *
 * Префиксы строк:  "F:" — только заливка,  "B:" — заливка + обводка,  без префикса — обводка.
 */
internal object IconPaths {
    private const val GEAR =
        "M9.83 5.13L10.35 2.64L13.65 2.64L14.17 5.13L15.32 5.61L17.45 4.22L19.78 6.55L18.39 8.68L18.87 9.83" +
            "L21.36 10.35L21.36 13.65L18.87 14.17L18.39 15.32L19.78 17.45L17.45 19.78L15.32 18.39L14.17 18.87" +
            "L13.65 21.36L10.35 21.36L9.83 18.87L8.68 18.39L6.55 19.78L4.22 17.45L5.61 15.32L5.13 14.17" +
            "L2.64 13.65L2.64 10.35L5.13 9.83L5.61 8.68L4.22 6.55L6.55 4.22L8.68 5.61Z"

    private const val DOC = "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"
    private const val DOC_FOLD = "M14 3v4a1 1 0 0 0 1 1h4"
    private const val BOOKMARK = "M17 3H7a2 2 0 0 0-2 2v16l7-4.5 7 4.5V5a2 2 0 0 0-2-2Z"

    val paths: Map<Icon, List<String>> = mapOf(
        Icon.BACK to listOf("M19 12H5", "M11.5 18.5 5 12l6.5-6.5"),
        Icon.CLOSE to listOf("M18 6 6 18", "M6 6l12 12"),
        Icon.SEARCH to listOf("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0Z", "M20.5 20.5l-4.5-4.5"),
        Icon.MORE to listOf(
            "F:M12 3.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5Z",
            "F:M12 10.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5Z",
            "F:M12 17.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5Z",
        ),
        Icon.SETTINGS to listOf(GEAR, "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0Z"),
        Icon.OPEN to listOf(
            "M3 17.5V6.5A2.5 2.5 0 0 1 5.5 4h3.2a2 2 0 0 1 1.6.8L11.5 6.5h7A2.5 2.5 0 0 1 21 9v8.5a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5Z",
            "M12 10.5v6", "M9 13.5h6",
        ),
        Icon.ADD to listOf("M12 5v14", "M5 12h14"),
        Icon.CHECK to listOf("M4.5 12.5l5 5L19.5 7"),
        Icon.TOC to listOf(
            "M10 6h10", "M10 12h10", "M10 18h10",
            "F:M5 4.75a1.25 1.25 0 1 1 0 2.5a1.25 1.25 0 1 1 0-2.5Z",
            "F:M5 10.75a1.25 1.25 0 1 1 0 2.5a1.25 1.25 0 1 1 0-2.5Z",
            "F:M5 16.75a1.25 1.25 0 1 1 0 2.5a1.25 1.25 0 1 1 0-2.5Z",
        ),
        Icon.BOOKMARK to listOf(BOOKMARK),
        Icon.BOOKMARK_FILLED to listOf("B:$BOOKMARK"),
        Icon.PAGE_GOTO to listOf(DOC, DOC_FOLD, "M8.5 14.5h6", "M12 11.5l3 3-3 3"),
        Icon.PEN to listOf(
            "M15.5 4.5a2.1 2.1 0 0 1 3 0l1 1a2.1 2.1 0 0 1 0 3L9 19l-5 1.5L5.5 15.5Z",
            "M13.5 6.5l4 4",
        ),
        Icon.HIGHLIGHTER to listOf(
            "M16.6 3.9a1.5 1.5 0 0 1 2.1 0l1.4 1.4a1.5 1.5 0 0 1 0 2.1L12 15.5l-3.5-3.5Z",
            "M8.5 12 5 15.5V19h3.5l3.5-3.5",
            "M13 20.5h8",
        ),
        Icon.ERASER to listOf(
            "M8.5 20 3.9 15.4a2 2 0 0 1 0-2.8l8.7-8.7a2 2 0 0 1 2.8 0l4.2 4.2a2 2 0 0 1 0 2.8L11 20Z",
            "M9 9.5l5.5 5.5", "M11 20h9",
        ),
        Icon.NOTE to listOf(
            "M15 20.5H6A2.5 2.5 0 0 1 3.5 18V6A2.5 2.5 0 0 1 6 3.5h12A2.5 2.5 0 0 1 20.5 6v9Z",
            "M20.5 15h-4a1.5 1.5 0 0 0-1.5 1.5v4",
            "M8 9h8", "M8 12.5h5",
        ),
        Icon.UNDO to listOf("M9 14.5 4 9.5l5-5", "M4 9.5h10.5a5.25 5.25 0 0 1 0 10.5H11"),
        Icon.REDO to listOf("M15 14.5l5-5-5-5", "M20 9.5H9.5a5.25 5.25 0 0 0 0 10.5H13"),
        Icon.SAVE to listOf(
            "M5.5 3.5h10.3a2 2 0 0 1 1.4.6l2.7 2.7a2 2 0 0 1 .6 1.4v10.3a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2Z",
            "M8 3.5v4h7v-4", "M7.5 20.5v-5.5a1 1 0 0 1 1-1h7a1 1 0 0 1 1 1v5.5",
        ),
        Icon.COPY to listOf(
            "M10.5 8.5h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z",
            "M15.5 8.5V5.5a2 2 0 0 0-2-2h-8a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h3",
        ),
        Icon.EDIT to listOf(
            "M11.5 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5.5",
            "M17.6 3.4a2 2 0 0 1 2.9 0l.1.1a2 2 0 0 1 0 2.9L12.5 14.5 9 15.5l1-3.5Z",
        ),
        Icon.FORM to listOf(
            "M6 3.5h12a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2Z",
            "M7.5 9l1.5 1.5 2.5-2.5", "M14 9.5h2.5",
            "M8.5 14h7a1 1 0 0 1 1 1v1a1 1 0 0 1-1 1h-7a1 1 0 0 1-1-1v-1a1 1 0 0 1 1-1Z",
        ),
        Icon.MOON to listOf("M20.5 14.2A8.5 8.5 0 1 1 9.8 3.5a6.8 6.8 0 0 0 10.7 10.7Z"),
        Icon.SUN to listOf(
            "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0Z",
            "M12 2.5v1.5", "M12 20v1.5", "M2.5 12H4", "M20 12h1.5",
            "M5.3 5.3l1.1 1.1", "M17.6 17.6l1.1 1.1", "M5.3 18.7l1.1-1.1", "M17.6 6.4l1.1-1.1",
        ),
        Icon.SCROLL_VERTICAL to listOf(
            "M7 2.5h10a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2Z",
            "M7 13.5h10a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2Z",
        ),
        Icon.PAGES_HORIZONTAL to listOf(
            "M12 6.5C10 5 7 4.5 3 5v13.5c4-.5 7 0 9 1.5c2-1.5 5-2 9-1.5V5c-4-.5-7 0-9 1.5Z",
            "M12 6.5V20",
        ),
        Icon.CHEVRON_UP to listOf("M6 15l6-6 6 6"),
        Icon.CHEVRON_DOWN to listOf("M6 9l6 6 6-6"),
        Icon.CHEVRON_LEFT to listOf("M15 6l-6 6 6 6"),
        Icon.CHEVRON_RIGHT to listOf("M9 6l6 6-6 6"),
        Icon.DELETE to listOf(
            "M4 6.5h16",
            "M9 6.5V5a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 5v1.5",
            "M6 6.5l.9 12.2a2 2 0 0 0 2 1.8h6.2a2 2 0 0 0 2-1.8L18 6.5",
            "M10 11v5", "M14 11v5",
        ),
        Icon.PDF_FILE to listOf(DOC, DOC_FOLD, "M8.5 13h7", "M8.5 16.5h4.5"),
    )
}

/** Drawable, рисующий иконку из [IconPaths]. Внутренний размер — 24dp. */
class IconDrawable(icon: Icon, tint: Int, private val sizePx: Int) : Drawable() {
    private class Part(val path: Path, val fill: Boolean, val stroke: Boolean)

    private val parts: List<Part> = (IconPaths.paths[icon] ?: emptyList()).map { s ->
        when {
            s.startsWith("F:") -> Part(SvgPath.parse(s.substring(2)), fill = true, stroke = false)
            s.startsWith("B:") -> Part(SvgPath.parse(s.substring(2)), fill = true, stroke = true)
            else -> Part(SvgPath.parse(s), fill = false, stroke = true)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val scaled = Path()
    private val m = Matrix()
    private var baseAlpha = android.graphics.Color.alpha(tint)
    private var drawAlpha = 255

    var color: Int
        get() = paint.color
        set(v) {
            baseAlpha = android.graphics.Color.alpha(v)
            paint.color = v
            paint.alpha = baseAlpha * drawAlpha / 255
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val side = minOf(b.width(), b.height()).toFloat()
        val s = side / 24f
        m.setScale(s, s)
        m.postTranslate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f)
        paint.strokeWidth = 2f * s
        for (p in parts) {
            p.path.transform(m, scaled)
            if (p.fill) { paint.style = Paint.Style.FILL; canvas.drawPath(scaled, paint) }
            if (p.stroke) { paint.style = Paint.Style.STROKE; canvas.drawPath(scaled, paint) }
        }
    }

    override fun setTintList(tint: ColorStateList?) {
        if (tint != null) color = tint.getColorForState(state, tint.defaultColor)
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        paint.alpha = baseAlpha * alpha / 255
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
}
