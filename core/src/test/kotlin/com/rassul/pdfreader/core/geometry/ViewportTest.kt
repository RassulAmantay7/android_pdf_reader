package com.rassul.pdfreader.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private val eps = 1e-3f
    private fun vp(z: Float = 1f, sx: Float = 0f, sy: Float = 0f) = Viewport(1000f, 2000f, 1000f, 10000f, z, sx, sy)

    @Test fun screenContentRoundTrip() {
        val v = vp(2.5f, 300f, 4000f)
        val sx = v.toScreenX(123f); val sy = v.toScreenY(4567f)
        assertEquals(123f, v.toContentX(sx), eps)
        assertEquals(4567f, v.toContentY(sy), eps)
        assertEquals(123f * 2.5f - 300f, sx, eps)
    }

    @Test fun zoomAroundKeepsFocusPoint() {
        val v = vp(1f, 0f, 3000f)
        val fx = 400f; val fy = 700f
        val cx = v.toContentX(fx); val cy = v.toContentY(fy)
        val z = v.zoomAround(3f, fx, fy)
        assertEquals(fx, z.toScreenX(cx), eps)
        assertEquals(fy, z.toScreenY(cy), eps)
    }

    @Test fun clampAtZoom1HorizontalIsZero() {
        val v = vp(1f, 50f, -100f).clamped()
        assertEquals(0f, v.scrollX, eps)
        assertEquals(0f, v.scrollY, eps)
    }

    @Test fun clampBottomEdge() {
        val v = vp(1f, 0f, 1e9f).clamped()
        assertEquals(10000f - 2000f, v.scrollY, eps)
        val z = vp(2f, 1e9f, 1e9f).clamped()
        assertEquals(1000f, z.scrollX, eps)          // 2000 − 1000
        assertEquals(20000f - 2000f, z.scrollY, eps)
    }

    @Test fun smallContentIsCentered() {
        // Страница 500×800 в окне 1000×2000 → центр: scroll = −(1000−500)/2, −(2000−800)/2
        val v = Viewport(1000f, 2000f, 500f, 800f, 1f, 123f, 456f).clamped()
        assertEquals(-250f, v.scrollX, eps)
        assertEquals(-600f, v.scrollY, eps)
        assertEquals(250f, v.toScreenX(0f), eps)
    }

    @Test fun zoomAtEdgeThenClampStaysInBounds() {
        // Масштаб у правого нижнего угла: после clamp край контента совпадает с краем окна.
        val v = vp(1f, 0f, 8000f).zoomAround(4f, 1000f, 2000f).clamped()
        assertEquals(v.maxScrollX, v.scrollX, eps)
        assertEquals(v.maxScrollY, v.scrollY, eps)
        assertEquals(1000f, v.toScreenX(1000f), eps)
    }

    @Test fun referenceClampAxis() {
        assertEquals(0f, Viewport.clampAxis(-50f, 2160f, 1080f), eps)
        assertEquals(1080f, Viewport.clampAxis(1500f, 2160f, 1080f), eps)
        assertEquals(-140f, Viewport.clampAxis(0f, 800f, 1080f), eps)
        assertEquals(10000f, Viewport.clampAxis(11000f, 12000f, 2000f), eps)
    }

    @Test fun panWithOverflowSplits() {
        val (s, rest) = Viewport.panWithOverflow(90f, 30f, 0f, 100f)
        assertEquals(100f, s, eps); assertEquals(20f, rest, eps)
        val (s2, rest2) = Viewport.panWithOverflow(10f, -5f, 0f, 100f)
        assertEquals(5f, s2, eps); assertEquals(0f, rest2, eps)
    }

    @Test fun zoomHelpers() {
        assertEquals(2.5f, doubleTapTargetZoom(1f), eps)
        assertEquals(1f, doubleTapTargetZoom(2.5f), eps)
        assertEquals(1f, doubleTapTargetZoom(6f), eps)
        assertEquals(1f, clampZoom(0.2f, 1f, 6f), eps)
        assertEquals(6f, clampZoom(60f, 1f, 6f), eps)
        assertEquals(1f, clampZoom(Float.NaN, 1f, 6f), eps)
    }

    @Test fun scrollToShowKeepsVisiblePoint() {
        assertEquals(100f, scrollToShow(100f, 500f, 1000f, 50f), eps)
        assertEquals(5000f - 1000f / 3f, scrollToShow(100f, 5000f, 1000f, 50f), eps)
        assertTrue(scrollToShow(1000f, 10f, 1000f, 50f) < 10f)
    }
}
