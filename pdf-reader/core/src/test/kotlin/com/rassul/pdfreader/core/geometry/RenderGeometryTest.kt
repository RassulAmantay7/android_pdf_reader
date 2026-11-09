package com.rassul.pdfreader.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderGeometryTest {
    @Test fun baseSizeFollowsAspect() {
        val s = RenderGeometry.baseBitmapSize(595f, 842f, 1080f, 10_000_000)
        assertEquals(1080, s.width); assertEquals(1529, s.height) // 1528.3 → вверх
    }

    @Test fun baseSizeCappedByPixels() {
        // очень длинная страница: 1000×100000 px → ограничение 4 Мп
        val s = RenderGeometry.baseBitmapSize(100f, 10000f, 1000f, 4_000_000)
        assertTrue(s.pixels <= 4_000_000 + 1000)
        assertEquals(100f, s.height.toFloat() / s.width, 1f)
    }

    @Test fun sharpRegionClipsToView() {
        // страница на экране при zoom: от −500 до 3000 по X, от 100 до 5000 по Y; окно 1000×2000
        val page = Box(-500f, 100.4f, 3000f, 5000f)
        val r = RenderGeometry.sharpRegion(page, 595f, 1000f, 2000f, 10_000_000)!!
        assertEquals(0, r.regionLeft); assertEquals(100, r.regionTop)
        assertEquals(1000, r.regionRight); assertEquals(2000, r.regionBottom)
        assertEquals(3500f / 595f, r.pxPerPt, 1e-4f)
        assertEquals(-500f, r.translateX, 1e-3f); assertEquals(0.4f, r.translateY, 1e-3f)
        // точка (0,0) страницы → в битмапе (translateX, translateY), что на экране = page.left/top
        assertEquals(page.left, r.regionLeft + r.translateX, 1e-3f)
    }

    @Test fun sharpRegionNullWhenInvisibleOrTooBig() {
        assertNull(RenderGeometry.sharpRegion(Box(0f, 2100f, 1000f, 3000f), 595f, 1000f, 2000f, 10_000_000))
        assertNull(RenderGeometry.sharpRegion(Box(0f, 0f, 1000f, 2000f), 595f, 1000f, 2000f, 1000))
    }

    @Test fun referenceScreenToPage() {
        // L=16, T_i=5000, s=1.712418, sx=0, sy=4900, касание (187.242, 185.621) → (100, 50) pt
        val (x, y) = RenderGeometry.screenToPage(187.242f, 185.621f, 16f, 5000f, 1.712418f, 0f, 4900f)
        assertEquals(100f, x, 1e-3f); assertEquals(50f, y, 1e-3f)
    }

    @Test fun referenceStrokeWidth() {
        assertEquals(3.5038f, RenderGeometry.pxToPt(6f, 1.712418f), 1e-4f)
    }

    @Test fun needsSharp() {
        assertTrue(RenderGeometry.needsSharp(1500f, 1000))
        assertTrue(!RenderGeometry.needsSharp(1200f, 1000))
        assertTrue(!RenderGeometry.needsSharp(1200f, 0))
    }
}
