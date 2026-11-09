package com.rassul.pdfreader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfOutlineReaderTest {

    private val expected = listOf(
        OutlineItem("Глава 1. Введение", 0, 0, listOf(
            OutlineItem("Section 1.1 ﬁnal (x)", 1, 1, emptyList()),
            OutlineItem("Раздел 1.2", 2, 1, emptyList()),
        )),
        OutlineItem("Chapter 2", 3, 0, listOf(
            OutlineItem("Broken", null, 1, listOf(
                OutlineItem("Глубоко ✓", 4, 2, emptyList()),
            )),
        )),
    )

    @Test fun classic() = assertEquals(expected, PdfOutlineReader.read(PdfGen.sample().build(PdfGen.Mode.CLASSIC)))

    @Test fun xrefStreamWithObjectStreams() =
        assertEquals(expected, PdfOutlineReader.read(PdfGen.sample().build(PdfGen.Mode.XREF_STREAM, PdfGen.COMPRESSIBLE)))

    @Test fun hybrid() =
        assertEquals(expected, PdfOutlineReader.read(PdfGen.sample().build(PdfGen.Mode.HYBRID, setOf(20, 21, 22, 23, 24, 25, 26, 27, 30, 31, 32, 33))))

    @Test fun brokenXref() {
        val pdf = PdfGen.sample().build(PdfGen.Mode.XREF_STREAM, PdfGen.COMPRESSIBLE)
        val s = String(pdf, Charsets.ISO_8859_1)
        val broken = s.replace(Regex("startxref\\s+\\d+"), "startxref\n77").toByteArray(Charsets.ISO_8859_1)
        assertEquals(expected, PdfOutlineReader.read(broken))
    }

    @Test fun updatedTitleFromIncrementalUpdate() {
        val base = PdfGen.sample().build(PdfGen.Mode.CLASSIC)
        val upd = PdfGen.incremental(base, mapOf(24 to "<< /Title ${PdfGen.utf16Hex("Глава 2")} /Parent 20 0 R /Prev 21 0 R /Dest [5 0 R /Fit] >>"), size = 61)
        val r = PdfOutlineReader.read(upd)
        assertEquals("Глава 2", r[1].title)
        assertEquals(1, r[1].pageIndex)
        assertTrue(r[1].children.isEmpty())
    }

    @Test fun noOutline() {
        val g = PdfGen.sample()
        g.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        assertEquals(emptyList<OutlineItem>(), PdfOutlineReader.read(g.build(PdfGen.Mode.CLASSIC)))
    }

    @Test fun encryptedHasNoOutlineAndRejectsAppend() {
        val g = PdfGen.sample()
        g.obj(70, "<< /Filter /Standard /V 1 /R 2 /O <00> /U <00> /P -4 >>")
        val pdf = g.build(PdfGen.Mode.CLASSIC, trailerExtra = "/Encrypt 70 0 R")
        assertEquals(emptyList<OutlineItem>(), PdfOutlineReader.read(pdf))
        try {
            PdfAnnotationWriter.append(pdf, listOf(com.rassul.pdfreader.core.annot.NoteAnnotation(0, com.rassul.pdfreader.core.annot.PtPoint(1f, 1f), "x", 0)))
            fail()
        } catch (e: PdfFormatException) {
            assertEquals("Файл зашифрован; пометки в него сохранить нельзя", e.message)
        }
    }

    @Test fun garbageIsFormatException() {
        try { PdfOutlineReader.read("hello, not a pdf".toByteArray()); fail() } catch (e: PdfFormatException) {
            assertTrue(e.message!!.contains("PDF"))
        }
    }
}
