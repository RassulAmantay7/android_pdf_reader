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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/** Аудит (code-formula-auditor): преобразование координат, формат чисел, xref, строки. */
class PdfWriterAuditTest {
    private val rnd = Random(777)

    private fun geo(rotate: Int, crop: DoubleArray) =
        PageGeometry.of(PageInfo(0, PdfRef(1, 0), PdfDict(), doubleArrayOf(0.0, 0.0, 612.0, 842.0), crop, PdfDocument.normRotate(rotate), null))

    /** Обратное к toPdf — по формулам эталона (docs/math-review.md), независимо от кода. */
    private fun fromPdf(g: PageGeometry, ux: Double, uy: Double): DoubleArray = when (g.rotate) {
        90 -> doubleArrayOf(uy - g.y0, ux - g.x0)
        180 -> doubleArrayOf(g.x1 - ux, uy - g.y0)
        270 -> doubleArrayOf(g.y1 - uy, g.x1 - ux)
        else -> doubleArrayOf(ux - g.x0, g.y1 - uy)
    }

    @Test fun rotateNormalization() {
        assertEquals(270, PdfDocument.normRotate(-90))
        assertEquals(90, PdfDocument.normRotate(450))
        assertEquals(0, PdfDocument.normRotate(45))
        assertEquals(180, PdfDocument.normRotate(-180))
        assertEquals(90, PdfDocument.normRotate(-630))
        assertEquals(0, PdfDocument.normRotate(-45))
    }

    /** Страница → PDF → страница = тождество; углы видимой области → углы C; размер W×H / H×W. */
    @Test fun propertyPagePdfPage() {
        for (rot in intArrayOf(0, 90, 180, 270)) repeat(300) {
            val l = rnd.nextDouble(0.0, 300.0); val b = rnd.nextDouble(0.0, 300.0)
            val crop = doubleArrayOf(l, b, l + rnd.nextDouble(10.0, 312.0), b + rnd.nextDouble(10.0, 542.0))
            val g = geo(rot, crop)
            val wc = g.x1 - g.x0; val hc = g.y1 - g.y0
            val (W, H) = if (rot % 180 == 0) wc to hc else hc to wc
            val x = rnd.nextDouble(0.0, W); val y = rnd.nextDouble(0.0, H)
            val u = g.toPdf(x, y)
            assertTrue(u[0] in g.x0 - 1e-9..g.x1 + 1e-9 && u[1] in g.y0 - 1e-9..g.y1 + 1e-9)
            assertArrayEquals(doubleArrayOf(x, y), fromPdf(g, u[0], u[1]), 1e-9)
            // противоположные углы видимой страницы → противоположные углы C
            val r = g.rectToPdf(0.0, 0.0, W, H)
            assertArrayEquals(doubleArrayOf(g.x0, g.y0, g.x1, g.y1), r, 1e-9)
        }
    }

