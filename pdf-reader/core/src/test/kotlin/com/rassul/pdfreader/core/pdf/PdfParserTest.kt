package com.rassul.pdfreader.core.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfParserTest {
    private fun parse(s: String): PdfObj = PdfParser(s.toByteArray(Charsets.ISO_8859_1)).parseObject()

    @Test fun literalStringEscapesAndNesting() {
        val o = parse("(a\\(b\\)c (nested (deep)) \\\\ \\n\\101\\0x\\\nline\r\nend)") as PdfString
        assertEquals("a(b)c (nested (deep)) \\ \nA\u0000xline\nend", String(o.bytes, Charsets.ISO_8859_1))
    }

    @Test fun hexStringOddDigitsAndSpaces() {
        val o = parse("<48 65 6C6C 6F7>") as PdfString
        assertArrayEquals("Hello".toByteArray() + byteArrayOf(0x70), o.bytes)
    }

    @Test fun namesWithHashEscapes() {
        assertEquals(PdfName("A B#"), parse("/A#20B#23"))
        assertEquals(PdfName(""), parse("/ "))
    }

    @Test fun numbersRefsArraysDicts() {
        val a = parse("[1 0 R 2 3.5 -4 .5 +7 true null /N 12 0 R]") as PdfArray
        assertEquals(PdfRef(1, 0), a[0])
        assertEquals(PdfNum(2.0, true), a[1])
        assertEquals(PdfNum(3.5, false), a[2])
        assertEquals(PdfNum(-4.0, true), a[3])
        assertEquals(PdfNum(0.5, false), a[4])
        assertEquals(PdfNum(7.0, true), a[5])
        assertEquals(PdfBool(true), a[6])
        assertEquals(PdfNull, a[7])
        assertEquals(PdfName("N"), a[8])
        assertEquals(PdfRef(12, 0), a[9])
        val d = parse("<< /A 1 /B << /C [1 2] >> % comment\n /D (x) >>") as PdfDict
        assertEquals(1, d["A"].asInt())
        assertEquals(2, d["B"].asDict()!!["C"].asArray()!!.size)
        assertTrue(d["D"] is PdfString)
    }

    @Test fun streamWithWrongLengthIsRecovered() {
        val s = "<< /Length 999 >>\nstream\nHELLO\nendstream"
        val st = parse(s) as PdfStream
        assertEquals("HELLO", String(st.rawBytes()))
    }

    @Test fun indirectObjectHeader() {
        val p = PdfParser("12 3 obj\n<< /X 1 >>\nendobj".toByteArray())
        val (n, g, o) = p.parseIndirect()!!
        assertEquals(12, n); assertEquals(3, g)
        assertEquals(1, o.asDict()!!["X"].asInt())
    }

    @Test fun pngPredictors() {
        // 2 строки по 3 байта: Sub, затем Up, Average, Paeth
        val parms = pdfDictOf("Predictor" to PdfNum(12), "Columns" to PdfNum(3))
        val enc = byteArrayOf(1, 10, 5, 5, 2, 1, 1, 1, 3, 0, 0, 0, 4, 1, 1, 1)
        val dec = PdfFilters.applyPredictor(enc, parms)
        // строка1: 10,15,20; строка2 (Up): 11,16,21; строка3 (Avg, bpp=1): 5, (5+16)/2=10, (10+21)/2=15;
        // строка4 (Paeth): 1+paeth(0,5,0)=6, 1+paeth(6,10,5)=11, 1+paeth(11,15,10)=16
        assertArrayEquals(byteArrayOf(10, 15, 20, 11, 16, 21, 5, 10, 15, 6, 11, 16), dec)
    }

    @Test fun flateRoundTrip() {
        val data = ByteArray(5000) { (it % 37).toByte() }
        val st = PdfStream(pdfDictOf("Filter" to PdfName("FlateDecode")), PdfGen.deflate(data))
        assertArrayEquals(data, PdfFilters.decode(st))
    }

    @Test fun serializerRoundTrip() {
        val d = pdfDictOf(
            "Name With Space" to PdfName("a/b#"),
            "S" to PdfString(byteArrayOf('('.code.toByte(), 0, 0xFE.toByte(), '\\'.code.toByte(), ')'.code.toByte())),
            "N" to pdfNums(1.0, 0.5, -0.33333333, 1e-7),
        )
        val back = PdfParser(PdfSerializer.toBytes(d)).parseObject() as PdfDict
        assertEquals(PdfName("a/b#"), back["Name With Space"])
        assertArrayEquals((d["S"] as PdfString).bytes, (back["S"] as PdfString).bytes)
        assertEquals("[1 0.5 -0.3333 0]", PdfSerializer.toBytes(back["N"]!!).toString(Charsets.ISO_8859_1))
    }

    @Test fun textDecoding() {
        assertEquals("Привет", PdfText.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Привет".toByteArray(Charsets.UTF_16BE)))
        assertEquals("Привет", PdfText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Привет".toByteArray(Charsets.UTF_8)))
        assertEquals("• ﬁ € é", PdfText.decode(byteArrayOf(0x80.toByte(), 32, 0x93.toByte(), 32, 0xA0.toByte(), 32, 0xE9.toByte())))
    }

    @Test fun rotateNormalization() {
        assertEquals(270, PdfDocument.normRotate(-90))
        assertEquals(90, PdfDocument.normRotate(450))
        assertEquals(0, PdfDocument.normRotate(45))
        assertEquals(0, PdfDocument.normRotate(720))
        assertEquals(180, PdfDocument.normRotate(-180))
    }

    @Test fun pageTreeInheritance() {
        for (mode in PdfGen.Mode.values()) {
            val pdf = PdfGen.sample().build(mode, if (mode == PdfGen.Mode.CLASSIC) emptySet() else PdfGen.COMPRESSIBLE)
            val doc = PdfDocument(pdf)
            assertTrue(!doc.repaired)
            val pages = doc.pages()
            assertEquals(5, pages.size)
            assertEquals(listOf(0, 90, 180, 270, 0), pages.map { it.rotate })
            assertEquals(listOf(4, 5, 6, 11, 12), pages.map { it.ref!!.num })
            assertArrayEquals(doubleArrayOf(0.0, 0.0, 612.0, 842.0), pages[1].mediaBox, 0.0)
            assertArrayEquals(doubleArrayOf(10.0, 20.0, 610.0, 820.0), pages[3].cropBox, 0.0)
            assertArrayEquals(doubleArrayOf(0.0, 0.0, 300.0, 400.0), pages[4].mediaBox, 0.0)
            assertEquals(doc.lastXrefIsStream, mode == PdfGen.Mode.XREF_STREAM)
            // поток с косвенным /Length (объект 60 — в объектном потоке для сжатых режимов)
            val c = doc.getObject(52) as PdfStream
            assertTrue(String(c.rawBytes()).startsWith("BT /F1 18 Tf"))
            assertTrue(String(c.rawBytes()).endsWith("h S"))
        }
    }

    @Test fun brokenXrefIsRepairedByScanning() {
        val pdf = PdfGen.sample().build(PdfGen.Mode.CLASSIC)
        val s = String(pdf, Charsets.ISO_8859_1)
        val i = s.lastIndexOf("startxref")
        val broken = (s.substring(0, i) + "startxref\n123\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)
        val doc = PdfDocument(broken)
        assertTrue(doc.repaired)
        assertEquals(5, doc.pages().size)
    }

    @Test fun prevChainNewerWins() {
        val base = PdfGen.sample().build(PdfGen.Mode.XREF_STREAM, PdfGen.COMPRESSIBLE)
        val upd = PdfGen.incremental(base, mapOf(5 to "<< /Type /Page /Parent 3 0 R /Contents 51 0 R /Rotate 270 >>"), size = 80)
        val doc = PdfDocument(upd)
        assertTrue(!doc.repaired)
        assertEquals(270, doc.pages()[1].rotate)
        assertEquals(0, doc.pages()[0].rotate)
    }
}
