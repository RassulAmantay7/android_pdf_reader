package com.rassul.pdfreader.core.pdf

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

/** Текстовые строки PDF (ISO 32000-1 §7.9.2 «String types», приложение D). */
object PdfText {

    /**
     * PDFDocEncoding, коды 0x18–0x1F и 0x80–0xA0 (ISO 32000-1, приложение D.2, таблица D.2).
     * Остальные коды совпадают с ISO-8859-1 / ASCII; 0x7F, 0x9F, 0xAD не определены.
     */
    private val PDFDOC_SPECIAL: Map<Int, Char> = mapOf(
        0x18 to '˘', 0x19 to 'ˇ', 0x1A to 'ˆ', 0x1B to '˙',
        0x1C to '˝', 0x1D to '˛', 0x1E to '˚', 0x1F to '˜',
        0x80 to '•', 0x81 to '†', 0x82 to '‡', 0x83 to '…',
        0x84 to '—', 0x85 to '–', 0x86 to 'ƒ', 0x87 to '⁄',
        0x88 to '‹', 0x89 to '›', 0x8A to '−', 0x8B to '‰',
        0x8C to '„', 0x8D to '“', 0x8E to '”', 0x8F to '‘',
        0x90 to '’', 0x91 to '‚', 0x92 to '™', 0x93 to 'ﬁ',
        0x94 to 'ﬂ', 0x95 to 'Ł', 0x96 to 'Œ', 0x97 to 'Š',
        0x98 to 'Ÿ', 0x99 to 'Ž', 0x9A to 'ı', 0x9B to 'ł',
        0x9C to 'œ', 0x9D to 'š', 0x9E to 'ž', 0xA0 to '€',
    )

    /**
     * Текстовая строка (§7.9.2.2): UTF-16BE с BOM FE FF; UTF-8 с BOM EF BB BF (PDF 2.0,
     * ISO 32000-2 §7.9.2.2); иначе PDFDocEncoding. Для живучести принимаем и UTF-16LE с FF FE.
     * Маркеры языка «ESC код ESC» (§7.9.2.2) вырезаются.
     */
    fun decode(b: ByteArray): String {
        val s = when {
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, (b.size - 2) and 1.inv(), Charsets.UTF_16BE)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, (b.size - 2) and 1.inv(), Charsets.UTF_16LE)
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
            else -> buildString(b.size) { for (x in b) { val c = x.toInt() and 0xFF; append(PDFDOC_SPECIAL[c] ?: c.toChar()) } }
        }
        return if (s.indexOf('\u001B') >= 0) s.replace(Regex("\u001B[^\u001B]*\u001B"), "") else s
    }

    /** Кодирование текстовой строки: всегда UTF-16BE с BOM (§7.9.2.2) — годится для любого текста. */
    fun encodeUtf16(s: String): PdfString {
        val body = s.toByteArray(Charsets.UTF_16BE)
        val b = ByteArray(body.size + 2)
        b[0] = 0xFE.toByte(); b[1] = 0xFF.toByte()
        System.arraycopy(body, 0, b, 2, body.size)
        return PdfString(b)
    }

    fun ascii(s: String): PdfString = PdfString(s.toByteArray(Charsets.ISO_8859_1))
}

/** Запись объектов PDF в байты (синтаксис §7.3). */
object PdfSerializer {

    /** Вещественные числа: без экспоненты (§7.3.3 запрещает её), до 4 знаков после точки. */
    fun num(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
    }

    fun write(o: PdfObj, out: ByteArrayOutputStream) {
        when (o) {
            is PdfNull -> out.ascii("null")
            is PdfBool -> out.ascii(if (o.value) "true" else "false")
            is PdfNum -> out.ascii(if (o.isInt) o.value.toLong().toString() else num(o.value))
            is PdfName -> writeName(o.name, out)
            is PdfString -> if (o.hex) writeHex(o.bytes, out) else writeString(o.bytes, out)
            is PdfRef -> out.ascii("${o.num} ${o.gen} R")
            is PdfArray -> {
                out.write('['.code)
                o.items.forEachIndexed { i, it -> if (i > 0) out.write(' '.code); write(it, out) }
                out.write(']'.code)
            }
            is PdfDict -> writeDict(o, out)
            is PdfStream -> {
                val d = o.dict.copy(); d["Length"] = PdfNum(o.len)
                writeDict(d, out)
                out.ascii("\nstream\n")
                out.write(o.src, o.off, o.len)
                out.ascii("\nendstream")
            }
            is PdfKeyword -> out.ascii("null")
        }
    }

    private fun writeDict(d: PdfDict, out: ByteArrayOutputStream) {
        out.ascii("<<")
        for ((k, v) in d.map) { writeName(k, out); out.write(' '.code); write(v, out); out.write(' '.code) }
        out.ascii(">>")
    }

    /** §7.3.5: символы вне 0x21–0x7E, разделители и «#» записываются как #xx. */
    private fun writeName(n: String, out: ByteArrayOutputStream) {
        out.write('/'.code)
        for (ch in n) {
            val c = ch.code and 0xFF
            if (c < 0x21 || c > 0x7E || c == '#'.code || PdfParser.isDelim(c)) {
                out.write('#'.code); out.ascii(String.format(java.util.Locale.ROOT, "%02X", c))
            } else out.write(c)
        }
    }

    /** §7.3.4.3: шестнадцатеричная строка (сохраняем форму, например для /ID). */
    private fun writeHex(b: ByteArray, out: ByteArrayOutputStream) {
        out.write('<'.code)
        for (x in b) out.ascii(String.format(java.util.Locale.ROOT, "%02X", x.toInt() and 0xFF))
        out.write('>'.code)
    }

    /** §7.3.4.2: литеральная строка; ( ) \ экранируются, непечатные — \ddd. */
    private fun writeString(b: ByteArray, out: ByteArrayOutputStream) {
        out.write('('.code)
        for (x in b) {
            val c = x.toInt() and 0xFF
            when {
                c == '('.code || c == ')'.code || c == '\\'.code -> { out.write('\\'.code); out.write(c) }
                c < 0x20 || c > 0x7E -> { out.write('\\'.code); out.ascii(Integer.toOctalString(c).padStart(3, '0')) }
                else -> out.write(c)
            }
        }
        out.write(')'.code)
    }

    fun toBytes(o: PdfObj): ByteArray = ByteArrayOutputStream().also { write(o, it) }.toByteArray()
}

fun ByteArrayOutputStream.ascii(s: String) {
    for (ch in s) write(ch.code)
}
