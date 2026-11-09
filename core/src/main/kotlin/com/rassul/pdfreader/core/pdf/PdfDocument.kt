package com.rassul.pdfreader.core.pdf

/** Запись таблицы перекрёстных ссылок (§7.5.4, §7.5.8.3 таблица 18). */
sealed class XrefEntry {
    object Free : XrefEntry()
    data class InFile(val offset: Int, val gen: Int) : XrefEntry()
    data class Compressed(val stmNum: Int, val index: Int) : XrefEntry()
}

/** Страница с учётом наследуемых атрибутов (§7.7.3.4, таблица 30). */
class PageInfo(
    val index: Int,
    /** Ссылка на объект страницы; null — если страница записана прямым словарём (нарушение §7.7.3.3). */
    val ref: PdfRef?,
    val dict: PdfDict,
    val mediaBox: DoubleArray?,
    val cropBox: DoubleArray?,
    /** /Rotate, уже нормализованный к 0/90/180/270. */
    val rotate: Int,
    val resources: PdfObj?,
)

/**
 * Документ PDF, разобранный «лениво»: читаются только xref/trailer, объекты — по запросу.
 *
 * Загрузка (ISO 32000-1 §7.5.5 «File trailer», §7.5.6 «Incremental updates»):
 *  startxref → секция xref (таблица §7.5.4 или поток §7.5.8) → /Prev → … ;
 *  записи более новых секций побеждают более старые. Гибридные файлы (§7.5.8.4):
 *  записи потока из /XRefStm дополняют таблицу той же секции.
 * При любой ошибке — восстановление сканированием «N G obj» по всему файлу.
 */
class PdfDocument(val buf: ByteArray) {
    val entries = HashMap<Int, XrefEntry>()
    var trailer = PdfDict(); private set
    /** Смещение последней секции xref (значение startxref) — для /Prev обновления. */
    var lastXrefOffset = -1; private set
    var lastXrefIsStream = false; private set
    /** true — xref не читался, таблица восстановлена сканированием. */
    var repaired = false; private set

    private val cache = HashMap<Int, PdfObj>()
    private val objStmCache = HashMap<Int, Pair<ByteArray, IntArray>>() // num → (данные, [номер, смещение]*)
    private val resolving = HashSet<Int>()
    private var scanned: HashMap<Int, XrefEntry.InFile>? = null

    val isEncrypted: Boolean get() = trailer["Encrypt"].let { it != null && it != PdfNull }

    /** Следующий свободный номер объекта: max(/Size, max(номер)+1) (§7.5.5 /Size). */
    val size: Int get() = maxOf(trailer["Size"].asInt() ?: 0, (entries.keys.maxOrNull() ?: 0) + 1)

    init {
        try {
            loadXref()
            val root = resolve(trailer["Root"]).asDict()
            if (root == null || resolve(root["Pages"]).asDict() == null) throw PdfFormatException("нет /Root")
        } catch (e: Exception) {
            repair()
        }
    }

    // ================= xref =================

    private fun findStartxref(): Int? {
        val kw = "startxref".toByteArray(Charsets.ISO_8859_1)
        var i = lastIndexOf(kw, maxOf(0, buf.size - 2048))
        if (i < 0) i = lastIndexOf(kw, 0)
        if (i < 0) return null
        val p = PdfParser(buf, i + kw.size)
        return p.readUInt()?.toInt()
    }

    private fun lastIndexOf(pat: ByteArray, from: Int): Int {
        var i = buf.size - pat.size
        outer@ while (i >= from) {
            for (k in pat.indices) if (buf[i + k] != pat[k]) { i--; continue@outer }
            return i
        }
        return -1
    }

    private class Section(val entries: Map<Int, XrefEntry>, val trailer: PdfDict, val isStream: Boolean, val prev: Int?)

