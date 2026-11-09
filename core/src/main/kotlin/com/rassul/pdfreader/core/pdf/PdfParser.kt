package com.rassul.pdfreader.core.pdf

/**
 * Лексер/парсер объектов PDF (ISO 32000-1 §7.2 «Lexical conventions», §7.3 «Objects»)
 * поверх массива байтов [buf] без копирования. Позиция — [pos].
 *
 * [lengthResolver] раскрывает косвенный /Length потока (§7.3.8.2: «Length … may be an
 * indirect reference»); если он вернул null или длина не сходится с «endstream», длина
 * ищется сканированием до ключевого слова endstream.
 */
class PdfParser(
    val buf: ByteArray,
    var pos: Int = 0,
    private val end: Int = buf.size,
    private val lengthResolver: ((PdfObj) -> Int?)? = null,
) {
    private var depth = 0

    val atEnd: Boolean get() = pos >= end

    // ---------- классы символов (§7.2.2, таблицы 1 и 2) ----------
    companion object {
        fun isWhite(b: Int) = b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32
        fun isDelim(b: Int) = b == '('.code || b == ')'.code || b == '<'.code || b == '>'.code ||
            b == '['.code || b == ']'.code || b == '{'.code || b == '}'.code || b == '/'.code || b == '%'.code
        fun isRegular(b: Int) = !isWhite(b) && !isDelim(b)
        fun isDigit(b: Int) = b in '0'.code..'9'.code
        private const val MAX_DEPTH = 200

        private fun hexVal(b: Int): Int = when (b) {
            in '0'.code..'9'.code -> b - '0'.code
            in 'a'.code..'f'.code -> b - 'a'.code + 10
            in 'A'.code..'F'.code -> b - 'A'.code + 10
            else -> -1
        }
    }

    private fun peek(off: Int = 0): Int = if (pos + off < end) buf[pos + off].toInt() and 0xFF else -1

    /** Пропуск пробельных символов и комментариев (§7.2.3). */
    fun skipWs() {
        while (pos < end) {
            val b = buf[pos].toInt() and 0xFF
            if (isWhite(b)) pos++
            else if (b == '%'.code) {
                while (pos < end && buf[pos].toInt() != 10 && buf[pos].toInt() != 13) pos++
            } else break
        }
    }

    /** Совпадают ли байты в позиции [at] со словом [w] (и за ним — разделитель/конец). */
    fun matchKeyword(at: Int, w: String): Boolean {
        if (at + w.length > end) return false
        for (i in w.indices) if (buf[at + i].toInt() != w[i].code) return false
        val after = at + w.length
        return after >= end || !isRegular(buf[after].toInt() and 0xFF)
    }

    /** Читает «обычное» слово (последовательность regular-символов). */
    fun readWord(): String {
        val s = pos
        while (pos < end && isRegular(buf[pos].toInt() and 0xFF)) pos++
        return String(buf, s, pos - s, Charsets.ISO_8859_1)
    }

    /** Читает неотрицательное целое или null (позиция не меняется при неудаче). */
    fun readUInt(): Long? {
        skipWs()
        val s = pos
        var v = 0L
        while (pos < end && isDigit(buf[pos].toInt())) { v = v * 10 + (buf[pos] - '0'.code.toByte()); pos++; if (v > 1L shl 40) break }
        if (pos == s || (pos < end && isRegular(buf[pos].toInt() and 0xFF))) { pos = s; return null }
        return v
    }

    fun parseObject(): PdfObj {
        skipWs()
        if (pos >= end) throw PdfFormatException("Неожиданный конец данных PDF")
        if (++depth > MAX_DEPTH) { depth--; throw PdfFormatException("Слишком глубокая вложенность объектов PDF") }
        try {
            val b = peek()
            return when {
                b == '/'.code -> { pos++; parseName() }
                b == '('.code -> { pos++; parseLiteralString() }
                b == '<'.code && peek(1) == '<'.code -> { pos += 2; parseDictOrStream() }
                b == '<'.code -> { pos++; parseHexString() }
                b == '['.code -> { pos++; parseArray() }
                b == '+'.code || b == '-'.code || b == '.'.code || isDigit(b) -> parseNumberOrRef()
                isRegular(b) -> when (val w = readWord()) {
                    "true" -> PdfBool(true)
                    "false" -> PdfBool(false)
                    "null" -> PdfNull
                    else -> PdfKeyword(w)
                }
                else -> { pos++; PdfKeyword(String(charArrayOf(b.toChar()))) } // ] >> ) { } — вызывающий решает
            }
        } finally { depth-- }
    }

    /** §7.3.5: имя, #xx — шестнадцатеричный код байта. */
    private fun parseName(): PdfName {
        val sb = StringBuilder()
        while (pos < end) {
            val b = buf[pos].toInt() and 0xFF
            if (!isRegular(b)) break
            if (b == '#'.code && pos + 2 < end + 0 && hexVal(peek(1)) >= 0 && hexVal(peek(2)) >= 0) {
                sb.append(((hexVal(peek(1)) shl 4) or hexVal(peek(2))).toChar()); pos += 3
            } else { sb.append(b.toChar()); pos++ }
        }
        return PdfName(sb.toString())
    }

    /**
     * §7.3.4.2: литеральная строка. Сбалансированные вложенные скобки, экранирование
     * \n \r \t \b \f \( \) \\ \ddd, «\» + конец строки = перенос (игнорируется),
     * неэкранированный конец строки (CR, CRLF, LF) читается как LF.
     */
    private fun parseLiteralString(): PdfString {
        val out = java.io.ByteArrayOutputStream()
        var nest = 1
        while (pos < end) {
            var b = buf[pos++].toInt() and 0xFF
            when (b) {
                '('.code -> { nest++; out.write(b) }
                ')'.code -> { if (--nest == 0) return PdfString(out.toByteArray()); out.write(b) }
                '\\'.code -> {
                    if (pos >= end) break
                    b = buf[pos++].toInt() and 0xFF
                    when (b) {
                        'n'.code -> out.write(10); 'r'.code -> out.write(13); 't'.code -> out.write(9)
                        'b'.code -> out.write(8); 'f'.code -> out.write(12)
                        13 -> { if (peek() == 10) pos++ }
                        10 -> {}
                        in '0'.code..'7'.code -> {
                            var v = b - '0'.code
                            for (k in 0 until 2) { val c = peek(); if (c in '0'.code..'7'.code) { v = v * 8 + (c - '0'.code); pos++ } else break }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(b) // \( \) \\ и неизвестные — сам символ
                    }
                }
                13 -> { if (peek() == 10) pos++; out.write(10) }
                else -> out.write(b)
            }
        }
        return PdfString(out.toByteArray()) // незакрытая строка — терпим
    }

    /** §7.3.4.3: шестнадцатеричная строка; пробелы игнорируются, нечётная последняя цифра дополняется 0. */
    private fun parseHexString(): PdfString {
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (pos < end) {
            val b = buf[pos++].toInt() and 0xFF
            if (b == '>'.code) break
            val v = hexVal(b)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return PdfString(out.toByteArray(), hex = true)
    }

    private fun parseArray(): PdfArray {
        val items = ArrayList<PdfObj>()
        while (true) {
            skipWs()
            if (pos >= end) return PdfArray(items)
            if (peek() == ']'.code) { pos++; return PdfArray(items) }
            val o = parseObject()
            if (o is PdfKeyword) {
                // мусор внутри массива: «>>», «endobj» — массив оборван
                if (o.word == "endobj" || o.word == ">" || o.word == "stream") { pos -= o.word.length; return PdfArray(items) }
                continue
            }
            items.add(o)
        }
    }

    private fun parseDictOrStream(): PdfObj {
        val map = LinkedHashMap<String, PdfObj>()
        while (true) {
            skipWs()
            if (pos >= end) break
            if (peek() == '>'.code && peek(1) == '>'.code) { pos += 2; break }
            if (peek() != '/'.code) {
                val o = parseObject()
                if (o is PdfKeyword && (o.word == "endobj" || o.word == "stream")) { pos -= o.word.length; break }
                continue // мусор вместо ключа — пропускаем
            }
            pos++
            val key = parseName().name
            skipWs()
            if (peek() == '>'.code && peek(1) == '>'.code) { map[key] = PdfNull; continue }
            val v = parseObject()
            if (v is PdfKeyword) { if (v.word == "endobj") { pos -= 6; break }; continue }
            map[key] = v
        }
        val dict = PdfDict(map)
        // §7.3.8.1: за словарём потока следует «stream» + CRLF или LF.
        val save = pos
        skipWs()
        if (matchKeyword(pos, "stream")) {
            pos += 6
            if (peek() == 13) pos++
            if (peek() == 10) pos++
            return readStreamData(dict)
        }
        pos = save
        return dict
    }

    private fun readStreamData(dict: PdfDict): PdfStream {
        val start = pos
        val lenObj = dict["Length"]
        var len: Int? = when (lenObj) {
            is PdfNum -> lenObj.int
            null -> null
            else -> try { lengthResolver?.invoke(lenObj) } catch (e: PdfFormatException) { null }
        }
        if (len != null && (len < 0 || start + len > end || !endstreamAt(start + len))) len = null
        if (len == null) {
            val e = indexOf("endstream".toByteArray(Charsets.ISO_8859_1), start)
            var stop = if (e < 0) end else e
            // Убираем EOL перед endstream (§7.3.8.1: «There should be an end-of-line marker…»).
            if (stop > start && buf[stop - 1].toInt() == 10) stop--
            if (stop > start && buf[stop - 1].toInt() == 13) stop--
            len = stop - start
        }
        pos = start + len
        skipWs()
        if (matchKeyword(pos, "endstream")) pos += 9
        return PdfStream(dict, buf, start, len)
    }

    private fun endstreamAt(p: Int): Boolean {
        var q = p
        while (q < end && isWhite(buf[q].toInt() and 0xFF)) q++
        return matchKeyword(q, "endstream")
    }

    fun indexOf(pattern: ByteArray, from: Int): Int {
        val first = pattern[0]
        var i = from
        val last = end - pattern.size
        outer@ while (i <= last) {
            if (buf[i] != first) { i++; continue }
            for (k in 1 until pattern.size) if (buf[i + k] != pattern[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    /** §7.3.3 числа; «n g R» (§7.3.10) распознаётся заглядыванием вперёд. */
    private fun parseNumberOrRef(): PdfObj {
        val s = pos
        if (peek() == '+'.code || peek() == '-'.code) pos++
        // «--5» и подобные ошибки встречаются в реальных файлах — пропускаем лишние знаки
        while (peek() == '-'.code || peek() == '+'.code) pos++
        var sawDot = false
        while (pos < end) {
            val b = buf[pos].toInt() and 0xFF
            if (isDigit(b)) pos++ else if (b == '.'.code && !sawDot) { sawDot = true; pos++ } else break
        }
        // мусор вида «12abc» — отбрасываем хвост
        while (pos < end && isRegular(buf[pos].toInt() and 0xFF) && !isDigit(buf[pos].toInt())) pos++
        val txt = String(buf, s, pos - s, Charsets.ISO_8859_1).filter { isDigit(it.code) || it == '.' || it == '-' }
        val neg = txt.startsWith("-")
        val digits = txt.trimStart('-')
        val v = digits.toDoubleOrNull()?.let { if (neg) -it else it } ?: 0.0
        val isInt = !sawDot
        if (isInt && !neg && buf[s].toInt() != '+'.code) {
            val save = pos
            val gen = readUInt()
            if (gen != null) {
                skipWs()
                if (matchKeyword(pos, "R")) { pos++; return PdfRef(v.toInt(), gen.toInt()) }
            }
            pos = save
        }
        return PdfNum(v, isInt)
    }

    /**
     * Косвенный объект «num gen obj … endobj» (§7.3.10) в позиции [pos].
     * Возвращает (num, gen, объект) или null, если заголовка нет.
     */
    fun parseIndirect(): Triple<Int, Int, PdfObj>? {
        val num = readUInt() ?: return null
        val gen = readUInt() ?: return null
        skipWs()
        if (!matchKeyword(pos, "obj")) return null
        pos += 3
        skipWs()
        // Пустой объект «n g obj endobj» = null.
        if (matchKeyword(pos, "endobj")) { pos += 6; return Triple(num.toInt(), gen.toInt(), PdfNull) }
        var o = parseObject()
        if (o is PdfKeyword) o = PdfNull
        skipWs()
        if (matchKeyword(pos, "endobj")) pos += 6
        return Triple(num.toInt(), gen.toInt(), o)
    }
}
