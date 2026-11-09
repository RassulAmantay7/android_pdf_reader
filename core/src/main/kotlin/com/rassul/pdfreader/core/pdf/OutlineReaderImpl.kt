package com.rassul.pdfreader.core.pdf

/**
 * Чтение оглавления (ISO 32000-1 §12.3.3 «Document outline», §12.3.2 «Destinations»).
 */
internal object OutlineReaderImpl {
    private const val MAX_DEPTH = 32
    private const val MAX_ITEMS = 100_000

    fun read(pdf: ByteArray): List<OutlineItem> {
        val doc = openDocument(pdf)
        // Зашифрованный файл: строки зашифрованы, расшифровку не поддерживаем — оглавления нет.
        if (doc.isEncrypted) return emptyList()
        return try {
            Reader(doc).run()
        } catch (e: PdfFormatException) {
            emptyList()
        } catch (e: RuntimeException) {
            emptyList()
        } catch (e: StackOverflowError) {
            emptyList()
        }
    }

    private class Reader(val doc: PdfDocument) {
        val catalog = doc.catalog
        val pageByNum: Map<Int, Int> by lazy { doc.pages().filter { it.ref != null }.associate { it.ref!!.num to it.index } }
        val pageCount by lazy { doc.pages().size }
        val visited = HashSet<Int>()
        var count = 0

        fun run(): List<OutlineItem> {
            val outlines = doc.resolve(catalog?.get("Outlines")).asDict() ?: return emptyList()
            return siblings(outlines["First"], 0)
        }

        /** Цепочка /First → /Next (§12.3.3, таблица 153). */
        fun siblings(first: PdfObj?, level: Int): List<OutlineItem> {
            if (level >= MAX_DEPTH) return emptyList()
            val out = ArrayList<OutlineItem>()
            var cur = first
            while (cur != null && count < MAX_ITEMS) {
                val ref = cur as? PdfRef
                if (ref != null && !visited.add(ref.num)) break // цикл
                val d = doc.resolve(cur).asDict() ?: break
                count++
                try {
                    val title = (doc.resolve(d["Title"]) as? PdfString)?.let { PdfText.decode(it.bytes) }
                        ?.replace(Regex("[\\u0000-\\u001F]+"), " ")?.trim() ?: ""
                    val page = target(d)
                    val kids = siblings(d["First"], level + 1)
                    out.add(OutlineItem(title, page, level, kids))
                } catch (e: PdfFormatException) {
                    // битая закладка — пропускаем, идём дальше
                } catch (e: RuntimeException) { }
                cur = d["Next"]
                if (cur !is PdfRef) cur = null // /Next обязан быть косвенным; прямой словарь мог бы зациклиться
            }
            return out
        }

        /** /Dest или действие /A с /S /GoTo и /D (§12.6.4.2). */
        fun target(item: PdfDict): Int? {
            item["Dest"]?.let { return dest(it, 0) }
            val a = doc.resolve(item["A"]).asDict() ?: return null
            if (doc.resolve(a["S"]).asName() != "GoTo") return null
            return dest(a["D"], 0)
        }

        /**
         * Назначение (§12.3.2.2): массив [page …]; имя → старый словарь /Root /Dests (§12.3.2.3);
         * строка → дерево имён /Root /Names /Dests (§7.9.6). Значение именованного назначения —
         * массив или словарь с /D.
         */
        fun dest(o: PdfObj?, depth: Int): Int? {
            if (depth > 4) return null
            val v = doc.resolve(o)
            return when (v) {
                is PdfArray -> {
                    val p = v.items.firstOrNull() ?: return null
                    when (p) {
                        is PdfRef -> pageByNum[p.num]
                        // Нестандартно (так пишут удалённые GoTo), но встречается в локальных: номер страницы.
                        is PdfNum -> p.int.takeIf { it in 0 until pageCount }
                        else -> null
                    }
                }
                is PdfDict -> dest(v["D"], depth + 1)
                is PdfName -> dest(oldDests(v.name.toByteArray(Charsets.ISO_8859_1)) ?: nameTree(v.name.toByteArray(Charsets.ISO_8859_1)), depth + 1)
                is PdfString -> dest(nameTree(v.bytes) ?: oldDests(v.bytes), depth + 1)
                else -> null
            }
        }

        fun oldDests(key: ByteArray): PdfObj? =
            doc.resolve(catalog?.get("Dests")).asDict()?.get(String(key, Charsets.ISO_8859_1))

        fun nameTree(key: ByteArray): PdfObj? {
            val names = doc.resolve(catalog?.get("Names")).asDict() ?: return null
            return lookup(names["Dests"], key, HashSet(), 0)
        }

        /** Дерево имён (§7.9.6, таблица 36): /Kids, /Names [k v …], /Limits [min max]. */
        fun lookup(nodeObj: PdfObj?, key: ByteArray, seen: HashSet<Int>, depth: Int): PdfObj? {
            if (depth > 32) return null
            if (nodeObj is PdfRef && !seen.add(nodeObj.num)) return null
            val node = doc.resolve(nodeObj).asDict() ?: return null
            val limits = doc.resolve(node["Limits"]).asArray()
            if (limits != null && limits.size >= 2) {
                val lo = (doc.resolve(limits[0]) as? PdfString)?.bytes
                val hi = (doc.resolve(limits[1]) as? PdfString)?.bytes
                // Ключи упорядочены побайтово (§7.9.6); /Limits иногда врут — проверяем только как подсказку.
                if (lo != null && hi != null && (cmp(key, lo) < 0 || cmp(key, hi) > 0) && depth > 0) return null
            }
            doc.resolve(node["Names"]).asArray()?.let { arr ->
                var i = 0
                while (i + 1 < arr.size) {
                    val k = doc.resolve(arr[i])
                    val kb = when (k) { is PdfString -> k.bytes; is PdfName -> k.name.toByteArray(Charsets.ISO_8859_1); else -> null }
                    if (kb != null && kb.contentEquals(key)) return arr[i + 1]
                    i += 2
                }
            }
            doc.resolve(node["Kids"]).asArray()?.items?.forEach { kid ->
                lookup(kid, key, seen, depth + 1)?.let { return it }
            }
            return null
        }

        fun cmp(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}

/** Общий вход: разбор документа с понятной ошибкой для пользователя. */
internal fun openDocument(pdf: ByteArray): PdfDocument = try {
    PdfDocument(pdf)
} catch (e: PdfFormatException) {
    throw PdfFormatException("Не удалось прочитать PDF: файл повреждён или это не PDF", e)
} catch (e: RuntimeException) {
    throw PdfFormatException("Не удалось прочитать PDF: файл повреждён или это не PDF", e)
} catch (e: StackOverflowError) {
    throw PdfFormatException("Не удалось прочитать PDF: слишком сложная структура", null)
}
