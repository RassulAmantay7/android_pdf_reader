package com.rassul.pdfreader.core.pdf

/**
 * Перевод «координат страницы» (пункты, начало — левый верхний угол ВИДИМОЙ страницы,
 * Y вниз; см. Annotations.kt / docs/contracts.md) в пользовательское пространство PDF
 * (начало внизу слева, Y вверх; ISO 32000-1 §8.3.2.3 «User space»).
 *
 * Видимая область — /CropBox, обрезанный по /MediaBox (§14.11.2: «the crop box … if it
 * extends beyond the media box, it shall be treated as the intersection»); без /CropBox —
 * /MediaBox (§7.7.3.3, таблица 30: CropBox по умолчанию = MediaBox). При отсутствии
 * /MediaBox берём US Letter 612×792 (как pdf.js/PDFium для битых файлов).
 *
 * /Rotate (§7.7.3.3, таблица 30) — «число градусов, на которое страница поворачивается
 * ПО ЧАСОВОЙ стрелке при показе». Пусть видимая область [x0,y0,x1,y1], W=x1−x0, H=y1−y0,
 * (px,py) — точка в координатах страницы. Без поворота точка пространства PDF видна в
 * (qx,qy) = (ux−x0, y1−uy) на картинке W×H; поворот картинки по часовой на r даёт:
 *   r=0:   (px,py) = (qx, qy)          ⇒ ux = x0 + px,  uy = y1 − py
 *   r=90:  (px,py) = (H−qy, qx)        ⇒ ux = x0 + py,  uy = y0 + px
 *   r=180: (px,py) = (W−qx, H−qy)      ⇒ ux = x1 − px,  uy = y0 + py
 *   r=270: (px,py) = (qy, W−qx)        ⇒ ux = x1 − py,  uy = y1 − px
 * Видимый размер: W×H при 0/180, H×W при 90/270.
 */
class PageGeometry(box: DoubleArray, val rotate: Int) {
    val x0 = box[0]; val y0 = box[1]; val x1 = box[2]; val y1 = box[3]

    companion object {
        private val LETTER = doubleArrayOf(0.0, 0.0, 612.0, 792.0)

        fun of(page: PageInfo): PageGeometry {
            val media = page.mediaBox ?: LETTER
            val crop = page.cropBox
            var v = if (crop == null) media else doubleArrayOf(
                maxOf(crop[0], media[0]), maxOf(crop[1], media[1]), minOf(crop[2], media[2]), minOf(crop[3], media[3]),
            )
            if (v[2] <= v[0] || v[3] <= v[1]) v = media // пересечение пусто — CropBox бессмыслен
            return PageGeometry(v, page.rotate)
        }
    }

    fun toPdf(px: Double, py: Double): DoubleArray = when (rotate) {
        90 -> doubleArrayOf(x0 + py, y0 + px)
        180 -> doubleArrayOf(x1 - px, y0 + py)
        270 -> doubleArrayOf(x1 - py, y1 - px)
        else -> doubleArrayOf(x0 + px, y1 - py)
    }

    /** Прямоугольник страницы → нормализованный /Rect [llx lly urx ury] (§7.9.5). */
    fun rectToPdf(l: Double, t: Double, r: Double, b: Double): DoubleArray {
        val a = toPdf(l, t); val c = toPdf(r, b)
        return doubleArrayOf(minOf(a[0], c[0]), minOf(a[1], c[1]), maxOf(a[0], c[0]), maxOf(a[1], c[1]))
    }

    /**
     * Матрица формы [a b c d 0 0] (§8.3.3, §12.5.5), переводящая локальную систему «как на
     * экране» (u вправо, v вверх по видимой странице) в пространство PDF: столбцы — образы
     * направлений видимой страницы (1,0) и (0,−1) под линейной частью [toPdf].
     *   r=0: [1 0 0 1]; r=90: [0 1 −1 0]; r=180: [−1 0 0 −1]; r=270: [0 −1 1 0].
     * Т.е. содержимое поворачивается на r против часовой, и после показа страницы с
     * поворотом r по часовой выглядит прямо.
     */
    fun formMatrix(): DoubleArray = when (rotate) {
        90 -> doubleArrayOf(0.0, 1.0, -1.0, 0.0, 0.0, 0.0)
        180 -> doubleArrayOf(-1.0, 0.0, 0.0, -1.0, 0.0, 0.0)
        270 -> doubleArrayOf(0.0, -1.0, 1.0, 0.0, 0.0, 0.0)
        else -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}
