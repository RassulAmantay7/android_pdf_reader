package com.rassul.pdfreader.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Аудит (code-formula-auditor): контрольные примеры docs/math-review.md, ещё не покрытые
 * тестами, и свойства «вручную» (цикл по случайным входам, фиксированный seed).
 */
class GeometryAuditTest {
    private val rnd = Random(20261109)

    private fun randomLayout(r: Random): ContinuousLayout {
        val n = r.nextInt(1, 40)
        val w = FloatArray(n) { r.nextFloat() * 1200f + 100f }
        val h = FloatArray(n) { r.nextFloat() * 1200f + 100f }
        return ContinuousLayout(w, h, r.nextFloat() * 1500f + 300f, r.nextFloat() * 30f, r.nextFloat() * 40f)
    }

    /**
     * «Щипок m16, sc0, f200, z1→2 → sc′ = 184» — эталон считает в модели с НЕмасштабируемыми
     * полями (x_экр = m + u·s·z − sc′). Код масштабирует поля вместе с контентом
     * (x_экр = (m + u·s)·z − sc), поэтому sc = sc′ + m(z − 1) = 200. Положение страницы
     * на экране при этом совпадает: левый край = 16 − 184 = 32 − 200 = −168.
     */
    @Test fun referencePinchSameScreenPlacement() {
        val l = ContinuousLayout(floatArrayOf(612f), floatArrayOf(792f), 1080f, 12f, 16f)
        val vp0 = Viewport(1080f, 2340f, l.contentWidth, l.contentHeight, 1f, 0f, 0f)
        val vp = l.zoomAnchored(vp0, 2f, 200f, 200f)
        assertEquals(200f, vp.scrollX, 1e-3f)                    // = 184 + 16·(2 − 1)
        assertEquals(16f - 184f, vp.toScreenX(l.pageBox(0).left), 1e-3f)
        assertEquals(200f, vp.toScreenX(l.pointToContent(0, (200f - 16f) / l.pageScale(0), 0f).first), 1e-3f)
    }

    /** Экран → страница → экран = тождество (раскладка + Viewport + RenderGeometry.screenToPage). */
    @Test fun propertyScreenPageScreen() {
        repeat(500) {
            val l = randomLayout(rnd)
            val z = 1f + rnd.nextFloat() * 5f
            val vp = Viewport(l.viewWidth, 2000f, l.contentWidth, l.contentHeight, z,
                rnd.nextFloat() * l.contentWidth * z, rnd.nextFloat() * l.contentHeight * z).clamped()
            val sx = rnd.nextFloat() * vp.viewW; val sy = rnd.nextFloat() * vp.viewH
            val p = l.pageUnder(vp.toContentX(sx), vp.toContentY(sy))
            if (p < 0) return@repeat
            val b = vp.toScreen(l.pageBox(p))
            val s = l.pageScale(p) * z
            assertEquals(s, b.width / (l.pageBox(p).width / l.pageScale(p)), s * 1e-4f)
            val (u, v) = RenderGeometry.screenToPage(sx, sy, b.left, b.top, s, 0f, 0f)
            val (cx, cy) = l.pointToContent(p, u, v)
            assertEquals(sx, vp.toScreenX(cx), 0.05f)
            assertEquals(sy, vp.toScreenY(cy), 0.05f)
        }
    }

    /** Щипок по якорю сохраняет точку под фокусом при любой раскладке, фокусе и масштабе. */
    @Test fun propertyPinchKeepsPointUnderFocus() {
        repeat(500) {
            val l = randomLayout(rnd)
            var vp = Viewport(l.viewWidth, 2000f, l.contentWidth, l.contentHeight, 1f + rnd.nextFloat() * 3f, 0f, 0f)
            vp = vp.withScroll(rnd.nextFloat() * maxOf(1f, vp.maxScrollX), rnd.nextFloat() * maxOf(1f, vp.maxScrollY))
            val fx = rnd.nextFloat() * vp.viewW; val fy = rnd.nextFloat() * vp.viewH
            val cx = vp.toContentX(fx); val cy = vp.toContentY(fy)
            val z2 = 1f + rnd.nextFloat() * 5f
            val v2 = l.zoomAnchored(vp, z2, fx, fy)
            val p = l.pageUnder(cx, cy)
            val (ax, ay) = if (p >= 0) l.pointToContent(p, (cx - l.pageBox(p).left) / l.pageScale(p), (cy - l.pageTop(p)) / l.pageScale(p)) else cx to cy
            val tol = 1e-6f * maxOf(1f, ay * z2) + 0.02f
            assertEquals(fx, v2.toScreenX(ax), tol)
            assertEquals(fy, v2.toScreenY(ay), tol)
            // общий Viewport.zoomAround даёт то же самое (поля масштабируются вместе с контентом)
            val v3 = vp.zoomAround(z2, fx, fy)
            assertEquals(v2.scrollY, v3.scrollY, tol)
        }
    }

    /** Ограничение прокрутки: результат в [min, max]; меньший контент — по центру; идемпотентно. */
    @Test fun propertyClamp() {
        repeat(1000) {
            val c = rnd.nextFloat() * 5000f; val v = rnd.nextFloat() * 3000f + 1f
            val s = (rnd.nextFloat() - 0.5f) * 20000f
            val r = Viewport.clampAxis(s, c, v)
            if (c <= v) assertEquals((c - v) / 2f, r, 1e-3f) else assertTrue(r in 0f..(c - v))
            assertEquals(r, Viewport.clampAxis(r, c, v), 0f)
        }
    }

