package com.rassul.pdfreader.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousLayoutTest {
    private val eps = 1e-3f
    // A4 портрет 595×842 pt, A4 альбом 842×595, Letter 612×792
    private val w = floatArrayOf(595f, 842f, 612f)
    private val h = floatArrayOf(842f, 595f, 792f)

    @Test fun fitWidthAndGaps() {
        val l = ContinuousLayout(w, h, 1000f, 10f)
        assertEquals(1000f / 595f, l.pageScale(0), eps)
        assertEquals(0f, l.pageTop(0), eps) // T_0 = m = 0
        assertEquals(842f * 1000f / 595f, l.pageHeight(0), 0.01f)
        assertEquals(595f * 1000f / 842f, l.pageHeight(1), 0.01f) // альбомная — короче
        assertEquals(l.pageHeight(0) + 10f, l.pageTop(1), 0.01f)
        val total = l.pageHeight(0) + 10f + l.pageHeight(1) + 10f + l.pageHeight(2)
        assertEquals(total, l.contentHeight, 0.01f)
        assertEquals(1000f, l.pageBox(2).width, eps)
    }

    // --- контрольные примеры physics-math-reviewer ---
    @Test fun referenceScaleWithMargins() {
        // W_view 1080, m 16, w 612 → s = 1048/612 = 1.712418; при z = 2 → 3.424837
        val l = ContinuousLayout(floatArrayOf(612f, 612f), floatArrayOf(792f, 792f), 1080f, 12f, 16f)
        assertEquals(1.712418f, l.pageScale(0), 1e-5f)
        assertEquals(3.424837f, l.pageScale(0) * 2f, 1e-5f)
        assertEquals(16f, l.pageTop(0), eps)                               // T_0 = m
        assertEquals(16f + 792f * 1.712418f + 12f, l.pageTop(1), 0.01f)   // T_1 = T_0 + h·s + g
        assertEquals(l.pageTop(1) + l.pageHeight(1) + 16f, l.contentHeight, 0.01f)
        assertEquals(16f, l.pageBox(0).left, eps)
    }

    @Test fun pageUnderGapIsNoPage() {
        val l = ContinuousLayout(floatArrayOf(612f, 612f), floatArrayOf(792f, 792f), 1080f, 12f, 16f)
        assertEquals(0, l.pageUnder(500f, 100f))
        assertEquals(-1, l.pageUnder(500f, l.pageTop(1) - 5f)) // зазор
        assertEquals(-1, l.pageUnder(8f, 100f))                 // боковое поле
        assertEquals(1, l.pageUnder(500f, l.pageTop(1) + 1f))
    }

    @Test fun zoomAnchoredKeepsPagePointUnderFocus() {
        val l = ContinuousLayout(floatArrayOf(612f, 842f), floatArrayOf(792f, 595f), 1080f, 12f, 16f)
        var vp = Viewport(1080f, 2340f, l.contentWidth, l.contentHeight, 1f, 0f, 900f)
        val fx = 300f; val fy = 1000f
        val cx = vp.toContentX(fx); val cy = vp.toContentY(fy)
        val p = l.pageUnder(cx, cy)
        val u = (cx - 16f) / l.pageScale(p); val v = (cy - l.pageTop(p)) / l.pageScale(p)
        for (z in floatArrayOf(1.7f, 3f, 6f, 1f)) {
            vp = l.zoomAnchored(vp, z, fx, fy)
            val (x2, y2) = l.pointToContent(p, u, v)
            assertEquals(fx, vp.toScreenX(x2), 1e-2f); assertEquals(fy, vp.toScreenY(y2), 1e-2f)
        }
    }

    @Test fun singlePage() {
        val l = ContinuousLayout(floatArrayOf(595f), floatArrayOf(842f), 1080f, 8f)
        assertEquals(0, l.pageAt(-100f)); assertEquals(0, l.pageAt(1e6f))
        assertEquals(0..0, l.visibleRange(0f, 2000f))
        assertEquals(0, l.mostVisiblePage(0f, 2000f))
    }

    @Test fun emptyDocument() {
        val l = ContinuousLayout(FloatArray(0), FloatArray(0), 1000f, 8f)
        assertEquals(-1, l.pageAt(0f)); assertTrue(l.visibleRange(0f, 100f).isEmpty())
        assertEquals(0f, l.contentHeight, eps)
    }

    @Test fun pageAtUsesGapMidpoint() {
        val l = ContinuousLayout(w, h, 1000f, 10f)
        val b0 = l.pageTop(0) + l.pageHeight(0)
        assertEquals(0, l.pageAt(b0 + 4f))
        assertEquals(1, l.pageAt(b0 + 6f))
    }

    @Test fun visibleRangeSkipsPagesOnlyInGap() {
        val l = ContinuousLayout(w, h, 1000f, 10f)
        val b0 = l.pageTop(0) + l.pageHeight(0)
        // полоса начинается в зазоре после страницы 0 → её не включаем
        assertEquals(1..1, l.visibleRange(b0 + 2f, l.pageTop(1) + 50f))
        // полоса кончается в зазоре перед страницей 1 → её не включаем
        assertEquals(0..0, l.visibleRange(100f, b0 + 8f))
        assertEquals(0..2, l.visibleRange(0f, l.contentHeight))
    }

    @Test fun mostVisible() {
        val l = ContinuousLayout(w, h, 1000f, 10f)
        val b0 = l.pageTop(0) + l.pageHeight(0)
        assertEquals(1, l.mostVisiblePage(b0 - 100f, b0 + 600f))
        assertEquals(0, l.mostVisiblePage(b0 - 600f, b0 + 100f))
    }

    @Test fun positionRoundTrip() {
        val l = ContinuousLayout(w, h, 1000f, 10f)
        for (p in 0..2) for (f in floatArrayOf(0f, 0.25f, 0.9f)) {
            val y = l.contentYFor(p, f)
            val (p2, f2) = l.positionAt(y)
            assertEquals(p, p2); assertEquals(f, f2, 1e-4f)
        }
        // за пределами — ограничение
        assertEquals(2, l.positionAt(1e9f).first)
        assertEquals(0f, l.contentYFor(-5, 0f), eps) // страница 0 — самый верх ленты
    }

    @Test fun pointToContent() {
        val l = ContinuousLayout(w, h, 1000f, 10f)
        val (x, y) = l.pointToContent(1, 421f, 0f)
        assertEquals(500f, x, 0.01f)
        assertEquals(l.pageTop(1), y, eps)
    }

    @Test fun zeroSizedPageDoesNotCrash() {
        val l = ContinuousLayout(floatArrayOf(0f, 595f), floatArrayOf(0f, 842f), 1000f, 10f)
        assertTrue(l.pageHeight(0) > 0f && l.contentHeight.isFinite())
    }
}
