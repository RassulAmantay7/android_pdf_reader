package com.rassul.pdfreader.core.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Контрольные примеры рецензента (MediaBox [0 0 612 842], CropBox [10 20 610 820]), посчитаны вручную. */
class PageGeometryTest {
    private fun page(rotate: Int, crop: DoubleArray? = doubleArrayOf(10.0, 20.0, 610.0, 820.0), media: DoubleArray? = doubleArrayOf(0.0, 0.0, 612.0, 842.0)) =
        PageGeometry.of(PageInfo(0, PdfRef(1, 0), PdfDict(), media, crop, PdfDocument.normRotate(rotate), null))

    private fun pt(g: PageGeometry, x: Double, y: Double, ex: Double, ey: Double) =
        assertArrayEquals(doubleArrayOf(ex, ey), g.toPdf(x, y), 1e-9)

    @Test fun rotate0() {
        val g = page(0)
        pt(g, 0.0, 0.0, 10.0, 820.0); pt(g, 100.0, 50.0, 110.0, 770.0); pt(g, 600.0, 800.0, 610.0, 20.0)
    }

    @Test fun rotate90() {
        val g = page(90) // видимый размер 800×600
        pt(g, 0.0, 0.0, 10.0, 20.0); pt(g, 100.0, 50.0, 60.0, 120.0); pt(g, 800.0, 600.0, 610.0, 820.0)
    }

    @Test fun rotate180() {
        val g = page(180)
        pt(g, 0.0, 0.0, 610.0, 20.0); pt(g, 100.0, 50.0, 510.0, 70.0)
    }

    @Test fun rotate270() {
        val g = page(-90)
        pt(g, 0.0, 0.0, 610.0, 820.0); pt(g, 100.0, 50.0, 560.0, 720.0)
    }

    @Test fun cropBoxNormalizationAndIntersection() {
        // PdfDocument.box() нормализует [610 820 10 20] → [10 20 610 820]
        val doc = PdfDocument(PdfGen.sample().apply {
            obj(4, "<< /Type /Page /Parent 3 0 R /Contents 50 0 R /CropBox [610 820 10 20] >>")
        }.build(PdfGen.Mode.CLASSIC))
        val g1 = PageGeometry.of(doc.pages()[0])
        assertArrayEquals(doubleArrayOf(10.0, 20.0, 610.0, 820.0), doubleArrayOf(g1.x0, g1.y0, g1.x1, g1.y1), 0.0)
        val g2 = page(0, crop = doubleArrayOf(-10.0, -10.0, 700.0, 900.0))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 612.0, 842.0), doubleArrayOf(g2.x0, g2.y0, g2.x1, g2.y1), 0.0)
        val g3 = page(0, crop = null, media = null) // US Letter, как PDFium
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 612.0, 792.0), doubleArrayOf(g3.x0, g3.y0, g3.x1, g3.y1), 0.0)
    }

    @Test fun noteRectAndMatrixRotate90() {
        val g = page(90)
        assertArrayEquals(doubleArrayOf(60.0, 120.0, 80.0, 140.0), g.rectToPdf(100.0, 50.0, 120.0, 70.0), 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, -1.0, 0.0, 0.0, 0.0), g.formMatrix(), 0.0)
    }
}
