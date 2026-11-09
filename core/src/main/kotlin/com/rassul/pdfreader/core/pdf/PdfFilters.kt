package com.rassul.pdfreader.core.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Декодирование потоков: только то, что нужно для служебных структур
 * (xref-потоки, объектные потоки): /FlateDecode + предикторы (ISO 32000-1 §7.4.4).
 */
object PdfFilters {

    fun decode(stream: PdfStream, resolve: (PdfObj?) -> PdfObj? = { it }): ByteArray {
        val filterObj = resolve(stream.dict["Filter"])
        val parmsObj = resolve(stream.dict["DecodeParms"] ?: stream.dict["DP"])
        val filters: List<String> = when (filterObj) {
            null, PdfNull -> emptyList()
            is PdfName -> listOf(filterObj.name)
            is PdfArray -> filterObj.items.mapNotNull { resolve(it).asName() }
            else -> emptyList()
        }
        if (filters.isEmpty()) return stream.rawBytes()
        var data = stream.src
        var off = stream.off
        var len = stream.len
        filters.forEachIndexed { i, f ->
            val parms = when (parmsObj) {
                is PdfArray -> resolve(parmsObj.items.getOrNull(i)).asDict()
                else -> parmsObj.asDict()
            }
            val decoded = when (f) {
                "FlateDecode", "Fl" -> applyPredictor(inflate(data, off, len), parms, resolve)
                else -> throw PdfFormatException("Неподдерживаемый фильтр потока PDF: /$f")
            }
            data = decoded; off = 0; len = decoded.size
        }
        return if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
    }

    /**
     * zlib (RFC 1950/1951), §7.4.4. При повреждённых данных возвращаем то, что успели
     * распаковать (как делают pdf.js и MuPDF); если не распаковалось ничего — пробуем
     * «сырой» deflate без заголовка zlib.
     */
    fun inflate(src: ByteArray, off: Int, len: Int): ByteArray {
        val r = inflateImpl(src, off, len, nowrap = false)
        if (r != null && r.isNotEmpty()) return r
        return inflateImpl(src, off, len, nowrap = true)
            ?: throw PdfFormatException("Повреждённый сжатый поток в PDF")
    }

    private fun inflateImpl(src: ByteArray, off: Int, len: Int, nowrap: Boolean): ByteArray? {
        val inf = Inflater(nowrap)
        try {
            inf.setInput(src, off, len)
            val out = ByteArrayOutputStream(maxOf(64, len * 3))
            val tmp = ByteArray(16 * 1024)
            while (!inf.finished()) {
                val n = try { inf.inflate(tmp) } catch (e: java.util.zip.DataFormatException) { break }
                if (n == 0) break // нужен ввод/словарь — данных больше нет
                out.write(tmp, 0, n)
            }
            return out.toByteArray()
        } finally { inf.end() }
    }

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        try {
            d.setInput(data); d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val tmp = ByteArray(16 * 1024)
            while (!d.finished()) { val n = d.deflate(tmp); out.write(tmp, 0, n) }
            return out.toByteArray()
        } finally { d.end() }
    }

    /**
     * Предикторы (§7.4.4.4, таблицы 8 и 9): 1 — нет; 2 — TIFF Predictor 2;
     * 10…15 — PNG (каждая строка начинается байтом типа фильтра PNG: 0 None, 1 Sub,
     * 2 Up, 3 Average, 4 Paeth — RFC 2083 §6). /Colors, /BitsPerComponent, /Columns
     * по умолчанию 1, 8, 1.
     */
    fun applyPredictor(data: ByteArray, parms: PdfDict?, resolve: (PdfObj?) -> PdfObj? = { it }): ByteArray {
        if (parms == null) return data
        val predictor = resolve(parms["Predictor"]).asInt() ?: 1
        if (predictor <= 1) return data
        val colors = (resolve(parms["Colors"]).asInt() ?: 1).coerceIn(1, 32)
        val bpc = (resolve(parms["BitsPerComponent"]).asInt() ?: 8).coerceIn(1, 16)
        val columns = (resolve(parms["Columns"]).asInt() ?: 1).coerceIn(1, 1 shl 20)
        val bpp = maxOf(1, (colors * bpc + 7) / 8)
        val rowLen = (colors * bpc * columns + 7) / 8
        if (predictor == 2) {
            if (bpc != 8) throw PdfFormatException("Неподдерживаемый предиктор TIFF в PDF")
            val out = data.copyOf()
            var r = 0
            while (r < out.size) {
                for (i in bpp until minOf(rowLen, out.size - r)) out[r + i] = (out[r + i] + out[r + i - bpp]).toByte()
                r += rowLen
            }
            return out
        }
        val rows = data.size / (rowLen + 1)
        val out = ByteArray(rows * rowLen)
        var prev = 0 // смещение предыдущей строки в out (для первой строки — нули)
        for (row in 0 until rows) {
            val inOff = row * (rowLen + 1)
            val type = data[inOff].toInt() and 0xFF
            val o = row * rowLen
            for (i in 0 until rowLen) {
                val raw = data[inOff + 1 + i].toInt() and 0xFF
                val left = if (i >= bpp) out[o + i - bpp].toInt() and 0xFF else 0
                val up = if (row > 0) out[prev + i].toInt() and 0xFF else 0
                val upLeft = if (row > 0 && i >= bpp) out[prev + i - bpp].toInt() and 0xFF else 0
                val v = when (type) {
                    0 -> raw
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> raw + paeth(left, up, upLeft)
                    else -> throw PdfFormatException("Неверный PNG-предиктор в потоке PDF")
                }
                out[o + i] = v.toByte()
            }
            prev = o
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