    /** /Matrix /Text: образ направления «вверх по видимой странице» (0,−1) в PDF. */
    @Test fun formMatrixMatchesToPdfLinearPart() {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val g = geo(rot, doubleArrayOf(10.0, 20.0, 610.0, 820.0))
            val o = g.toPdf(0.0, 0.0); val ex = g.toPdf(1.0, 0.0); val up = g.toPdf(0.0, -1.0)
            val m = g.formMatrix()
            assertArrayEquals(doubleArrayOf(ex[0] - o[0], ex[1] - o[1], up[0] - o[0], up[1] - o[1]), m.copyOfRange(0, 4), 1e-12)
            assertEquals(1.0, m[0] * m[3] - m[1] * m[2], 1e-12) // поворот, без отражения
        }
    }

    /** Числа: без экспоненты, без запятой, ≤ 4 знаков, при любой локали. */
    @Test fun numberFormat() {
        val saved = Locale.getDefault()
        try {
            for (loc in listOf("ar-EG", "fa-IR", "de-DE", "ru-RU", "th-TH-u-nu-thai", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(loc))
                assertEquals("0", PdfSerializer.num(1e-5))
                assertEquals("0", PdfSerializer.num(-4e-5))
                assertEquals("0.0001", PdfSerializer.num(1e-4))
                assertEquals("0.0012", PdfSerializer.num(1.23456e-3))
                assertEquals("-12.5", PdfSerializer.num(-12.5))
                assertEquals("100000000000000000000", PdfSerializer.num(1e20))
                assertEquals("0", PdfSerializer.num(Double.NaN))
                assertEquals("0", PdfSerializer.num(Double.POSITIVE_INFINITY))
                assertEquals("0", PdfSerializer.num(-0.0))
                repeat(300) {
                    val v = (rnd.nextDouble() - 0.5) * Math.pow(10.0, rnd.nextInt(-8, 9).toDouble())
                    val s = PdfSerializer.num(v)
                    assertTrue("$loc: «$s»", s.matches(Regex("-?\\d+(\\.\\d{1,4})?")))
                    assertEquals(v, s.toDouble(), 5.0001e-5)
                }
                assertEquals("<C0FFEE>", String(PdfSerializer.toBytes(PdfString(byteArrayOf(0xC0.toByte(), 0xFF.toByte(), 0xEE.toByte()), hex = true)), Charsets.ISO_8859_1))
            }
        } finally { Locale.setDefault(saved) }
    }

    /** /Contents: UTF-16BE с BOM, ( ) \ экранированы, CR/LF — восьмеричные; читается обратно. */
    @Test fun contentsEscaping() {
        val text = "a(b)c\\d)(\r\n Привет ✓ ĨĩŜ"
        val bytes = PdfSerializer.toBytes(PdfText.encodeUtf16(text))
        val s = String(bytes, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("(\\376\\377"))
        // в теле нет неэкранированных скобок/обратных слешей и сырых CR/LF
        var i = 1
        while (i < s.length - 1) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            assertFalse("сырой символ ${c.code} в $i", c == '(' || c == ')' || c == '\r' || c == '\n')
            i++
        }
        val back = PdfParser(bytes, 0).parseObject() as PdfString
        assertEquals(text, PdfText.decode(back.bytes))
    }

    private fun randomAnnots(pages: Int): List<Annotation> = (0 until 60).map {
        val p = rnd.nextInt(pages)
        when (it % 3) {
            0 -> HighlightAnnotation(p, (0 until rnd.nextInt(1, 4)).map {
                val l = rnd.nextFloat() * 400f; val t = rnd.nextFloat() * 400f
                PtRect(l, t, l + rnd.nextFloat() * 150f + 0.001f, t + rnd.nextFloat() * 20f + 0.001f)
            }, 0x80FFEB3B.toInt(), "x(${it})\\")
            1 -> InkAnnotation(p, listOf((0 until rnd.nextInt(1, 6)).map { PtPoint(rnd.nextFloat() * 500f, rnd.nextFloat() * 500f) }),
                0xFFE53935.toInt(), rnd.nextFloat() * 10f + 0.12345f, rnd.nextFloat())
            else -> NoteAnnotation(p, PtPoint(rnd.nextFloat() * 500f, rnd.nextFloat() * 500f), "n$it", 0xFFFFC107.toInt())
        }
    }

    /**
     * append → парсится обратно: Rect ⊇ QuadPoints и Ink-точек ± w/2, xref-записи ровно 20 байт
     * и указывают на «N G obj», /Size = max номер + 1. Запуск под арабской локалью.
     */
    @Test fun propertyAppendRoundTripUnderArabicLocale() {
        val saved = Locale.getDefault()
        try {
            // исходник строим до смены локали: тестовый генератор сам пишет xref через String.format
            val pdf = PdfGen.sample().build(PdfGen.Mode.CLASSIC)
            assertFalse(PdfDocument(pdf).repaired)
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val ann = randomAnnots(5)
            val out = PdfAnnotationWriter.append(pdf, ann)
            val doc = PdfDocument(out)
            assertFalse("файл пришлось восстанавливать", doc.repaired)
            val tail = String(out, pdf.size, out.size - pdf.size, Charsets.ISO_8859_1)
            assertTrue(tail.all { it.code < 0x80 })
            checkXrefTable(out)
            var maxNum = 0
            for (p in 0 until 5) {
                val g = PageGeometry.of(doc.pages()[p])
                for (a in PdfAnnotationWriterTest.annots(doc, p)) {
                    val r = PdfAnnotationWriterTest.nums(a["Rect"])
                    assertTrue(r[0] < r[2] && r[1] < r[3])
                    when (a["Subtype"].asName()) {
                        "Highlight" -> {
                            val q = PdfAnnotationWriterTest.nums(a["QuadPoints"])
                            assertEquals(0, q.size % 8)
                            for (k in q.indices step 2) assertTrue(q[k] in r[0]..r[2] && q[k + 1] in r[1]..r[3])
                            for (k in q.indices step 8) { // порядок «Z»: TL, TR, BL, BR в осях PDF
                                assertTrue(q[k] < q[k + 2] && q[k + 1] == q[k + 3] && q[k + 5] < q[k + 1] && q[k + 4] == q[k] && q[k + 6] == q[k + 2])
                            }
                            assertTrue(r.all { it == Math.rint(it) }) // округление наружу до целых
                        }
                        "Ink" -> {
                            val w = a["BS"].asDict()!!["W"].asNum()!!
                            for (s in a["InkList"].asArray()!!.items) {
                                val v = PdfAnnotationWriterTest.nums(s)
                                for (k in v.indices step 2) {
                                    assertTrue(v[k] - w / 2 >= r[0] - 1e-6 && v[k] + w / 2 <= r[2] + 1e-6)
                                    assertTrue(v[k + 1] - w / 2 >= r[1] - 1e-6 && v[k + 1] + w / 2 <= r[3] + 1e-6)
                                }
                            }
                            val ap = doc.resolve(a["AP"].asDict()!!["N"]) as PdfStream
                            assertArrayEquals(r, PdfAnnotationWriterTest.nums(ap.dict["BBox"]), 0.0)
                            assertTrue(ap.dict["Matrix"] == null)
                        }
                        "Text" -> {
                            val ap = doc.resolve(a["AP"].asDict()!!["N"]) as PdfStream
                            assertArrayEquals(g.formMatrix(), PdfAnnotationWriterTest.nums(ap.dict["Matrix"]), 0.0)
                            assertEquals(20.0, r[2] - r[0], 1e-3); assertEquals(20.0, r[3] - r[1], 1e-3)
                        }
                    }
                    if (a["Subtype"].asName() != "Square") {
                        val ca = a["CA"].asNum()!!
                        assertTrue(ca in 0.0..1.0)
                    }
                }
                doc.resolve(doc.pages()[p].dict["Annots"]).asArray()!!.items.forEach { maxNum = maxOf(maxNum, (it as PdfRef).num) }
            }
            assertTrue((doc.trailer["Size"].asInt() ?: 0) > maxNum)
            val written = ann.count { it !is HighlightAnnotation || it.rects.isNotEmpty() }
            assertEquals(written, (0 until 5).sumOf { PdfAnnotationWriterTest.annots(doc, it).count { a -> a["Subtype"].asName() != "Square" } })
            val dir = if (File("core/src").isDirectory) File("core/build/pdf-out") else File("build/pdf-out")
            dir.mkdirs(); File(dir, "audit-arabic-locale.pdf").writeBytes(out)
        } finally { Locale.setDefault(saved) }
    }

    /** Последняя классическая таблица xref: записи по 20 байт, смещения ведут на «N G obj». */
    private fun checkXrefTable(out: ByteArray) {
        val s = String(out, Charsets.ISO_8859_1)
        val sx = Regex("startxref\\s+(\\d+)\\s+%%EOF\\s*$").find(s)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("xref\n", sx))
        var pos = sx + 5
        var maxNum = -1
        while (!s.startsWith("trailer", pos)) {
            val eol = s.indexOf('\n', pos)
            val (start, count) = s.substring(pos, eol).trim().split(' ').map { it.toInt() }
            pos = eol + 1
            for (k in 0 until count) {
                val e = s.substring(pos, pos + 20)
                assertTrue("запись «$e»", e.matches(Regex("\\d{10} \\d{5} [nf]\r\n")))
                val num = start + k; maxNum = maxOf(maxNum, num)
                if (e[17] == 'n') {
                    val off = e.substring(0, 10).toInt()
                    assertTrue("объект $num по смещению $off", s.startsWith("$num ${e.substring(11, 16).toInt()} obj", off))
                }
                pos += 20
            }
        }
        val size = Regex("/Size (\\d+)").find(s, pos)!!.groupValues[1].toInt()
        assertTrue(size > maxNum)
        assertTrue(Regex("/Prev (\\d+)").containsMatchIn(s.substring(pos)))
    }
}