    private fun loadXref() {
        val start = findStartxref() ?: throw PdfFormatException("нет startxref")
        if (start <= 0 || start >= buf.size) throw PdfFormatException("startxref вне файла")
        lastXrefOffset = start
        val visited = HashSet<Int>()
        var off: Int? = start
        var first = true
        while (off != null && off >= 0 && off < buf.size && visited.add(off)) {
            val sec = readSection(off)
            if (first) {
                lastXrefIsStream = sec.isStream
                trailer = sec.trailer.copy()
                first = false
            } else {
                for ((k, v) in sec.trailer.map) if (k !in trailer.map) trailer[k] = v
                val s = sec.trailer["Size"].asInt() ?: 0
                if (s > (trailer["Size"].asInt() ?: 0)) trailer["Size"] = PdfNum(s)
            }
            for ((k, v) in sec.entries) entries.putIfAbsent(k, v)
            off = sec.prev
        }
        trailer.map.remove("Prev"); trailer.map.remove("XRefStm")
    }

    private fun readSection(off: Int): Section {
        val p = PdfParser(buf, off)
        p.skipWs()
        if (p.matchKeyword(p.pos, "xref")) {
            p.pos += 4
            val table = readXrefTable(p)
            p.skipWs()
            if (!p.matchKeyword(p.pos, "trailer")) throw PdfFormatException("нет trailer")
            p.pos += 7
            val tr = p.parseObject().asDict() ?: throw PdfFormatException("битый trailer")
            // §7.5.8.4: гибридный файл — записи из /XRefStm дополняют таблицу этой секции
            // (объекты из объектных потоков в таблице отсутствуют или помечены свободными).
            tr["XRefStm"].asInt()?.let { xs ->
                try {
                    val s = readXrefStreamAt(xs)
                    for ((k, v) in s.entries) { val cur = table[k]; if (cur == null || cur is XrefEntry.Free) table[k] = v }
                } catch (e: PdfFormatException) { /* терпим: таблица всё равно есть */ }
            }
            return Section(table, tr, false, tr["Prev"].asInt())
        }
        return readXrefStreamAt(off)
    }

    /** §7.5.4: подсекции «start count», затем записи «oooooooooo ggggg n|f». */
    private fun readXrefTable(p: PdfParser): HashMap<Int, XrefEntry> {
        val table = HashMap<Int, XrefEntry>()
        var firstSub = true
        while (true) {
            p.skipWs()
            if (p.matchKeyword(p.pos, "trailer") || p.atEnd) break
            var startNum = p.readUInt()?.toInt() ?: throw PdfFormatException("битая таблица xref")
            val count = p.readUInt()?.toInt() ?: throw PdfFormatException("битая таблица xref")
            if (count < 0 || count > 10_000_000) throw PdfFormatException("битая таблица xref")
            for (i in 0 until count) {
                val o = p.readUInt() ?: throw PdfFormatException("битая запись xref")
                val g = p.readUInt() ?: throw PdfFormatException("битая запись xref")
                p.skipWs()
                val type = p.readWord()
                // Частая ошибка генераторов: первая подсекция «1 N» начинается с записи 0 (free 65535).
                if (firstSub && i == 0 && startNum == 1 && type == "f" && o == 0L && g == 65535L) startNum = 0
                val num = startNum + i
                when (type) {
                    "n" -> if (o > 0) table.putIfAbsent(num, XrefEntry.InFile(o.toInt(), g.toInt())) else table.putIfAbsent(num, XrefEntry.Free)
                    "f" -> table.putIfAbsent(num, XrefEntry.Free)
                    else -> throw PdfFormatException("битая запись xref")
                }
            }
            firstSub = false
        }
        return table
    }