    /** Ночная матрица дважды ≈ исходный цвет для серых (Y_серого = c ⇒ c′ = 255 − c). */
    @Test fun propertyNightTwiceIsIdentityForGrays() {
        val m = NightColorMatrix.create()
        for (g in 0..255) {
            val once = NightColorMatrix.apply(m, g, g, g)
            assertEquals(255 - g, once[0], 1); assertEquals(once[0], once[1], 1); assertEquals(once[1], once[2], 1)
            val twice = NightColorMatrix.apply(m, once[0], once[1], once[2])
            for (k in 0..2) assertEquals("серый $g", g, twice[k], 1)
            assertEquals(255, twice[3])
        }
        // формула c′ = c + 255 − 2Y без насыщения — для произвольных цветов в гамме
        repeat(500) {
            val r = rnd.nextInt(256); val gg = rnd.nextInt(256); val b = rnd.nextInt(256)
            val y = 0.213f * r + 0.715f * gg + 0.072f * b
            val out = NightColorMatrix.apply(m, r, gg, b)
            val exp = intArrayOf(r, gg, b).map { Math.round(it + 255 - 2 * y).coerceIn(0, 255) }
            for (k in 0..2) assertEquals(exp[k], out[k], 1)
        }
    }

    private fun assertEquals(exp: Int, act: Int, tol: Int) = assertEquals("", exp, act, tol)
    private fun assertEquals(msg: String, exp: Int, act: Int, tol: Int) =
        assertTrue("$msg: ожидалось $exp ± $tol, получено $act", abs(exp - act) <= tol)

    /** Матрица Page.render: пиксель битмапа (pt·s + t) + левый верх участка = экранная точка. */
    @Test fun propertySharpRegionMatrix() {
        repeat(500) {
            val wPt = rnd.nextFloat() * 1000f + 100f
            val s = rnd.nextFloat() * 10f + 0.5f
            val left = (rnd.nextFloat() - 0.5f) * 4000f; val top = (rnd.nextFloat() - 0.5f) * 4000f
            val page = Box(left, top, left + wPt * s, top + wPt * 1.3f * s)
            val r = RenderGeometry.sharpRegion(page, wPt, 1080f, 2340f, Long.MAX_VALUE) ?: return@repeat
            assertEquals(s, r.pxPerPt, s * 1e-4f)
            assertTrue(r.width in 1..1081 && r.height in 1..2341)
            val u = rnd.nextFloat() * wPt; val v = rnd.nextFloat() * wPt * 1.3f
            val bx = u * r.pxPerPt + r.translateX; val by = v * r.pxPerPt + r.translateY
            assertEquals(left + u * s, r.regionLeft + bx, 0.05f + abs(left) * 1e-6f)
            assertEquals(top + v * s, r.regionTop + by, 0.05f + abs(top) * 1e-6f)
        }
    }

    /** Толщина пера: w_pt = w_px / s, обратное w_px = w_pt·s; деление на 0 не даёт Inf. */
    @Test fun strokeWidthConversion() {
        repeat(200) {
            val s = rnd.nextFloat() * 10f + 0.1f; val px = rnd.nextFloat() * 40f
            assertEquals(px, RenderGeometry.pxToPt(px, s) * s, 1e-3f)
        }
        assertTrue(RenderGeometry.pxToPt(5f, 0f).isFinite())
    }

    /**
     * Длинный документ: 2000 страниц × ~1500 px ⇒ T_i ≈ 3·10^6 px. ulp(Float) там 0.25 px.
     * T_i накапливается в Double, поэтому отличие от точной суммы — не больше одного округления.
     */
    @Test fun longDocumentTopsPrecision() {
        val n = 2000
        val w = FloatArray(n) { 595f + (it % 7) }; val h = FloatArray(n) { 842f + (it % 5) * 0.3f }
        val l = ContinuousLayout(w, h, 1080f, 12f, 16f)
        var exact = 16.0
        var maxErr = 0.0
        for (i in 0 until n) {
            maxErr = maxOf(maxErr, abs(l.pageTop(i) - exact))
            exact += l.pageHeight(i).toDouble() + 12.0
        }
        assertTrue("T_last ≈ ${l.pageTop(n - 1)}", l.pageTop(n - 1) > 2.5e6f)
        assertTrue("ошибка T_i $maxErr px", maxErr <= 0.25)
        assertEquals((exact - 12.0 + 16.0).toFloat(), l.contentHeight, 0.5f)
        // позиция чтения в конце ленты восстанавливается
        val y = l.contentYFor(n - 1, 0.5f)
        val (p, f) = l.positionAt(y)
        assertEquals(n - 1, p); assertEquals(0.5f, f, 1e-3f)
    }

    /** Нулевые/NaN-размеры страниц и нулевая ширина окна не дают NaN/Inf. */
    @Test fun degenerateSizes() {
        val l = ContinuousLayout(floatArrayOf(0f, Float.NaN, -5f), floatArrayOf(0f, 100f, Float.NaN), 0f, 8f, 16f)
        for (i in 0 until 3) {
            assertTrue(l.pageScale(i).isFinite() && l.pageTop(i).isFinite() && l.pageHeight(i).isFinite())
        }
        assertTrue(l.contentHeight.isFinite())
        val (fw, fh, s) = PagedGeometry.fit(0f, Float.NaN, 0f, 0f, 16f)
        assertTrue(fw.isFinite() && fh.isFinite() && s.isFinite())
        val b = RenderGeometry.baseBitmapSize(0f, 0f, 0f, 0)
        assertTrue(b.width >= 1 && b.height >= 1)
    }
}
