package com.rassul.pdfreader.core.geometry

import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.core.annot.PtPoint
import com.rassul.pdfreader.core.annot.PtRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationGeometryTest {
    @Test fun distToSegment() {
        assertEquals(5f, AnnotationGeometry.distToSegment(5f, 5f, 0f, 0f, 10f, 0f), 1e-4f)
        assertEquals(5f, AnnotationGeometry.distToSegment(-3f, 4f, 0f, 0f, 10f, 0f), 1e-4f) // за концом
        assertEquals(5f, AnnotationGeometry.distToSegment(3f, 4f, 0f, 0f, 0f, 0f), 1e-4f)   // вырожденный
    }

    @Test fun inkHit() {
        val ink = InkAnnotation(0, listOf(listOf(PtPoint(0f, 0f), PtPoint(100f, 0f))), 0, 4f)
        assertTrue(AnnotationGeometry.hits(ink, 50f, 3f, 2f))
        assertFalse(AnnotationGeometry.hits(ink, 50f, 10f, 2f))
        val dot = InkAnnotation(0, listOf(listOf(PtPoint(10f, 10f))), 0, 2f)
        assertTrue(AnnotationGeometry.hits(dot, 11f, 10f, 1f))
    }

    @Test fun highlightAndNoteHit() {
        val h = HighlightAnnotation(0, listOf(PtRect(10f, 10f, 50f, 20f)), 0)
        assertTrue(AnnotationGeometry.hits(h, 30f, 15f, 0f))
        assertFalse(AnnotationGeometry.hits(h, 30f, 25f, 2f))
        val n = NoteAnnotation(0, PtPoint(100f, 100f), "x", 0)
        assertTrue(AnnotationGeometry.hits(n, 110f, 110f, 0f))
        assertFalse(AnnotationGeometry.hits(n, 130f, 110f, 0f))
        assertTrue(AnnotationGeometry.hits(n, 130f, 110f, 0f, noteSizePt = 40f))
    }

    @Test fun smoothing() {
        assertNull(AnnotationGeometry.smooth(emptyList()))
        val pts = listOf(PtPoint(0f, 0f), PtPoint(10f, 0f), PtPoint(10f, 10f), PtPoint(20f, 10f))
        val (start, segs) = AnnotationGeometry.smooth(pts)!!
        assertEquals(PtPoint(0f, 0f), start)
        assertEquals(pts.size, segs.size)
        // кривая проходит через середины отрезков и заканчивается в последней точке
        assertEquals(5f, segs[0].x, 0f); assertEquals(10f, segs[1].x, 0f); assertEquals(5f, segs[1].y, 0f)
        assertEquals(20f, segs.last().x, 0f); assertEquals(10f, segs.last().y, 0f)
        // точка → крошечный штрих, чтобы было что нарисовать
        assertEquals(1, AnnotationGeometry.smooth(listOf(PtPoint(1f, 1f)))!!.second.size)
    }
}
