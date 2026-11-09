package com.rassul.pdfreader.core.pdf

import com.rassul.pdfreader.core.annot.Annotation
import com.rassul.pdfreader.core.annot.Argb
import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Инкрементальное обновление (ISO 32000-1 §7.5.6): к исходным байтам дописываются
 * новые объекты аннотаций, новые версии изменённых объектов (страница или массив /Annots)
 * и новая секция xref с /Prev на предыдущую. Исходные байты не меняются.
 */
internal object AnnotationWriterImpl {

    /** Автор пометок (/T, §12.5.6.2 таблица 170). */
    private const val AUTHOR = "Я"
    private const val DEFAULT_HIGHLIGHT_CA = 0.35

    fun append(pdf: ByteArray, annotations: List<Annotation>, now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): ByteArray {
        if (annotations.isEmpty()) return pdf
        val doc = openDocument(pdf)
        if (doc.isEncrypted) throw PdfFormatException("Файл зашифрован; пометки в него сохранить нельзя")
        try {
            return Writer(doc, pdf, now).run(annotations)
        } catch (e: PdfFormatException) {
            throw e
        } catch (e: RuntimeException) {
            throw PdfFormatException("Не удалось добавить пометки: структура PDF не поддерживается", e)
        }
    }

    private class Writer(val doc: PdfDocument, val pdf: ByteArray, now: ZonedDateTime) {
        /** Номер → (поколение, объект) — что допишем в обновление. */
        val newObjs = LinkedHashMap<Int, Pair<Int, PdfObj>>()
        var next = doc.size
        fun alloc(): Int = next++

