package com.rassul.pdfreader.core.pdf

import com.rassul.pdfreader.core.annot.Annotation
import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.core.annot.PtPoint
import com.rassul.pdfreader.core.annot.PtRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfAnnotationWriterTest {

    companion object {
        const val YELLOW = 0xFFFFEB3B.toInt()
        const val RED = 0xFFE53935.toInt()

        /** Набор пометок на все 5 страниц образца (контрольные значения — в [checkAll]). */
        fun sampleAnnotations(): List<Annotation> = listOf(
            HighlightAnnotation(0, listOf(PtRect(100f, 50f, 300f, 62f)), YELLOW, "Выделенный текст"),
            HighlightAnnotation(1, listOf(PtRect(100f, 50f, 300f, 62f)), YELLOW),
            NoteAnnotation(1, PtPoint(100f, 50f), "Заметка на повёрнутой странице", RED),
            InkAnnotation(2, listOf(listOf(PtPoint(100f, 50f), PtPoint(200f, 50f))), RED, 4f, 0.5f),
            InkAnnotation(3, listOf(listOf(PtPoint(100f, 50f))), RED, 2f),
            NoteAnnotation(4, PtPoint(10f, 10f), "Ещё заметка", YELLOW),
        )

        fun annots(doc: PdfDocument, page: Int): List<PdfDict> =
            doc.resolve(doc.pages()[page].dict["Annots"]).asArray()!!.items.map { doc.resolve(it).asDict()!! }

        fun nums(o: PdfObj?): DoubleArray = (o as PdfArray).items.map { it.asNum()!! }.toDoubleArray()
    }

    private fun build(mode: PdfGen.Mode) = PdfGen.sample().build(mode, if (mode == PdfGen.Mode.CLASSIC) emptySet() else PdfGen.COMPRESSIBLE)

    private fun checkAll(out: ByteArray, copies: Int = 1) {
        val doc = PdfDocument(out)
        assertFalse(doc.repaired)

        fun ofType(page: Int, t: String) = annots(doc, page).filter { it["Subtype"].asName() == t }

        // стр. 1 (R0): прямой /Annots [42 0 R] дополнен
        val p0 = annots(doc, 0)
        assertEquals("Square", p0[0]["Subtype"].asName())
        val h0 = ofType(0, "Highlight")
        assertEquals(copies, h0.size)
        assertArrayEquals(doubleArrayOf(110.0, 770.0, 310.0, 770.0, 110.0, 758.0, 310.0, 758.0), nums(h0[0]["QuadPoints"]), 1e-9)
        assertArrayEquals(doubleArrayOf(110.0, 758.0, 310.0, 770.0), nums(h0[0]["Rect"]), 1e-9)
        assertEquals("Выделенный текст", PdfText.decode((h0[0]["Contents"] as PdfString).bytes))

        // стр. 2 (R90)
        val h1 = ofType(1, "Highlight").first()
        assertArrayEquals(doubleArrayOf(60.0, 320.0, 72.0, 320.0, 60.0, 120.0, 72.0, 120.0), nums(h1["QuadPoints"]), 1e-9)
        assertArrayEquals(doubleArrayOf(60.0, 120.0, 72.0, 320.0), nums(h1["Rect"]), 1e-9)
        val n1 = ofType(1, "Text")
        assertEquals(copies, n1.size)
        assertArrayEquals(doubleArrayOf(60.0, 120.0, 80.0, 140.0), nums(n1[0]["Rect"]), 1e-9)
        assertEquals("Note", n1[0]["Name"].asName())
        assertEquals(PdfBool(false), n1[0]["Open"])
        val nc = (n1[0]["Contents"] as PdfString).bytes
        assertEquals(0xFE.toByte(), nc[0]); assertEquals(0xFF.toByte(), nc[1])
        assertEquals("Заметка на повёрнутой странице", PdfText.decode(nc))
        val nap = doc.resolve(n1[0]["AP"].asDict()!!["N"]) as PdfStream
        assertArrayEquals(doubleArrayOf(0.0, 1.0, -1.0, 0.0, 0.0, 0.0), nums(nap.dict["Matrix"]), 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 20.0, 20.0), nums(nap.dict["BBox"]), 0.0)

        // стр. 3 (R180): ink
        val i2 = ofType(2, "Ink")
        assertEquals(copies, i2.size)
        assertArrayEquals(doubleArrayOf(408.0, 68.0, 512.0, 72.0), nums(i2[0]["Rect"]), 1e-9)
        assertArrayEquals(doubleArrayOf(510.0, 70.0, 410.0, 70.0), nums(i2[0]["InkList"].asArray()!![0]), 1e-9)
        assertEquals(4.0, i2[0]["BS"].asDict()!!["W"].asNum()!!, 0.0)
        assertEquals(0.5, i2[0]["CA"].asNum()!!, 1e-9)

        // стр. 4 (Rotate −90 → 270, унаследован)
        val i3 = ofType(3, "Ink")
        assertArrayEquals(doubleArrayOf(559.0, 719.0, 561.0, 721.0), nums(i3[0]["Rect"]), 1e-9)
        assertArrayEquals(doubleArrayOf(560.0, 720.0), nums(i3[0]["InkList"].asArray()!![0]), 1e-9)

        // стр. 5: MediaBox [0 0 300 400], CropBox шире — обрезан; /Annots косвенный (объект 40)
        val p4 = annots(doc, 4)
        assertEquals("Square", p4[0]["Subtype"].asName())
        assertEquals(PdfRef(40, 0), doc.pages()[4].dict["Annots"])
        val n4 = ofType(4, "Text")
        assertArrayEquals(doubleArrayOf(10.0, 370.0, 30.0, 390.0), nums(n4[0]["Rect"]), 1e-9)

        // общие ключи и /AP у всех новых
        for (pi in 0 until 5) for (a in annots(doc, pi)) {
            if (a["Subtype"].asName() == "Square") continue
            assertEquals("Annot", a["Type"].asName())
            assertEquals(4, a["F"].asInt())
            assertEquals(doc.pages()[pi].ref, a["P"])
            assertEquals(3, a["C"].asArray()!!.size)
            assertNotNull(a["NM"]); assertNotNull(a["M"]); assertNotNull(a["CA"])
            assertEquals("Я", PdfText.decode((a["T"] as PdfString).bytes))
            val ap = doc.resolve(a["AP"].asDict()!!["N"]) as PdfStream
            assertEquals("Form", ap.dict["Subtype"].asName())
            assertTrue(ap.len > 0)
            val r = nums(a["Rect"])
            assertTrue(r[0] < r[2] && r[1] < r[3])
            a["QuadPoints"]?.let { q -> val v = nums(q); for (k in v.indices) assertTrue(if (k % 2 == 0) v[k] in r[0]..r[2] else v[k] in r[1]..r[3]) }
            if (a["Subtype"].asName() == "Highlight") {
                val gs = ap.dict["Resources"].asDict()!!["ExtGState"].asDict()!!["GS0"].asDict()!!
                assertEquals("Multiply", gs["BM"].asName())
                assertEquals(0.35, gs["CA"].asNum()!!, 1e-9)
            }
        }
    }

    private fun tail(orig: ByteArray, out: ByteArray) = String(out, orig.size, out.size - orig.size, Charsets.ISO_8859_1)

    private fun assertPrefix(orig: ByteArray, out: ByteArray) {
        assertTrue(out.size > orig.size)
        for (i in orig.indices) if (orig[i] != out[i]) fail("байт $i изменён")
    }

    @Test fun classicKeepsClassicXref() {
        val pdf = build(PdfGen.Mode.CLASSIC)
        val out = PdfAnnotationWriter.append(pdf, sampleAnnotations())
        assertPrefix(pdf, out)
        val t = tail(pdf, out)
        assertTrue(t.contains("\nxref\n") && t.contains("trailer") && t.contains("/Prev ${PdfGen.startxref(pdf)}"))
        assertFalse(t.contains("12 0 obj")) // страница с косвенным /Annots не переписывается
        assertTrue(t.contains("40 0 obj")) // а сам массив — да
        assertTrue(t.contains("4 0 obj")) // страница с прямым /Annots — новая версия
        assertTrue(t.contains("<00112233445566778899AABBCCDDEEFF>")) // первая часть /ID сохранена
        checkAll(out)
    }

    @Test fun xrefStreamKeepsXrefStream() {
        val pdf = build(PdfGen.Mode.XREF_STREAM)
        val out = PdfAnnotationWriter.append(pdf, sampleAnnotations())
        assertPrefix(pdf, out)
        val t = tail(pdf, out)
        assertTrue(t.contains("/Type /XRef"))
        assertFalse(t.contains("\nxref\n"))
        val doc = PdfDocument(out)
        assertTrue(doc.lastXrefIsStream)
        checkAll(out)
    }

    @Test fun hybrid() {
        val pdf = PdfGen.sample().build(PdfGen.Mode.HYBRID, PdfGen.COMPRESSIBLE)
        val out = PdfAnnotationWriter.append(pdf, sampleAnnotations())
        assertPrefix(pdf, out)
        checkAll(out)
        assertEquals(PdfOutlineReader.read(pdf), PdfOutlineReader.read(out))
        assertEquals(2, PdfOutlineReader.read(out).size)
    }

    @Test fun secondAppendOnTopOfUpdatedFile() {
        for (mode in PdfGen.Mode.values()) {
            val pdf = build(mode)
            val once = PdfAnnotationWriter.append(pdf, sampleAnnotations())
            val twice = PdfAnnotationWriter.append(once, sampleAnnotations())
            assertPrefix(once, twice)
            assertTrue(tail(once, twice).contains("/Prev ${PdfGen.startxref(once)}"))
            val doc = PdfDocument(twice)
            assertEquals(1 + 2, annots(doc, 0).size)
            assertEquals(1 + 2, annots(doc, 4).size)
            assertEquals(4, annots(doc, 1).size)
            // номера новых объектов не пересекаются
            val nums = (0 until 5).flatMap { doc.resolve(doc.pages()[it].dict["Annots"]).asArray()!!.items.map { r -> (r as PdfRef).num } }
            assertEquals(nums.size, nums.toSet().size)
            assertEquals(PdfOutlineReader.read(pdf), PdfOutlineReader.read(twice))
        }
    }

    @Test fun repairedFileGetsFullXref() {
        val pdf = build(PdfGen.Mode.XREF_STREAM)
        val s = String(pdf, Charsets.ISO_8859_1)
        val broken = s.replace(Regex("startxref\\s+\\d+"), "startxref\n77").toByteArray(Charsets.ISO_8859_1)
        val out = PdfAnnotationWriter.append(broken, sampleAnnotations())
        assertPrefix(broken, out)
        // последняя секция полная, поэтому новый файл читается без восстановления
        checkAll(out)
    }

    @Test fun pageOutOfRange() {
        for (bad in listOf(5, -1)) {
            try {
                PdfAnnotationWriter.append(build(PdfGen.Mode.CLASSIC), listOf(NoteAnnotation(bad, PtPoint(0f, 0f), "x", RED)))
                fail()
            } catch (e: PdfFormatException) {
                assertTrue(e.message!!.contains("отсутствует"))
            }
        }
    }

    @Test fun emptyListReturnsSameBytes() {
        val pdf = build(PdfGen.Mode.CLASSIC)
        assertArrayEquals(pdf, PdfAnnotationWriter.append(pdf, emptyList()))
    }

    @Test fun inkAppearanceHasRoundCapsAndOpacity() {
        val pdf = build(PdfGen.Mode.CLASSIC)
        val out = PdfAnnotationWriter.append(pdf, listOf(InkAnnotation(0, listOf(listOf(PtPoint(1f, 1f), PtPoint(5f, 5f))), RED, 3f, 0.4f)))
        val doc = PdfDocument(out)
        val a = annots(doc, 0).last()
        val ap = doc.resolve(a["AP"].asDict()!!["N"]) as PdfStream
        val c = String(ap.rawBytes())
        assertTrue(c.contains("3 w 1 J 1 j"))
        assertTrue(c.contains("/GS0 gs"))
        assertArrayEquals(nums(a["Rect"]), nums(ap.dict["BBox"]), 0.0)
        assertEquals(0.4, ap.dict["Resources"].asDict()!!["ExtGState"].asDict()!!["GS0"].asDict()!!["ca"].asNum()!!, 1e-9)
    }
}
