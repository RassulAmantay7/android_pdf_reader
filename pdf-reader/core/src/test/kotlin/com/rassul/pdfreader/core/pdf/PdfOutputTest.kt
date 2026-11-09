package com.rassul.pdfreader.core.pdf

import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.core.annot.PtPoint
import com.rassul.pdfreader.core.annot.PtRect
import org.junit.Test
import java.io.File

/**
 * Пишет результаты append в core/build/pdf-out/ для внешней проверки
 * (scripts/verify_pdf.py, qpdf --check, рендер PyMuPDF). Файлы не коммитятся (build/ в .gitignore).
 */
class PdfOutputTest {
    private fun outDir(): File {
        val d = if (File("core/src").isDirectory) File("core/build/pdf-out") else File("build/pdf-out")
        d.mkdirs(); return d
    }

    /** Те же пометки + наглядные: «рамка» пером вдоль верхнего левого угла видимой страницы. */
    private fun visual() = PdfAnnotationWriterTest.sampleAnnotations() + (0 until 5).flatMap { p ->
        listOf(
            // «Г»-образная линия: от (30,120) вверх к (30,30) и вправо к (200,30) — показывает верх и лево
            InkAnnotation(p, listOf(listOf(PtPoint(30f, 120f), PtPoint(30f, 30f), PtPoint(200f, 30f))), 0xFF1E88E5.toInt(), 6f, 0.8f),
            HighlightAnnotation(p, listOf(PtRect(40f, 140f, 240f, 160f), PtRect(40f, 162f, 180f, 182f)), 0xFF66BB6A.toInt()),
            NoteAnnotation(p, PtPoint(220f, 20f), "Заметка $p", 0xFFFFC107.toInt()),
        )
    }

    @Test fun writeSamples() {
        val dir = outDir()
        for (mode in PdfGen.Mode.values()) {
            val pdf = PdfGen.sample().build(mode, if (mode == PdfGen.Mode.CLASSIC) emptySet() else PdfGen.COMPRESSIBLE)
            File(dir, "${mode.name.lowercase()}-orig.pdf").writeBytes(pdf)
            val once = PdfAnnotationWriter.append(pdf, visual())
            File(dir, "${mode.name.lowercase()}-annot.pdf").writeBytes(once)
            File(dir, "${mode.name.lowercase()}-annot2.pdf").writeBytes(PdfAnnotationWriter.append(once, PdfAnnotationWriterTest.sampleAnnotations()))
        }
        val s = String(PdfGen.sample().build(PdfGen.Mode.XREF_STREAM, PdfGen.COMPRESSIBLE), Charsets.ISO_8859_1)
        val broken = s.replace(Regex("startxref\\s+\\d+"), "startxref\n77").toByteArray(Charsets.ISO_8859_1)
        File(dir, "repaired-annot.pdf").writeBytes(PdfAnnotationWriter.append(broken, visual()))
    }
}
