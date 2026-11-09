package com.rassul.pdfreader.core.geometry

/**
 * Матрица ночного режима для android.graphics.ColorMatrix (4×5, построчно, 20 чисел):
 *   R' = a·R + b·G + c·B + d·A + e   (R,G,B,A и e — в шкале 0..255),
 * см. developer.android.com/reference/android/graphics/ColorMatrix.
 *
 * Идея — «инверсия яркости с сохранением оттенка» (как «smart invert»): invert() и затем
 * hue-rotate(180°). Матрица hueRotate — из W3C Filter Effects Module Level 1, §feColorMatrix
 * (type="hueRotate"), с весами яркости 0.213 / 0.715 / 0.072 (≈ Rec.709). При угле 180°
 * (cos = −1, sin = 0) строки hueRotate равны δ − 2·w, а invert даёт 255 − C; их произведение
 * в замкнутом виде:
 *   C' = C + 255 − 2·Y,   Y = 0.213·R + 0.715·G + 0.072·B
 * Т.е. инвертируется только яркость Y, цветность сохраняется.
 * Эталон (проверен physics-math-reviewer):
 *   [0.574,−1.430,−0.144,0,255,  −0.426,−0.430,−0.144,0,255,  −0.426,−1.430,0.856,0,255,  0,0,0,1,0]
 * Белый → чёрный, чёрный → белый, красный → (255,146,146), синий → (218,218,255).
 *
 * blackLevel (0..1) — во что превращается белый фон: 0 — чистый чёрный, 0.07 — мягкий
 * тёмно-серый (меньше «ореолов» вокруг светлого текста на OLED). Применяем линейно:
 *   C'' = blackLevel + (1 − blackLevel)·C'
 * Смещение e при этом равно 255·(blackLevel + (1 − blackLevel)) = 255 при любом blackLevel.
 */
object NightColorMatrix {
    /** Веса яркости hueRotate из W3C Filter Effects (≈ Rec.709). */
    const val LUMA_R = 0.213f
    const val LUMA_G = 0.715f
    const val LUMA_B = 0.072f

    fun create(blackLevel: Float = 0f): FloatArray {
        val bl = blackLevel.coerceIn(0f, 1f)
        val k = 1f - bl
        val w = floatArrayOf(LUMA_R, LUMA_G, LUMA_B)
        val m = FloatArray(20)
        for (row in 0..2) {
            for (col in 0..2) {
                val delta = if (row == col) 1f else 0f
                m[row * 5 + col] = k * (delta - 2f * w[col])
            }
            m[row * 5 + 3] = 0f
            m[row * 5 + 4] = 255f
        }
        m[18] = 1f // альфа без изменений
        return m
    }

    /** Применить матрицу к цвету (компоненты 0..255) с насыщением — как это делает Skia. Для тестов. */
    fun apply(m: FloatArray, r: Int, g: Int, b: Int, a: Int = 255): IntArray {
        val src = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())
        return IntArray(4) { row ->
            var v = m[row * 5 + 4]
            for (col in 0..3) v += m[row * 5 + col] * src[col]
            Math.round(v).coerceIn(0, 255)
        }
    }
}
