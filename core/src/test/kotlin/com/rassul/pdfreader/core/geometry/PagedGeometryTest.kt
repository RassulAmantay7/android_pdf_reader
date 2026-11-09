package com.rassul.pdfreader.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedGeometryTest {
    private val eps = 1e-3f

    @Test fun fitPortraitInTallView() {
        val (w, h, s) = PagedGeometry.fit(595f, 842f, 1080f, 2400f)
        assertEquals(1080f, w, eps); assertEquals(842f * 1080f / 595f, h, 0.01f); assertEquals(1080f / 595f, s, eps)
    }

    @Test fun fitLandscapeAndHeightLimited() {
        val (w, h, _) = PagedGeometry.fit(842f, 595f, 1080f, 2400f)
        assertEquals(1080f, w, eps); assertTrue(h < 1080f)
        // окно шире страницы по пропорциям — ограничивает высота
        val (w2, h2, _) = PagedGeometry.fit(595f, 842f, 2400f, 1080f)
        assertEquals(1080f, h2, eps); assertEquals(595f * 1080f / 842f, w2, 0.01f)
    }

    @Test fun referenceBook() {
        // книга 1080×2340, m 16, стр. 595×841 → s = 1.761345, ox = 16, oy ≈ 429.3
        val (w, h, s) = PagedGeometry.fit(595f, 841f, 1080f, 2340f, 16f)
        assertEquals(1.761345f, s, 1e-5f)
        val b = PagedGeometry.slotBox(w, h, 1080f, 2340f, 0f)
        assertEquals(16f, b.left, 1e-3f); assertEquals(429.3f, b.top, 0.1f)
        // стр. 842×595 → s = 1.244656, oy ≈ 799.7
        val (w2, h2, s2) = PagedGeometry.fit(842f, 595f, 1080f, 2340f, 16f)
        assertEquals(1.244656f, s2, 1e-5f)
        assertEquals(799.7f, PagedGeometry.slotBox(w2, h2, 1080f, 2340f, 0f).top, 0.1f)
    }

    @Test fun slotBoxCentered() {
        val b = PagedGeometry.slotBox(500f, 800f, 1000f, 2000f, 1010f)
        assertEquals(1010f + 250f, b.left, eps); assertEquals(600f, b.top, eps)
    }

    @Test fun rubberBandMonotonicAndBounded() {
        val a = PagedGeometry.rubberBand(100f, 1000f)
        val b = PagedGeometry.rubberBand(400f, 1000f)
        assertTrue(a in 0f..100f); assertTrue(b > a); assertTrue(b < 400f)
        assertEquals(-a, PagedGeometry.rubberBand(-100f, 1000f), eps)
        assertEquals(0f, PagedGeometry.rubberBand(0f, 1000f), eps)
    }

    @Test fun decideFlip() {
        // по расстоянию
        assertEquals(1, PagedGeometry.decideFlip(-300f, 0f, 1000f, true, true))
        assertEquals(-1, PagedGeometry.decideFlip(300f, 0f, 1000f, true, true))
        assertEquals(0, PagedGeometry.decideFlip(-100f, 0f, 1000f, true, true))
        // быстрый жест при малом смещении
        assertEquals(1, PagedGeometry.decideFlip(-50f, -2000f, 1000f, true, true))
        // жест против смещения — возврат
        assertEquals(0, PagedGeometry.decideFlip(-400f, 2000f, 1000f, true, true))
        // края документа
        assertEquals(0, PagedGeometry.decideFlip(-600f, 0f, 1000f, true, false))
        assertEquals(0, PagedGeometry.decideFlip(600f, 0f, 1000f, false, true))
    }
}