    /** §7.5.8: поток перекрёстных ссылок, /W, /Index, /Size, /Prev. */
    private fun readXrefStreamAt(off: Int): Section {
        val p = PdfParser(buf, off, lengthResolver = ::resolveLength)
        val (_, _, obj) = p.parseIndirect() ?: throw PdfFormatException("нет xref по смещению")
        val st = obj as? PdfStream ?: throw PdfFormatException("xref не поток")
        val d = st.dict
        if (d["Type"].asName() != "XRef") throw PdfFormatException("xref не /XRef")
        val w = d["W"].asArray()?.items?.map { it.asInt() ?: 0 } ?: throw PdfFormatException("нет /W")
        if (w.size < 3 || w.any { it < 0 || it > 8 }) throw PdfFormatException("битый /W")
        val size = d["Size"].asInt() ?: 0
        val index = d["Index"].asArray()?.items?.map { it.asInt() ?: 0 } ?: listOf(0, size)
        val data = PdfFilters.decode(st)
        val rowLen = w[0] + w[1] + w[2]
        val table = HashMap<Int, XrefEntry>()
        var pos = 0
        var k = 0
        while (k + 1 < index.size) {
            val startNum = index[k]; val count = index[k + 1]; k += 2
            for (i in 0 until count) {
                if (pos + rowLen > data.size) break
                // §7.5.8.2: при W[0]=0 тип по умолчанию 1.
                val t = if (w[0] == 0) 1L else field(data, pos, w[0])
                val f2 = field(data, pos + w[0], w[1])
                val f3 = field(data, pos + w[0] + w[1], w[2])
                pos += rowLen
                val num = startNum + i
                val e = when (t) {
                    0L -> XrefEntry.Free
                    1L -> if (f2 > 0) XrefEntry.InFile(f2.toInt(), f3.toInt()) else XrefEntry.Free
                    2L -> XrefEntry.Compressed(f2.toInt(), f3.toInt())
                    else -> continue // §7.5.8.3: неизвестные типы — как ссылка на null
                }
                table.putIfAbsent(num, e)
            }
        }
        val tr = PdfDict()
        for ((key, v) in d.map) if (key !in setOf("Type", "W", "Index", "Filter", "DecodeParms", "Length", "DL")) tr[key] = v
        return Section(table, tr, true, d["Prev"].asInt())
    }

    private fun field(data: ByteArray, off: Int, w: Int): Long {
        var v = 0L
        for (i in 0 until w) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
        return v
    }

    // ================= восстановление =================

    /** Все «N G obj» в файле: последнее вхождение номера побеждает (как в инкрементальных обновлениях). */
    private fun scanObjects(): HashMap<Int, XrefEntry.InFile> {
        scanned?.let { return it }
        val res = HashMap<Int, XrefEntry.InFile>()
        val n = buf.size
        var i = 0
        while (i + 3 <= n) {
            if (buf[i] == 'o'.code.toByte() && buf[i + 1] == 'b'.code.toByte() && buf[i + 2] == 'j'.code.toByte() &&
                (i + 3 == n || !PdfParser.isRegular(buf[i + 3].toInt() and 0xFF)) && i > 0 && PdfParser.isWhite(buf[i - 1].toInt() and 0xFF)
            ) {
                // назад: пробелы, gen, пробелы, num
                var j = i - 1
                while (j >= 0 && PdfParser.isWhite(buf[j].toInt() and 0xFF)) j--
                val genEnd = j + 1
                while (j >= 0 && PdfParser.isDigit(buf[j].toInt())) j--
                val genStart = j + 1
                if (genStart < genEnd && j >= 0 && PdfParser.isWhite(buf[j].toInt() and 0xFF)) {
                    while (j >= 0 && PdfParser.isWhite(buf[j].toInt() and 0xFF)) j--
                    val numEnd = j + 1
                    while (j >= 0 && PdfParser.isDigit(buf[j].toInt())) j--
                    val numStart = j + 1
                    if (numStart < numEnd && numEnd - numStart <= 10 && genEnd - genStart <= 5 &&
                        (j < 0 || !PdfParser.isRegular(buf[j].toInt() and 0xFF))
                    ) {
                        val num = String(buf, numStart, numEnd - numStart, Charsets.ISO_8859_1).toLong()
                        val gen = String(buf, genStart, genEnd - genStart, Charsets.ISO_8859_1).toInt()
                        if (num in 1..Int.MAX_VALUE.toLong()) res[num.toInt()] = XrefEntry.InFile(numStart, gen)
                    }
                }
                i += 3
            } else i++
        }
        scanned = res
        return res
    }

