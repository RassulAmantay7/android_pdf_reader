package com.rassul.pdfreader.core.pdf

/*
 * Минимальная объектная модель PDF (ISO 32000-1 §7.3 «Objects»).
 * Низкоуровневый слой пакета core.pdf: публичен только ради тестов (check.sh компилирует
 * их отдельным модулем); приложению следует пользоваться PdfOutlineReader/PdfAnnotationWriter.
 */

sealed class PdfObj

object PdfNull : PdfObj() {
    override fun toString() = "null"
}

data class PdfBool(val value: Boolean) : PdfObj()

/** Число (§7.3.3). [isInt] — было записано как целое (без точки). */
data class PdfNum(val value: Double, val isInt: Boolean) : PdfObj() {
    constructor(v: Int) : this(v.toDouble(), true)
    constructor(v: Long) : this(v.toDouble(), true)
    constructor(v: Float) : this(v.toDouble(), false)

    val int: Int get() = value.toInt()
    val long: Long get() = value.toLong()
}

/** Строка (§7.3.4) — сырые байты; [hex] только влияет на исходную запись. */
class PdfString(val bytes: ByteArray, val hex: Boolean = false) : PdfObj() {
    override fun equals(other: Any?) = other is PdfString && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "(" + String(bytes, Charsets.ISO_8859_1) + ")"
}

/**
 * Имя (§7.3.5). [name] — байты имени после раскрытия #xx, по одному символу на байт
 * (ISO-8859-1), т.е. без потерь.
 */
data class PdfName(val name: String) : PdfObj() {
    override fun toString() = "/$name"
}

class PdfArray(val items: MutableList<PdfObj> = ArrayList()) : PdfObj() {
    val size: Int get() = items.size
    operator fun get(i: Int): PdfObj = items[i]
    override fun toString() = items.joinToString(" ", "[", "]")
}

class PdfDict(val map: LinkedHashMap<String, PdfObj> = LinkedHashMap()) : PdfObj() {
    operator fun get(key: String): PdfObj? = map[key]
    operator fun set(key: String, v: PdfObj) { map[key] = v }
    fun copy(): PdfDict = PdfDict(LinkedHashMap(map))
    override fun toString() = map.entries.joinToString(" ", "<<", ">>") { "/${it.key} ${it.value}" }
}

/** Косвенная ссылка «n g R» (§7.3.10). */
data class PdfRef(val num: Int, val gen: Int) : PdfObj() {
    override fun toString() = "$num $gen R"
}

/**
 * Поток (§7.3.8): словарь + закодированные данные src[off, off+len) — без копирования
 * исходного массива.
 */
class PdfStream(val dict: PdfDict, val src: ByteArray, val off: Int, val len: Int) : PdfObj() {
    constructor(dict: PdfDict, data: ByteArray) : this(dict, data, 0, data.size)
    fun rawBytes(): ByteArray = src.copyOfRange(off, off + len)
    override fun toString() = "$dict stream[$len]"
}

/** Ключевое слово, встреченное парсером там, где ждали объект (endobj, R, stream, …). */
class PdfKeyword(val word: String) : PdfObj() {
    override fun toString() = word
}

// ---- удобные доступы ----
fun PdfObj?.asDict(): PdfDict? = when (this) {
    is PdfDict -> this
    is PdfStream -> dict
    else -> null
}
fun PdfObj?.asArray(): PdfArray? = this as? PdfArray
fun PdfObj?.asName(): String? = (this as? PdfName)?.name
fun PdfObj?.asNum(): Double? = (this as? PdfNum)?.value
fun PdfObj?.asInt(): Int? = (this as? PdfNum)?.value?.toInt()

fun pdfDictOf(vararg pairs: Pair<String, PdfObj>): PdfDict =
    PdfDict(LinkedHashMap<String, PdfObj>().apply { pairs.forEach { put(it.first, it.second) } })

fun pdfArrayOf(vararg items: PdfObj): PdfArray = PdfArray(items.toMutableList())
fun pdfNums(vararg v: Double): PdfArray = PdfArray(v.mapTo(ArrayList()) { PdfNum(it, false) })
