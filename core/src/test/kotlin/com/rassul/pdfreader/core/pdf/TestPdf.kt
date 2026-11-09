package com.rassul.pdfreader.core.pdf

import java.io.ByteArrayOutputStream
import java.util.TreeMap
import java.util.zip.Deflater

/**
 * Маленький генератор PDF для тестов: классическая xref-таблица, xref-поток (Flate +
 * PNG-предиктор Up), объектные потоки, гибридный файл (/XRefStm), ручное инкрементальное
 * обновление с /Prev.
 */
class PdfGen {
    enum class Mode { CLASSIC, XREF_STREAM, HYBRID }

    private val objs = TreeMap<Int, ByteArray>()
    private val streams = HashSet<Int>()

    fun obj(num: Int, body: String) { objs[num] = body.toByteArray(Charsets.ISO_8859_1) }

    /** Поток; [dict] — содержимое словаря без << >> и без /Length. */
    fun stream(num: Int, dict: String, data: ByteArray, indirectLengthNum: Int? = null) {
        val o = ByteArrayOutputStream()
        val len = if (indirectLengthNum != null) { obj(indirectLengthNum, data.size.toString()); "$indirectLengthNum 0 R" } else data.size.toString()
        o.write("<< $dict /Length $len >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        o.write(data)
        o.write("\nendstream".toByteArray())
        objs[num] = o.toByteArray()
        streams.add(num)
    }

    fun build(mode: Mode, compress: Set<Int> = emptySet(), trailerExtra: String = "", root: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val maxNum = objs.lastKey()
        val toCompress = if (mode == Mode.CLASSIC) emptySet() else compress.filter { it in objs && it !in streams }.toSortedSet()
        val offsets = TreeMap<Int, Int>()
        val compressed = TreeMap<Int, Pair<Int, Int>>() // num → (stm, idx)
        for ((n, b) in objs) if (n !in toCompress) {
            offsets[n] = out.size()
            out.write("$n 0 obj\n".toByteArray()); out.write(b); out.write("\nendobj\n".toByteArray())
        }
        var next = maxNum + 1
        if (toCompress.isNotEmpty()) {
            val stmNum = next++
            val hdr = StringBuilder(); val body = ByteArrayOutputStream()
            toCompress.forEachIndexed { i, n ->
                hdr.append("$n ${body.size()} ")
                body.write(objs[n]!!); body.write(' '.code)
                compressed[n] = stmNum to i
            }
            val h = hdr.toString().toByteArray()
            val data = h + body.toByteArray()
            val z = deflate(data)
            offsets[stmNum] = out.size()
            out.write("$stmNum 0 obj\n<< /Type /ObjStm /N ${toCompress.size} /First ${h.size} /Filter /FlateDecode /Length ${z.size} >>\nstream\n".toByteArray())
            out.write(z); out.write("\nendstream\nendobj\n".toByteArray())
        }
        val trailer = "/Root $root 0 R /ID [<00112233445566778899AABBCCDDEEFF> <00112233445566778899AABBCCDDEEFF>] $trailerExtra"
        when (mode) {
            Mode.CLASSIC -> {
                val size = next
                val x = out.size()
                out.write(classicTable(offsets, size).toByteArray())
                out.write("trailer\n<< /Size $size $trailer >>\nstartxref\n$x\n%%EOF\n".toByteArray())
            }
            Mode.XREF_STREAM -> {
                val xnum = next++
                val x = out.size()
                offsets[xnum] = x
                writeXrefStream(out, xnum, offsets, compressed, next, trailer, null)
                out.write("startxref\n$x\n%%EOF\n".toByteArray())
            }
            Mode.HYBRID -> {
                // Поток xref только для сжатых объектов + классическая таблица для остальных.
                val xnum = next++
                val xs = out.size()
                offsets[xnum] = xs
                writeXrefStream(out, xnum, TreeMap(), compressed, next, "", null)
                val x = out.size()
                out.write(classicTable(offsets, next, skipMissing = true).toByteArray())
                out.write("trailer\n<< /Size $next $trailer /XRefStm $xs >>\nstartxref\n$x\n%%EOF\n".toByteArray())
            }
        }
        return out.toByteArray()
    }

    /**
     * Классическая таблица. [skipMissing] — пропускать отсутствующие номера (подсекциями),
     * а не писать их свободными: так делают гибридные файлы, иначе свободная запись
     * в таблице перекрывает /XRefStm (так её трактует, например, qpdf 12).
     */
    private fun classicTable(offsets: Map<Int, Int>, size: Int, skipMissing: Boolean = false): String {
        val sb = StringBuilder("xref\n")
        val nums = if (skipMissing) (setOf(0) + offsets.keys).sorted() else (0 until size).toList()
        var i = 0
        while (i < nums.size) {
            var j = i
            while (j + 1 < nums.size && nums[j + 1] == nums[j] + 1) j++
            sb.append("${nums[i]} ${j - i + 1}\n")
            for (n in nums[i]..nums[j]) {
                val o = offsets[n]
                if (o == null) sb.append(String.format("%010d %05d f\r\n", 0, if (n == 0) 65535 else 0))
                else sb.append(String.format("%010d %05d n\r\n", o, 0))
            }
            i = j + 1
        }
        return sb.toString()
    }

    /** Поток xref /W [1 4 2], Flate + /Predictor 12 (PNG Up) /Columns 7. */
    private fun writeXrefStream(
        out: ByteArrayOutputStream, xnum: Int, offsets: Map<Int, Int>, compressed: Map<Int, Pair<Int, Int>>,
        size: Int, trailer: String, prev: Int?,
    ) {
        val nums = (offsets.keys + compressed.keys).toSortedSet()
        val rows = ArrayList<ByteArray>()
        val index = StringBuilder()
        // одна подсекция на каждый подряд идущий диапазон
        var start = -1; var prevN = -2; var count = 0
        for (n in nums) {
            if (n != prevN + 1) { if (start >= 0) index.append("$start $count "); start = n; count = 0 }
            count++; prevN = n
            val r = ByteArray(7)
            val off = offsets[n]
            if (off != null) { r[0] = 1; put(r, 1, 4, off.toLong()); put(r, 5, 2, 0) }
            else { val (s, i) = compressed[n]!!; r[0] = 2; put(r, 1, 4, s.toLong()); put(r, 5, 2, i.toLong()) }
            rows.add(r)
        }
        if (start >= 0) index.append("$start $count")
        // PNG Up: каждая строка = 2, (row[i] - prevRow[i])
        val enc = ByteArrayOutputStream()
        var prevRow = ByteArray(7)
        for (r in rows) { enc.write(2); for (i in 0 until 7) enc.write((r[i] - prevRow[i]) and 0xFF); prevRow = r }
        val z = deflate(enc.toByteArray())
        val p = if (prev != null) "/Prev $prev" else ""
        out.write(("$xnum 0 obj\n<< /Type /XRef /Size $size /W [1 4 2] /Index [$index] /Filter /FlateDecode " +
            "/DecodeParms << /Predictor 12 /Columns 7 >> $p $trailer /Length ${z.size} >>\nstream\n").toByteArray())
        out.write(z); out.write("\nendstream\nendobj\n".toByteArray())
    }

    private fun put(r: ByteArray, off: Int, w: Int, v: Long) { for (i in 0 until w) r[off + i] = (v ushr (8 * (w - 1 - i))).toByte() }

    companion object {
        fun deflate(b: ByteArray): ByteArray {
            val d = Deflater(); d.setInput(b); d.finish()
            val o = ByteArrayOutputStream(); val t = ByteArray(4096)
            while (!d.finished()) o.write(t, 0, d.deflate(t))
            d.end(); return o.toByteArray()
        }

        fun startxref(pdf: ByteArray): Int {
            val s = String(pdf, Charsets.ISO_8859_1)
            val i = s.lastIndexOf("startxref")
            return s.substring(i + 9).trim().lineSequence().first().trim().toInt()
        }

        /** Ручное инкрементальное обновление (классическая секция с /Prev). */
        fun incremental(base: ByteArray, newObjs: Map<Int, String>, size: Int, root: Int = 1): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(base)
            val offs = TreeMap<Int, Int>()
            for ((n, body) in newObjs) { offs[n] = out.size(); out.write("$n 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)) }
            val x = out.size()
            val sb = StringBuilder("xref\n")
            for ((n, o) in offs) sb.append("$n 1\n").append(String.format("%010d %05d n\r\n", o, 0))
            sb.append("trailer\n<< /Size $size /Root $root 0 R /Prev ${startxref(base)} >>\nstartxref\n$x\n%%EOF\n")
            out.write(sb.toString().toByteArray())
            return out.toByteArray()
        }

        /** UTF-16BE с BOM в виде hex-строки PDF. */
        fun utf16Hex(s: String): String = "<FEFF" + s.toByteArray(Charsets.UTF_16BE).joinToString("") { String.format("%02X", it) } + ">"
        fun utf8BomHex(s: String): String = "<EFBBBF" + s.toByteArray(Charsets.UTF_8).joinToString("") { String.format("%02X", it) } + ">"

        /**
         * Образцовый документ: 5 страниц, наследование, /Rotate 0/90/180/−90/0, /CropBox,
         * прямой и косвенный /Annots, оглавление с вложенностью, именованными назначениями
         * (дерево имён с /Kids и старый /Dests), UTF-16/UTF-8/PDFDocEncoding-заголовками и циклом.
         */
        fun sample(): PdfGen = PdfGen().apply {
            obj(1, "<< /Type /Catalog /Pages 2 0 R /Outlines 20 0 R /Names << /Dests 30 0 R >> /Dests 35 0 R >>")
            obj(2, "<< /Type /Pages /Kids [3 0 R 10 0 R] /Count 5 /MediaBox [0 0 612 842] /Resources << /Font << /F1 7 0 R >> >> >>")
            obj(3, "<< /Type /Pages /Parent 2 0 R /Kids [4 0 R 5 0 R 6 0 R] /Count 3 /CropBox [10 20 610 820] >>")
            obj(4, "<< /Type /Page /Parent 3 0 R /Contents 50 0 R /Annots [42 0 R] >>")
            obj(5, "<< /Type /Page /Parent 3 0 R /Contents 51 0 R /Rotate 90 >>")
            obj(6, "<< /Type /Page /Parent 3 0 R /Contents 52 0 R /Rotate 180 >>")
            obj(7, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            obj(10, "<< /Type /Pages /Parent 2 0 R /Kids [11 0 R 12 0 R] /Count 2 /Rotate -90 /CropBox [10 20 610 820] >>")
            obj(11, "<< /Type /Page /Parent 10 0 R /Contents 53 0 R >>")
            obj(12, "<< /Type /Page /Parent 10 0 R /Contents 54 0 R /Rotate 0 /MediaBox [0 0 300 400] /CropBox [-10 -10 700 900] /Annots 40 0 R >>")
            // оглавление
            obj(20, "<< /Type /Outlines /First 21 0 R /Last 24 0 R /Count 4 >>")
            obj(21, "<< /Title ${utf16Hex("Глава 1. Введение")} /Parent 20 0 R /First 22 0 R /Last 23 0 R /Next 24 0 R /Dest [4 0 R /XYZ 0 800 0] >>")
            obj(22, "<< /Title (Section 1.1 \\223nal \\(x\\)) /Parent 21 0 R /Next 23 0 R /Dest (sec11) >>")
            obj(23, "<< /Title ${utf8BomHex("Раздел 1.2")} /Parent 21 0 R /Prev 22 0 R /Next 22 0 R /A << /S /GoTo /D /OldName >> >>")
            obj(24, "<< /Title (Chapter 2) /Parent 20 0 R /Prev 21 0 R /First 25 0 R /Last 25 0 R /Dest (ch2) >>")
            obj(25, "<< /Title (Broken) /Parent 24 0 R /First 26 0 R /Last 26 0 R /Dest [999 0 R /Fit] >>")
            obj(26, "<< /Title ${utf16Hex("Глубоко ✓")} /Parent 25 0 R /A 27 0 R >>")
            obj(27, "<< /S /GoTo /D [12 0 R /Fit] >>")
            // дерево имён
            obj(30, "<< /Kids [31 0 R 32 0 R] >>")
            obj(31, "<< /Limits [(aaa) (ch2)] /Names [(aaa) [4 0 R /Fit] (ch2) [11 0 R /Fit]] >>")
            obj(32, "<< /Limits [(sec11) (zzz)] /Names [(sec11) 33 0 R (zzz) [4 0 R /Fit]] >>")
            obj(33, "<< /D [5 0 R /XYZ null null null] >>")
            obj(35, "<< /OldName [6 0 R /Fit] >>")
            // существующие аннотации
            obj(40, "[41 0 R]")
            obj(41, "<< /Type /Annot /Subtype /Square /Rect [5 5 25 25] /C [0 0 1] >>")
            obj(42, "<< /Type /Annot /Subtype /Square /Rect [20 30 40 50] /C [0 0 1] >>")
            for (i in 0 until 5) {
                val c = "BT /F1 18 Tf 60 400 Td (Page ${i + 1}: text line for highlight) Tj ET\n" +
                    "0 0 1 RG 2 w 20 30 m 590 30 l 590 810 l 20 810 l h S"
                stream(50 + i, "", c.toByteArray(), indirectLengthNum = if (i == 2) 60 else null)
            }
        }

        /** Номера несжимаемых (потоки + те, что сжимать не хотим) — остальное в ObjStm. */
        val COMPRESSIBLE: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 20, 21, 22, 23, 24, 25, 26, 27, 30, 31, 32, 33, 35, 40, 41, 42, 60)
    }
}
