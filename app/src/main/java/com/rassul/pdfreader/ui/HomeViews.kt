package com.rassul.pdfreader.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Обложка документа: картинка по ширине, выравнивание по верху; без картинки — плейсхолдер. Высота = ширина × ratio. */
class CoverView(ctx: Context, private val ratio: Float = 1.3f) : View(ctx) {
    private val p = Ui.colors
    private var bmp: Bitmap? = null
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint().apply { color = if (p.isDark) Tokens.blend(p.text, p.surface, 0.07f) else Tokens.blend(p.text, p.surface, 0.04f) }
    private val placeholder = IconDrawable(Icon.PDF_FILE, Tokens.withAlpha(p.textSecondary, 0.7f), Ui.dp(ctx, 40))
    private val m = Matrix()
    private var fadeAnim: ValueAnimator? = null

    fun setBitmap(b: Bitmap?, animate: Boolean) {
        if (b === bmp) return
        bmp = b
        fadeAnim?.cancel()
        if (b != null && animate) {
            bmpPaint.alpha = 0
            fadeAnim = ValueAnimator.ofInt(0, 255).apply {
                duration = Tokens.DUR_STANDARD
                addUpdateListener { bmpPaint.alpha = it.animatedValue as Int; invalidate() }
                start()
            }
        } else bmpPaint.alpha = 255
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * ratio).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val b = bmp
        if (b == null || bmpPaint.alpha < 255) {
            val s = placeholder.intrinsicWidth
            placeholder.setBounds((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
            placeholder.draw(canvas)
        }
        if (b != null && !b.isRecycled && b.width > 0) {
            val scale = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
            m.setScale(scale, scale)
            m.postTranslate((width - b.width * scale) / 2f, 0f) // по верху
            canvas.drawBitmap(b, m, bmpPaint)
        }
    }
}

/** Полоска прогресса чтения (4dp, скруглённая). */
class ProgressLine(ctx: Context) : View(ctx) {
    private val p = Ui.colors
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.outline }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.primary }
    private val r = RectF()
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(context, 4))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, track)
        if (progress > 0f) {
            r.right = maxOf(h, width * progress)
            canvas.drawRoundRect(r, h / 2, h / 2, fill)
        }
    }
}

/** Иллюстрация пустого состояния: два листа, бейдж «PDF», искорки. Рисуется в сетке 200×160dp. */
class EmptyIllustration(ctx: Context) : View(ctx) {
    private val p = Ui.colors
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Tokens.FONT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.dp(context, 200), Ui.dp(context, 160))
    }

    override fun onDraw(canvas: Canvas) {
        val u = width / 200f
        canvas.save()
        canvas.scale(u, u)
        // мягкое «сияние»
        paint.style = Paint.Style.FILL
        paint.color = Tokens.withAlpha(p.primary, if (p.isDark) 0.12f else 0.10f)
        canvas.drawCircle(100f, 84f, 70f, paint)

        // задний лист
        doc(canvas, 78f, 86f, -10f, Tokens.blend(p.text, p.surfaceHigh, 0.06f), p.outline, false)
        // передний лист
        doc(canvas, 112f, 80f, 7f, p.surfaceHigh, p.primary, true)

        // искорки
        paint.color = p.primary
        sparkle(canvas, 160f, 36f, 7f)
        sparkle(canvas, 40f, 46f, 4.5f)
        paint.color = Tokens.withAlpha(p.primary, 0.6f)
        sparkle(canvas, 170f, 120f, 4f)
        canvas.restore()
    }

    private fun doc(c: Canvas, cx: Float, cy: Float, rot: Float, fillColor: Int, stroke: Int, front: Boolean) {
        val w = 64f; val h = 84f; val fold = 16f
        c.save()
        c.rotate(rot, cx, cy)
        val l = cx - w / 2; val t = cy - h / 2
        path.reset()
        path.moveTo(l + 6, t)
        path.lineTo(l + w - fold, t)
        path.lineTo(l + w, t + fold)
        path.lineTo(l + w, t + h - 6)
        path.quadTo(l + w, t + h, l + w - 6, t + h)
        path.lineTo(l + 6, t + h)
        path.quadTo(l, t + h, l, t + h - 6)
        path.lineTo(l, t + 6)
        path.quadTo(l, t, l + 6, t)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = stroke
        c.drawPath(path, paint)
        // загнутый угол
        path.reset()
        path.moveTo(l + w - fold, t); path.lineTo(l + w - fold, t + fold - 3)
        path.quadTo(l + w - fold, t + fold, l + w - fold + 3, t + fold); path.lineTo(l + w, t + fold)
        c.drawPath(path, paint)
        // строки текста
        paint.color = Tokens.withAlpha(p.textSecondary, if (front) 0.55f else 0.35f)
        paint.strokeWidth = 3f
        val lines = if (front) floatArrayOf(40f, 44f, 30f) else floatArrayOf(44f, 36f, 40f)
        lines.forEachIndexed { i, lw ->
            val y = t + 34f + i * 11f
            c.drawLine(l + 10, y, l + 10 + lw, y, paint)
        }
        if (front) {
            // бейдж PDF
            paint.style = Paint.Style.FILL
            paint.color = p.primary
            r.set(l - 10, t + 12, l + 26, t + 28)
            c.drawRoundRect(r, 8f, 8f, paint)
            textPaint.color = p.onPrimary
            textPaint.textSize = 10f
            c.drawText("PDF", r.centerX(), r.centerY() + 3.6f, textPaint)
        }
        c.restore()
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, s: Float) {
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(x, y - s)
        path.quadTo(x, y, x + s, y)
        path.quadTo(x, y, x, y + s)
        path.quadTo(x, y, x - s, y)
        path.quadTo(x, y, x, y - s)
        path.close()
        c.drawPath(path, paint)
    }
}
