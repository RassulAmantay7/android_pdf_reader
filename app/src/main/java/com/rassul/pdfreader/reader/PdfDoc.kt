package com.rassul.pdfreader.reader

import android.content.Context
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.SizeF
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * КОНТРАКТ (владелец реализации — агент «reader-view»). Сигнатуры не менять.
 * Обёртка над android.graphics.pdf.PdfRenderer (API 35). PdfRenderer НЕ потокобезопасен и
 * допускает только одну открытую страницу — весь доступ идёт через runOnPage под замком.
 */

/** Документ защищён паролем (или пароль неверный). */
class PasswordRequiredException(val wrongPassword: Boolean) : Exception()

class PdfDoc private constructor(
    private val renderer: PdfRenderer,
    private val tempFile: File?,
    private val docUri: Uri,
    private val name: String,
) : Closeable {
    companion object {
        /** Сколько страниц измерять за одну задачу фонового измерения (остальные задачи — рендер — идут между ними). */
        private const val MEASURE_CHUNK = 24

        /**
         * Открыть документ. Блокирующий вызов — звать НЕ из UI-потока.
         * @throws PasswordRequiredException если нужен пароль / пароль неверный
         * @throws java.io.IOException при ошибке чтения или повреждённом файле
         */
        fun open(context: Context, uri: Uri, password: String?): PdfDoc {
            val cr = context.contentResolver
            val name = queryDisplayName(context, uri)
            var pfd = try {
                cr.openFileDescriptor(uri, "r")
            } catch (e: SecurityException) {
                throw IOException("Нет доступа к файлу", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("Неверный адрес файла", e)
            } ?: throw IOException("Не удалось открыть файл")

            // PdfRenderer требует файловый дескриптор с произвольным доступом (seekable).
            // Некоторые провайдеры отдают канал (pipe) — statSize у него < 0. Тогда копируем
            // во временный файл в cacheDir.
            var temp: File? = null
            if (pfd.statSize < 0) {
                temp = File.createTempFile("open_", ".pdf", context.cacheDir)
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    FileOutputStream(temp).use { out -> input.copyTo(out) }
                }
                pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            val renderer = try {
                if (password == null) PdfRenderer(pfd)
                else PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
            } catch (e: SecurityException) {
                // Документация PdfRenderer: «SecurityException — if the file requires a password
                // or the security scheme is not supported».
                closeQuietly(pfd); temp?.delete()
                throw PasswordRequiredException(wrongPassword = password != null)
            } catch (e: IOException) {
                closeQuietly(pfd); temp?.delete(); throw e
            } catch (e: Exception) {
                closeQuietly(pfd); temp?.delete()
                throw IOException("Файл повреждён или не является PDF", e)
            }
            // С этого момента pfd принадлежит renderer: «This class takes ownership of the passed
            // in file descriptor and is responsible for closing it when the renderer is closed.»
            val doc = PdfDoc(renderer, temp, uri, name)
            try {
                doc.init()
            } catch (e: Exception) {
                doc.close()
                throw if (e is IOException) e else IOException("Файл повреждён или не является PDF", e)
            }
            return doc
        }

        private fun queryDisplayName(context: Context, uri: Uri): String {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            } catch (_: Exception) { /* провайдер может не поддерживать query — берём из пути */ }
            return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "document.pdf"
        }

        private fun closeQuietly(c: Closeable?) { try { c?.close() } catch (_: Exception) {} }
    }

    private val lock = Any()
    @Volatile private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-worker").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }

    private var count = 0
    /** Размеры страниц (pt). Массив заменяется целиком (copy-on-write), поэтому чтение из UI безопасно. */
    @Volatile private var sizes: Array<SizeF> = emptyArray()
    @Volatile private var measured = 0

    val uri: Uri get() = docUri
    /** Имя файла для показа (из OpenableColumns.DISPLAY_NAME). */
    val displayName: String get() = name
    val pageCount: Int get() = count

    /** Тип формы (PdfRenderer.PDF_FORM_TYPE_*): NONE, ACRO_FORM, XFA_FULL, XFA_FOREGROUND. */
    var formType: Int = PdfRenderer.PDF_FORM_TYPE_NONE
        private set
    val hasForms: Boolean get() = formType != PdfRenderer.PDF_FORM_TYPE_NONE

    /** true, когда размеры всех страниц измерены (до этого непромеренные = размеру первой страницы). */
    val pageSizesComplete: Boolean get() = measured >= count

    /**
     * Вызывается в UI-потоке, когда фоновое измерение уточнило размеры страниц (порциями).
     * Для документа с одинаковыми страницами размеры не меняются и вызов не происходит.
     */
    @Volatile var onPageSizesChanged: (() -> Unit)? = null

    /**
     * Размер видимой страницы в пунктах (уже с учётом поворота). Кэшируется при открытии.
     * Page.getWidth/getHeight — «in points (1/72")» (документация PdfRenderer.Page).
     * Чтобы первая страница большого файла показалась быстро (NFR-12), при открытии синхронно
     * измеряется только первая страница; остальные — в фоне на worker порциями.
     */
    fun pageSize(index: Int): SizeF {
        val s = sizes
        return if (index in s.indices) s[index] else s.firstOrNull() ?: SizeF(612f, 792f)
    }

    /** Однопоточный исполнитель для всей работы с PdfRenderer (рендер, поиск, формы). */
    val worker: ExecutorService get() = executor

    private fun init() {
        count = renderer.pageCount
        formType = try { renderer.pdfFormType } catch (_: Exception) { PdfRenderer.PDF_FORM_TYPE_NONE }
        if (count <= 0) throw IOException("В документе нет страниц")
        val first = runOnPage(0) { SizeF(it.width.toFloat(), it.height.toFloat()) }
        sizes = Array(count) { first }
        measured = 1
        if (count > 1) executor.execute { measureChunk(1) }
    }

    private fun measureChunk(from: Int) {
        if (closed) return
        val to = minOf(count, from + MEASURE_CHUNK)
        val copy = sizes.copyOf()
        var changed = false
        try {
            for (i in from until to) {
                val s = runOnPage(i) { SizeF(it.width.toFloat(), it.height.toFloat()) }
                if (s != copy[i]) changed = true
                copy[i] = s
            }
        } catch (_: Exception) {
            return // документ закрыт или страница битая — оставляем оценку
        }
        // Непромеренные страницы предполагаем такими же, как последняя промеренная —
        // это ближе к истине для документов, где меняется формат с какого-то места.
        val last = copy[to - 1]
        for (i in to until count) if (copy[i] != last) { copy[i] = last; changed = true }
        sizes = copy
        measured = to
        if (changed) main.post { if (!closed) onPageSizesChanged?.invoke() }
        if (to < count && !closed) {
            try { executor.execute { measureChunk(to) } } catch (_: Exception) {}
        }
    }

    /** Выполнить block с открытой страницей. Блокирующий, потокобезопасный; звать с worker-потока. */
    fun <T> runOnPage(index: Int, block: (PdfRenderer.Page) -> T): T {
        synchronized(lock) {
            check(!closed) { "Документ закрыт" }
            require(index in 0 until count) { "Нет страницы $index" }
            val page = renderer.openPage(index)
            try {
                return block(page)
            } finally {
                page.close()
            }
        }
    }

    /** true, если через applyEdit были изменения форм, ещё не сохранённые. */
    @Volatile var formsModified: Boolean = false

    /** Записать текущее состояние документа (включая правки форм) в pfd через PdfRenderer.write. Звать с worker. */
    fun writeTo(pfd: ParcelFileDescriptor) {
        synchronized(lock) {
            check(!closed) { "Документ закрыт" }
            // write(destination, removePasswordProtection = false): пароль (если был) сохраняется.
            renderer.write(pfd, false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onPageSizesChanged = null
        // Задачи из очереди, не успевшие начаться, отменяем: иначе Future.get() у тех, кто ждёт
        // результата (обложка, сохранение), не вернётся никогда и повесит их поток.
        for (r in executor.shutdownNow()) (r as? java.util.concurrent.Future<*>)?.cancel(false)
        // Ждём окончания текущей операции с PdfRenderer (замок), затем закрываем (renderer закрывает и pfd).
        synchronized(lock) {
            try { renderer.close() } catch (_: Exception) {}
        }
        tempFile?.delete()
    }
}
