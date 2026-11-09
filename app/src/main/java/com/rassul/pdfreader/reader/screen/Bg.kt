package com.rassul.pdfreader.reader.screen

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.provider.OpenableColumns
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Фоновая работа экрана чтения: отдельный поток для файлового ввода-вывода
 * (всё, что трогает PdfRenderer, идёт через PdfDoc.worker) и возврат в UI-поток
 * с проверкой, что Activity ещё жива.
 */
internal class Bg(private val activity: Activity) {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "reader-io").apply { isDaemon = true }
    }
    val main = Handler(Looper.getMainLooper())

    /** Выполнить в UI-потоке, если Activity не уничтожена. */
    fun ui(block: () -> Unit) {
        main.post { if (!activity.isDestroyed) block() }
    }

    /** Выполнить в потоке ввода-вывода (молча игнорирует вызов после остановки). */
    fun runIo(block: () -> Unit) {
        try { io.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** true после shutdown (onDestroy): фоновые задачи должны сами освободить то, что открыли. */
    @Volatile var stopped = false
        private set

    fun shutdown() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        io.shutdown()
    }
}

/** Ошибка с готовым текстом для пользователя. */
internal class UserMessageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Файл больше лимита. */
internal class TooLargeException : Exception()

internal fun unwrap(t: Throwable): Throwable {
    var e = t
    while (e is ExecutionException && e.cause != null) e = e.cause!!
    return e
}

internal object FileIo {
    const val MB = 1024L * 1024L

    /** Размер по OpenableColumns.SIZE (или -1). */
    fun querySize(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: -1L
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }

    fun openIn(ctx: Context, uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

    /** Прочитать файл целиком, не больше limit байт (иначе TooLargeException). */
    fun readAllCapped(ctx: Context, uri: Uri, limit: Long): ByteArray {
        val size = querySize(ctx, uri)
        if (size > limit) throw TooLargeException()
        openIn(ctx, uri).use { input ->
            if (size in 1..limit) {
                // Размер известен — читаем сразу в массив нужной длины (без второй копии в toByteArray).
                val arr = ByteArray(size.toInt())
                var off = 0
                while (off < arr.size) {
                    val n = input.read(arr, off, arr.size - off)
                    if (n < 0) break
                    off += n
                }
                if (off < arr.size) return arr.copyOf(off)
                val extra = input.read()
                if (extra < 0) return arr
                // файл оказался длиннее заявленного — дочитываем обычным путём
                if (arr.size + 1L > limit) throw TooLargeException()
                val rest = ByteArrayOutputStream(arr.size + (1 shl 16))
                rest.write(arr); rest.write(extra)
                val b = ByteArray(1 shl 16)
                var t = arr.size + 1L
                while (true) {
                    val n = input.read(b); if (n < 0) break
                    t += n; if (t > limit) throw TooLargeException()
                    rest.write(b, 0, n)
                }
                return rest.toByteArray()
            }
            val out = ByteArrayOutputStream(if (size in 1..limit) size.toInt() else 1 shl 16)
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw TooLargeException()
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    fun copyUriToFile(ctx: Context, uri: Uri, dst: File) {
        openIn(ctx, uri).use { input -> FileOutputStream(dst).use { out -> input.copyTo(out, 1 shl 16); syncQuietly(out) } }
    }

    /**
     * Открыть uri на запись с усечением. Не все провайдеры понимают "wt": пробуем "wt", "rwt",
     * затем "w" (тогда усечение делает writeFileToUri через ftruncate). SecurityException не глушим.
     */
    fun openOutPfd(ctx: Context, uri: Uri): ParcelFileDescriptor {
        val cr = ctx.contentResolver
        var last: Exception? = null
        for (mode in arrayOf("wt", "rwt", "w")) {
            try {
                cr.openFileDescriptor(uri, mode)?.let { return it }
            } catch (e: SecurityException) {
                throw e
            } catch (e: FileNotFoundException) { last = e
            } catch (e: IllegalArgumentException) { last = e
            } catch (e: UnsupportedOperationException) { last = e }
        }
        throw (last as? FileNotFoundException) ?: FileNotFoundException(uri.toString()).apply { last?.let { initCause(it) } }
    }

    /**
     * Записать содержимое src в uri. opened[0] = true, как только поток на запись открыт
     * (то есть исходный файл мог быть изменён). После записи файл усекается до длины src
     * (на случай режима "w" без усечения; для каналов-pipe ftruncate молча не выполняется).
     */
    fun writeFileToUri(ctx: Context, src: File, uri: Uri, opened: BooleanArray? = null) {
        val pfd = openOutPfd(ctx, uri)
        opened?.set(0, true)
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            var total = 0L
            FileInputStream(src).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            out.flush()
            try { Os.ftruncate(out.fd, total) } catch (_: ErrnoException) { } catch (_: Exception) { }
            syncQuietly(out)
        }
    }

    private fun syncQuietly(out: OutputStream) {
        try { (out as? FileOutputStream)?.fd?.sync() } catch (_: Exception) { }
    }
}

/** Кружок текущего цвета инструмента (кнопка 48dp). */
internal class ColorDotView(ctx: Context, private val sizePx: Int, private val ringColor: Int) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    var color: Int = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) * 0.27f
        fill.color = color
        canvas.drawCircle(cx, cy, r, fill)
        ring.color = ringColor
        ring.strokeWidth = r * 0.18f
        canvas.drawCircle(cx, cy, r + ring.strokeWidth, ring)
    }
}