    private fun repair() {
        repaired = true
        entries.clear(); cache.clear(); objStmCache.clear()
        lastXrefOffset = -1; lastXrefIsStream = false
        val found = scanObjects()
        entries.putAll(found)
        // Объекты из объектных потоков (не перекрывают найденные в файле).
        var catalog: PdfRef? = null
        var xrefTrailer: PdfDict? = null
        for (num in found.keys.sorted()) {
            val o = try { getObject(num) } catch (e: Exception) { continue }
            val d = o.asDict() ?: continue
            when (d["Type"].asName()) {
                "Catalog" -> catalog = PdfRef(num, found[num]!!.gen)
                "XRef" -> xrefTrailer = d
                "ObjStm" -> try {
                    val (_, hdr) = objStm(num)
                    var k = 0
                    while (k + 1 < hdr.size) { entries.putIfAbsent(hdr[k], XrefEntry.Compressed(num, k / 2)); k += 2 }
                } catch (e: Exception) { }
            }
        }
        // Последний «trailer», чей /Root разрешается в словарь.
        var tr: PdfDict? = null
        val kw = "trailer".toByteArray(Charsets.ISO_8859_1)
        val p = PdfParser(buf)
        var at = p.indexOf(kw, 0)
        while (at >= 0) {
            try {
                p.pos = at + kw.size
                val d = p.parseObject().asDict()
                if (d != null && resolve(d["Root"]).asDict() != null) tr = d
            } catch (e: Exception) { }
            at = p.indexOf(kw, at + kw.size)
        }
        if (tr == null && xrefTrailer != null && resolve(xrefTrailer["Root"]).asDict() != null) tr = xrefTrailer
        val t = tr?.copy() ?: PdfDict()
        if (resolve(t["Root"]).asDict() == null) {
            if (catalog == null) throw PdfFormatException("Файл повреждён или не является PDF")
            t["Root"] = catalog
        }
        for (k in listOf("Prev", "XRefStm", "Type", "W", "Index", "Filter", "DecodeParms", "Length")) t.map.remove(k)
        t["Size"] = PdfNum((entries.keys.maxOrNull() ?: 0) + 1)
        trailer = t
    }

    // ================= объекты =================

    private fun resolveLength(o: PdfObj): Int? = (resolve(o) as? PdfNum)?.int

    fun resolve(o: PdfObj?): PdfObj? {
        var cur = o
        var n = 0
        while (cur is PdfRef && n++ < 16) cur = getObject(cur.num)
        return if (cur is PdfRef) PdfNull else cur
    }

    /** Объект по номеру; отсутствующий/свободный — null (§7.3.10: «reference to a non-existent object … null»). */
    fun getObject(num: Int): PdfObj {
        cache[num]?.let { return it }
        if (!resolving.add(num)) return PdfNull // цикл (/Length сам на себя и т.п.)
        try {
            val o = when (val e = entries[num]) {
                is XrefEntry.InFile -> readInFile(num, e.offset) ?: scanObjects()[num]?.let { readInFile(num, it.offset) } ?: PdfNull
                is XrefEntry.Compressed -> readCompressed(num, e)
                else -> PdfNull
            }
            cache[num] = o
            return o
        } finally { resolving.remove(num) }
    }

    private fun readInFile(num: Int, offset: Int): PdfObj? {
        if (offset < 0 || offset >= buf.size) return null
        return try {
            val p = PdfParser(buf, offset, lengthResolver = ::resolveLength)
            val r = p.parseIndirect() ?: return null
            if (r.first != num) null else r.third
        } catch (e: PdfFormatException) { null }
    }