        /** §7.9.4: дата «D:YYYYMMDDHHmmSS+HH'mm'». */
        val date: PdfString = PdfText.ascii("D:" + now.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", java.util.Locale.ROOT)) + "+00'00'")

        fun run(annotations: List<Annotation>): ByteArray {
            val pages = doc.pages()
            for (a in annotations) {
                if (a.pageIndex !in pages.indices) {
                    throw PdfFormatException("Страница ${a.pageIndex + 1} отсутствует в документе (всего страниц: ${pages.size})")
                }
            }
            for ((pi, list) in annotations.groupBy { it.pageIndex }) {
                val page = pages[pi]
                val pageRef = page.ref ?: throw PdfFormatException("Страница ${pi + 1} записана нестандартно; пометки на неё добавить нельзя")
                val geo = PageGeometry.of(page)
                val refs = list.mapNotNull { build(it, geo, pageRef) }
                if (refs.isEmpty()) continue
                attach(page, pageRef, refs)
            }
            if (newObjs.isEmpty()) return pdf
            return serialize()
        }

        /**
         * §12.5.1 таблица 30 /Annots — «массив косвенных ссылок на аннотации». Если /Annots —
         * ссылка на массив, переписываем сам объект-массив (страница не меняется), иначе —
         * новая версия объекта страницы с дополненным массивом.
         */
        fun attach(page: PageInfo, pageRef: PdfRef, refs: List<PdfRef>) {
            val raw = (newObjs[pageRef.num]?.second as? PdfDict ?: page.dict)["Annots"]
            if (raw is PdfRef) {
                val pending = newObjs[raw.num]?.second as? PdfArray
                val target = pending ?: doc.resolve(raw) as? PdfArray
                if (target != null) {
                    val arr = pending ?: PdfArray(ArrayList(target.items))
                    arr.items.addAll(refs)
                    newObjs[raw.num] = doc.refGen(raw.num) to arr
                    return
                }
            }
            val dict = (newObjs[pageRef.num]?.second as? PdfDict) ?: page.dict.copy()
            val items = ArrayList<PdfObj>()
            (doc.resolve(raw) as? PdfArray)?.let { items.addAll(it.items) }
            items.addAll(refs)
            dict["Annots"] = PdfArray(items)
            newObjs[pageRef.num] = pageRef.gen to dict
        }

        fun add(o: PdfObj): PdfRef { val n = alloc(); newObjs[n] = 0 to o; return PdfRef(n, 0) }

        // ---------------- аннотации ----------------

        fun build(a: Annotation, g: PageGeometry, page: PdfRef): PdfRef? = when (a) {
            is InkAnnotation -> ink(a, g, page)
            is HighlightAnnotation -> highlight(a, g, page)
            is NoteAnnotation -> note(a, g, page)
        }

        /** Общие ключи (§12.5.2 таблица 164, §12.5.6.2 таблица 170). */
        fun base(subtype: String, rect: DoubleArray, page: PdfRef, color: Argb, ca: Double, ap: PdfRef): PdfDict = pdfDictOf(
            "Type" to PdfName("Annot"),
            "Subtype" to PdfName(subtype),
            "Rect" to pdfNums(*rect),
            "P" to page,
            "F" to PdfNum(4), // бит 3 Print (§12.5.3 таблица 165)
            "C" to rgb(color),
            "CA" to PdfNum(ca, false),
            "M" to date,
            "CreationDate" to date,
            "T" to PdfText.encodeUtf16(AUTHOR),
            "NM" to PdfText.ascii("pdfreader-" + UUID.randomUUID()),
            "AP" to pdfDictOf("N" to ap), // §12.5.5: внешний вид обязателен для видимости во всех ридерах
        )

        /** /Ink (§12.5.6.13 таблица 182): /InkList в пространстве PDF, /BS /W — толщина (§12.5.4 таблица 166). */
        fun ink(a: InkAnnotation, g: PageGeometry, page: PdfRef): PdfRef? {
            val strokes = a.strokes.filter { it.isNotEmpty() }.map { s ->
                DoubleArray(s.size * 2).also { arr -> s.forEachIndexed { i, p -> val u = g.toPdf(p.x.toDouble(), p.y.toDouble()); arr[2 * i] = u[0]; arr[2 * i + 1] = u[1] } }
            }
            if (strokes.isEmpty()) return null
            val w = a.widthPt.toDouble().coerceAtLeast(0.1)
            val b = a.bounds
            // /Rect = оболочка точек ± w/2 (InkAnnotation.bounds), округлённая наружу.
            val rect = outward(g.rectToPdf(b.left.toDouble(), b.top.toDouble(), b.right.toDouble(), b.bottom.toDouble()))
            val ca = (a.opacity.toDouble() * alpha(a.color)).coerceIn(0.0, 1.0)
            // Внешний вид рисуем прямо в пространстве PDF (уже преобразованные точки; /BBox = /Rect,
            // /Matrix — единичная): у линий нет «верха», так что поворот страницы ничего не портит,
            // и результат не зависит от поддержки /Matrix ридером. §8.5.3.2: 1 J — круглые концы, 1 j — круглые стыки.
            val c = StringBuilder()
            c.append("q\n")
            if (ca < 1.0) c.append("/GS0 gs\n")
            c.append(rgbOp(a.color, "RG")).append(n(w)).append(" w 1 J 1 j\n")
            for (s in strokes) {
                c.append(n(s[0])).append(' ').append(n(s[1])).append(" m\n")
                if (s.size == 2) c.append(n(s[0])).append(' ').append(n(s[1])).append(" l\n") // точка
                var i = 2
                while (i < s.size) { c.append(n(s[i])).append(' ').append(n(s[i + 1])).append(" l\n"); i += 2 }
                c.append("S\n")
            }
            c.append("Q\n")
            val ap = add(form(rect, null, c, gs(ca, null)))
            val d = base("Ink", rect, page, a.color, ca, ap)
            d["InkList"] = PdfArray(strokes.mapTo(ArrayList<PdfObj>()) { pdfNums(*it) })
            d["BS"] = pdfDictOf("Type" to PdfName("Border"), "W" to PdfNum(w, false), "S" to PdfName("S"))
            return add(d)
        }

        /**
         * /Highlight (§12.5.6.10 таблица 179). /QuadPoints: на каждую строку 8 чисел.
         * ISO 32000-1 пишет «против часовой стрелки», но фактически Acrobat и все ридеры
         * используют порядок «Z»: верх-лево, верх-право, низ-лево, низ-право — см. PDFium
         * core/fpdfdoc/cpdf_annot.cpp RectFromQuadPointsArray (p0=TL, p1=TR, p2=BL, p3=BR) и
         * pdf.js src/core/annotation.js getQuadPoints(). Пишем «Z» по осям ПРОСТРАНСТВА PDF:
         * [xmin ymax xmax ymax xmin ymin xmax ymin] — так их понимает PDFium (Android) при любом /Rotate.
         * /Rect обязан содержать все QuadPoints (иначе pdf.js их отбрасывает), поэтому точки сначала
         * округляем как при записи, а /Rect — наружу (floor/ceil).
         */
        fun highlight(a: HighlightAnnotation, g: PageGeometry, page: PdfRef): PdfRef? {
            if (a.rects.isEmpty()) return null
            val quads = ArrayList<DoubleArray>()
            for (r in a.rects) {
                val u = g.rectToPdf(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble()).map { rnd(it) }
                val (x0, y0, x1, y1) = u
                quads.add(doubleArrayOf(x0, y1, x1, y1, x0, y0, x1, y0))
            }
            var llx = Double.MAX_VALUE; var lly = Double.MAX_VALUE; var urx = -Double.MAX_VALUE; var ury = -Double.MAX_VALUE
            for (q in quads) for (i in 0 until 4) { llx = minOf(llx, q[2 * i]); urx = maxOf(urx, q[2 * i]); lly = minOf(lly, q[2 * i + 1]); ury = maxOf(ury, q[2 * i + 1]) }
            val rect = outward(doubleArrayOf(llx, lly, urx, ury))
            // Прозрачность маркера: альфа цвета, а для непрозрачного цвета — 0.35 (рекомендация ревью).
            val al = (a.color ushr 24) and 0xFF
            val ca = if (al in 1..254) al / 255.0 else DEFAULT_HIGHLIGHT_CA
            // §11.3.5 таблица 136: /BM /Multiply — маркер не закрывает текст; как в Acrobat/pdf.js.
            val c = StringBuilder("q\n/GS0 gs\n").append(rgbOp(a.color, "rg"))
            for (q in quads) {
                c.append(n(q[0])).append(' ').append(n(q[1])).append(" m ")
                c.append(n(q[2])).append(' ').append(n(q[3])).append(" l ")
                c.append(n(q[6])).append(' ').append(n(q[7])).append(" l ")
                c.append(n(q[4])).append(' ').append(n(q[5])).append(" l h f\n")
            }
            c.append("Q\n")
            val ap = add(form(rect, null, c, gs(ca, "Multiply")))
            val d = base("Highlight", rect, page, a.color, ca, ap)
            d["QuadPoints"] = PdfArray(quads.flatMapTo(ArrayList<PdfObj>()) { q -> q.map { PdfNum(it, false) } })
            if (a.text.isNotEmpty()) d["Contents"] = PdfText.encodeUtf16(a.text)
            return add(d)
        }

        /**
         * /Text (§12.5.6.4 таблица 172): /Name /Note, /Open false, /Contents — UTF-16BE с BOM.
         * У значка есть «верх», поэтому форму рисуем в локальной системе видимой страницы
         * (20×20, v вверх) и задаём /Matrix с поворотом (PageGeometry.formMatrix): по алгоритму
         * §12.5.5 ридер отображает преобразованный /BBox на /Rect, так что значок встаёт
         * в нужное место и смотрит прямо при любом /Rotate.
         *
         * Проверено рендером: PDFium (Android PdfRenderer, Chrome) — прямо и на месте при 0/90/180/270.
         * Ограничение: §12.5.6.4 велит показывать /Text так, будто стоят флаги NoZoom и NoRotate;
         * ридеры, которые это соблюдают (MuPDF — проверено; вероятно Acrobat, pdf.js), на страницах
         * с /Rotate 90/270 рисуют значок повёрнутым и сдвинутым на ~20 pt, но видимым. Пробовали:
         * единичная матрица — наоборот, боком в PDFium; поворот через «cm» в потоке — в MuPDF
         * значок пропадает. Выбран вариант, правильный для PDFium и видимый везде.
         */
        fun note(a: NoteAnnotation, g: PageGeometry, page: PdfRef): PdfRef {
            val s = NoteAnnotation.ICON_SIZE_PT.toDouble()
            val x = a.at.x.toDouble(); val y = a.at.y.toDouble()
            val rect = g.rectToPdf(x, y, x + s, y + s)
            val ca = alpha(a.color)
            val dark = darker(a.color)
            val c = StringBuilder("q\n")
            if (ca < 1.0) c.append("/GS0 gs\n")
            c.append("1 w 1 j 0 J\n")
                .append(rgbOp(a.color, "rg")).append(rgbOp(dark, "RG"))
                // лист с загнутым правым верхним углом
                .append("0.5 0.5 m 19.5 0.5 l 19.5 14.5 l 14.5 19.5 l 0.5 19.5 l h B\n")
                .append("14.5 19.5 m 14.5 14.5 l 19.5 14.5 l S\n")
                // строки «текста»
                .append("0.2 0.2 0.2 RG 1.2 w\n")
                .append("4 14.5 m 12 14.5 l S\n4 10.5 m 16 10.5 l S\n4 6.5 m 16 6.5 l S\n")
                .append("Q\n")
            val ap = add(form(doubleArrayOf(0.0, 0.0, s, s), g.formMatrix(), c, gs(ca, null)))
            val d = base("Text", rect, page, a.color, ca, ap)
            d["Name"] = PdfName("Note")
            d["Open"] = PdfBool(false)
            d["Contents"] = PdfText.encodeUtf16(a.text)
            return add(d)
        }

        /** Форма-XObject внешнего вида (§8.10 таблица 95, §12.5.5). */
        fun form(bbox: DoubleArray, matrix: DoubleArray?, content: CharSequence, extGState: PdfDict?): PdfStream {
            val d = pdfDictOf(
                "Type" to PdfName("XObject"),
                "Subtype" to PdfName("Form"),
                "FormType" to PdfNum(1),
                "BBox" to pdfNums(*bbox),
            )
            if (matrix != null) d["Matrix"] = pdfNums(*matrix)
            val res = PdfDict()
            if (extGState != null) res["ExtGState"] = pdfDictOf("GS0" to extGState)
            d["Resources"] = res
            return PdfStream(d, content.toString().toByteArray(Charsets.ISO_8859_1))
        }

        /** §8.4.5 таблица 58: /CA, /ca — непрозрачность обводки/заливки, /BM — режим наложения. */
        fun gs(ca: Double, blend: String?): PdfDict? {
            if (ca >= 1.0 && blend == null) return null
            val d = pdfDictOf("Type" to PdfName("ExtGState"), "CA" to PdfNum(ca, false), "ca" to PdfNum(ca, false))
            if (blend != null) d["BM"] = PdfName(blend)
            return d
        }

        // ---------------- запись ----------------

        fun serialize(): ByteArray {
            val needNl = pdf.isNotEmpty() && pdf.last().toInt() != 10 && pdf.last().toInt() != 13
            val base = pdf.size + if (needNl) 1 else 0
            val tail = ByteArrayOutputStream(4096 + newObjs.size * 512)
            if (needNl) tail.write('\n'.code)
            val tailStart = if (needNl) 1 else 0
            fun offset() = base + tail.size() - tailStart

            // num → (тип, поле2, поле3) как в §7.5.8.3 таблица 18
            val xref = java.util.TreeMap<Int, LongArray>()
            for ((num, pair) in newObjs) {
                xref[num] = longArrayOf(1, offset().toLong(), pair.first.toLong())
                tail.ascii("$num ${pair.first} obj\n")
                PdfSerializer.write(pair.second, tail)
                tail.ascii("\nendobj\n")
            }
            // Файл восстанавливали сканированием: старым секциям xref верить нельзя —
            // пишем полную таблицу без /Prev (тоже допустимое обновление по §7.5.6).
            val full = doc.repaired
            if (full) {
                for ((num, e) in doc.entries) if (num !in xref) when (e) {
                    is XrefEntry.InFile -> xref[num] = longArrayOf(1, e.offset.toLong(), e.gen.toLong())
                    is XrefEntry.Compressed -> xref[num] = longArrayOf(2, e.stmNum.toLong(), e.index.toLong())
                    else -> {}
                }
                xref[0] = longArrayOf(0, 0, 65535)
            }
            val useStream = if (full) xref.values.any { it[0] == 2L } else doc.lastXrefIsStream

            val tr = PdfDict()
            for ((k, v) in doc.trailer.map) if (k !in setOf("Size", "Prev", "XRefStm", "ID")) tr[k] = v
            if (!full && doc.lastXrefOffset >= 0) tr["Prev"] = PdfNum(doc.lastXrefOffset)
            tr["ID"] = fileId(tail)

            val xrefOffset: Int
            if (useStream) {
                val selfNum = alloc()
                xrefOffset = offset()
                xref[selfNum] = longArrayOf(1, xrefOffset.toLong(), 0)
                tr["Size"] = PdfNum(maxOf(next, doc.size))
                writeXrefStream(tail, selfNum, xref, tr)
            } else {
                xrefOffset = offset()
                tr["Size"] = PdfNum(maxOf(next, doc.size))
                // Подсекция «0 1 / 0000000000 65535 f» и в обновлении — как у Acrobat: иначе часть
                // читателей (pypdf) принимает таблицу за «не с нуля» и пытается её «чинить».
                xref.putIfAbsent(0, longArrayOf(0, 0, 65535))
                tail.ascii("xref\n")
                for ((start, count) in runs(xref.keys)) {
                    tail.ascii("$start $count\n")
                    for (num in start until start + count) {
                        val e = xref[num]!!
                        // §7.5.4: каждая запись ровно 20 байт: 10 цифр, пробел, 5 цифр, пробел, n|f, CRLF.
                        // Locale.ROOT: при локали ar/fa %d даёт арабско-индийские цифры → битая таблица xref.
                        tail.ascii(String.format(java.util.Locale.ROOT, "%010d %05d %s\r\n", e[1], e[2], if (e[0] == 0L) "f" else "n"))
                    }
                }
                tail.ascii("trailer\n")
                PdfSerializer.write(tr, tail)
                tail.ascii("\n")
            }
            tail.ascii("startxref\n$xrefOffset\n%%EOF\n")

            // Одно копирование исходника: результат = pdf + tail.
            val tb = tail.toByteArray()
            val out = ByteArray(pdf.size + tb.size)
            System.arraycopy(pdf, 0, out, 0, pdf.size)
            System.arraycopy(tb, 0, out, pdf.size, tb.size)
            return out
        }

        /** §7.5.8: поток xref с /W, /Index, /Prev; данные сжаты /FlateDecode. */
        fun writeXrefStream(tail: ByteArrayOutputStream, selfNum: Int, xref: Map<Int, LongArray>, tr: PdfDict) {
            fun width(v: Long): Int { var w = 1; var x = v ushr 8; while (x > 0) { w++; x = x ushr 8 }; return w }
            val w2 = width(xref.values.maxOf { it[1] })
            val w3 = width(xref.values.maxOf { it[2] })
            val rowLen = 1 + w2 + w3
            val data = ByteArray(xref.size * rowLen)
            var p = 0
            val index = ArrayList<PdfObj>()
            for ((start, count) in runs(xref.keys)) {
                index.add(PdfNum(start)); index.add(PdfNum(count))
                for (num in start until start + count) {
                    val e = xref[num]!!
                    data[p++] = e[0].toByte()
                    for (i in w2 - 1 downTo 0) data[p++] = (e[1] ushr (8 * i)).toByte()
                    for (i in w3 - 1 downTo 0) data[p++] = (e[2] ushr (8 * i)).toByte()
                }
            }
            val d = pdfDictOf("Type" to PdfName("XRef"))
            d.map.putAll(tr.map)
            d["W"] = pdfArrayOf(PdfNum(1), PdfNum(w2), PdfNum(w3))
            d["Index"] = PdfArray(index)
            d["Filter"] = PdfName("FlateDecode")
            tail.ascii("$selfNum 0 obj\n")
            PdfSerializer.write(PdfStream(d, PdfFilters.deflate(data)), tail)
            tail.ascii("\nendobj\n")
        }

        /** Подряд идущие номера → подсекции (start, count) (§7.5.4). */
        fun runs(keys: Collection<Int>): List<Pair<Int, Int>> {
            val res = ArrayList<Pair<Int, Int>>()
            var start = -1; var prev = -2
            for (k in keys.sorted()) {
                if (k != prev + 1) { if (start >= 0) res.add(start to prev - start + 1); start = k }
                prev = k
            }
            if (start >= 0) res.add(start to prev - start + 1)
            return res
        }

        /**
         * §14.4 «File identifiers»: при обновлении первая строка /ID сохраняется, вторая
         * должна измениться. Если /ID не было — создаём обе.
         */
        fun fileId(tail: ByteArrayOutputStream): PdfArray {
            val md = MessageDigest.getInstance("MD5")
            md.update(pdf, maxOf(0, pdf.size - 4096), minOf(pdf.size, 4096))
            md.update(tail.toByteArray())
            md.update(UUID.randomUUID().toString().toByteArray())
            val fresh = PdfString(md.digest(), hex = true)
            val old = doc.resolve(doc.trailer["ID"]).asArray()
            val first = (old?.items?.getOrNull(0)?.let { doc.resolve(it) } as? PdfString) ?: fresh
            return pdfArrayOf(first, fresh)
        }

        // ---------------- цвета и числа ----------------

        fun alpha(c: Argb): Double { val a = (c ushr 24) and 0xFF; return if (a == 0) 1.0 else a / 255.0 }
        fun comps(c: Argb) = doubleArrayOf(((c shr 16) and 0xFF) / 255.0, ((c shr 8) and 0xFF) / 255.0, (c and 0xFF) / 255.0)
        fun rgb(c: Argb): PdfArray = pdfNums(*comps(c))
        fun rgbOp(c: Argb, op: String): String { val v = comps(c); return "${n(v[0])} ${n(v[1])} ${n(v[2])} $op\n" }
        fun darker(c: Argb): Argb {
            val v = comps(c)
            fun ch(x: Double) = (x * 0.6 * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(v[0]) shl 16) or (ch(v[1]) shl 8) or ch(v[2])
        }
        fun n(v: Double) = PdfSerializer.num(v)
        /** Округление как при записи (4 знака, PdfSerializer.num). */
        fun rnd(v: Double) = PdfSerializer.num(v).toDouble()
        fun outward(r: DoubleArray) = doubleArrayOf(Math.floor(r[0]), Math.floor(r[1]), Math.ceil(r[2]), Math.ceil(r[3]))
    }
}
