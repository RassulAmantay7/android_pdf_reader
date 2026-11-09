package com.rassul.pdfreader.core.pdf

import com.rassul.pdfreader.core.annot.Annotation

/*
 * КОНТРАКТ (владелец реализации — агент «core-pdf»). Сигнатуры менять нельзя без согласования.
 * Чистый Kotlin/JVM, без Android.
 */

/** Ошибка разбора/записи PDF, сообщение — на русском, для показа пользователю. */
class PdfFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Пункт оглавления. pageIndex — 0-based, null если цель не удалось определить. */
data class OutlineItem(
    val title: String,
    val pageIndex: Int?,
    val level: Int,
    val children: List<OutlineItem>,
)

object PdfOutlineReader {
    /** Оглавление (/Outlines) документа. Пустой список, если его нет. Не бросает на битых закладках — пропускает их. */
    @Throws(PdfFormatException::class)
    fun read(pdf: ByteArray): List<OutlineItem> = OutlineReaderImpl.read(pdf)
}

object PdfAnnotationWriter {
    /**
     * Возвращает новый PDF = исходные байты без изменений + инкрементальное обновление
     * (ISO 32000-1 §7.5.6), добавляющее пометки на страницы как стандартные аннотации
     * /Ink, /Highlight, /Text (с потоками внешнего вида /AP, чтобы их видели все ридеры).
     * Координаты в аннотациях — «координаты страницы» (см. Annotations.kt), писатель сам
     * переводит их с учётом /MediaBox, /CropBox и /Rotate.
     */
    @Throws(PdfFormatException::class)
    fun append(pdf: ByteArray, annotations: List<Annotation>): ByteArray = AnnotationWriterImpl.append(pdf, annotations)
}