    /** §7.5.7: объектный поток, /N пар «номер смещение», затем объекты с /First. */
    private fun objStm(stmNum: Int): Pair<ByteArray, IntArray> {
        objStmCache[stmNum]?.let { return it }
        val st = getObject(stmNum) as? PdfStream ?: throw PdfFormatException("нет объектного потока $stmNum")
        val n = resolve(st.dict["N"]).asInt() ?: 0
        val first = resolve(st.dict["First"]).asInt() ?: 0
        val data = PdfFilters.decode(st, ::resolve)
        val p = PdfParser(data, 0, minOf(first, data.size))
        val hdr = ArrayList<Int>()
        for (i in 0 until n) {
            val on = p.readUInt() ?: break
            val off = p.readUInt() ?: break
            hdr.add(on.toInt()); hdr.add(first + off.toInt())
        }
        val r = data to hdr.toIntArray()
        objStmCache[stmNum] = r
        return r
    }

    private fun readCompressed(num: Int, e: XrefEntry.Compressed): PdfObj = try {
        val (data, hdr) = objStm(e.stmNum)
        var at = -1
        if (e.index * 2 + 1 < hdr.size && hdr[e.index * 2] == num) at = hdr[e.index * 2 + 1]
        else { var k = 0; while (k + 1 < hdr.size) { if (hdr[k] == num) { at = hdr[k + 1]; break }; k += 2 } }
        if (at < 0 || at >= data.size) PdfNull
        else PdfParser(data, at).parseObject().let { if (it is PdfKeyword) PdfNull else it }
    } catch (ex: PdfFormatException) { PdfNull }

    fun refGen(num: Int): Int = when (val e = entries[num]) {
        is XrefEntry.InFile -> e.gen
        else -> 0
    }

    // ================= страницы =================

    val catalog: PdfDict? get() = resolve(trailer["Root"]).asDict()

    private var pagesCache: List<PageInfo>? = null

    /** Обход дерева страниц /Pages → /Kids (§7.7.3) с наследованием атрибутов (§7.7.3.4). */
    fun pages(): List<PageInfo> {
        pagesCache?.let { return it }
        val out = ArrayList<PageInfo>()
        val visited = HashSet<Int>()
        class Inh(val media: DoubleArray?, val crop: DoubleArray?, val rotate: PdfObj?, val res: PdfObj?)
        fun walk(nodeObj: PdfObj?, inh: Inh, depth: Int) {
            if (depth > 64) return
            val ref = nodeObj as? PdfRef
            if (ref != null && !visited.add(ref.num)) return
            val node = resolve(nodeObj).asDict() ?: return
            val here = Inh(
                box(node["MediaBox"]) ?: inh.media,
                box(node["CropBox"]) ?: inh.crop,
                resolve(node["Rotate"])?.takeIf { it is PdfNum } ?: inh.rotate,
                node["Resources"] ?: inh.res,
            )
            val kids = resolve(node["Kids"]).asArray()
            val type = resolve(node["Type"]).asName()
            if (type == "Pages" || (type != "Page" && kids != null)) {
                kids?.items?.forEach { walk(it, here, depth + 1) }
            } else {
                out.add(PageInfo(out.size, ref?.let { PdfRef(it.num, it.gen) }, node, here.media, here.crop, normRotate(here.rotate.asInt() ?: 0), here.res))
            }
        }
        walk(catalog?.get("Pages"), Inh(null, null, null, null), 0)
        pagesCache = out
        return out
    }

    fun box(o: PdfObj?): DoubleArray? {
        val a = resolve(o).asArray() ?: return null
        if (a.size < 4) return null
        val v = DoubleArray(4) { resolve(a[it]).asNum() ?: return null }
        // §7.9.5: прямоугольник может быть задан любыми противоположными углами — нормализуем.
        return doubleArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    companion object {
        /**
         * §7.7.3.3 /Rotate: «shall be a multiple of 90». Как PDFium: rot = trunc(R)/90 по модулю 4,
         * отрицательные +4 (−90→270, 450→90, 45→0, 135→90).
         */
        fun normRotate(r: Int): Int {
            var q = (r / 90) % 4
            if (q < 0) q += 4
            return q * 90
        }
    }
}
