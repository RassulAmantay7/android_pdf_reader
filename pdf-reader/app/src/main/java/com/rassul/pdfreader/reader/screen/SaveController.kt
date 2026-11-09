package com.rassul.pdfreader.reader.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.rassul.pdfreader.core.pdf.PdfAnnotationWriter
import com.rassul.pdfreader.core.pdf.PdfFormatException
import com.rassul.pdfreader.reader.PdfDoc
import com.rassul.pdfreader.reader.ReaderActivity
import com.rassul.pdfreader.ui.Ui
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Callable

/**
 * Сохранение пометок и правок форм (FR-35…FR-38):
 *  a) временный файл в cacheDir (doc.writeTo, если правились формы, иначе копия исходника);
 *  b) пометки — PdfAnnotationWriter.append (инкрементальное обновление);
 *  c) проверка: результат открывается PdfRenderer и число страниц совпадает;
 *  d) «Перезаписать»: резервная копия в filesDir/backups (последние 5) → запись "wt";
 *     «Копия»: ACTION_CREATE_DOCUMENT → запись туда → открыть копию;
 *  e) переоткрыть документ (пометки уже в файле), «Сохранено».
 */
internal class SaveController(private val a: ReaderActivity) {
    companion object {
        const val REQ_CREATE_COPY = 4101
        private const val KEEP_BACKUPS = 5
    }

    private enum class Target { COPY, OVERWRITE }

    var busy = false
        private set
    private var pendingCopy: File? = null
    private var pendingFinish = false

    /** Спросить «Как сохранить?» (каждый раз, FR-36). finishAfter — закрыть экран после успеха. */
    fun start(finishAfter: Boolean) {
        val d = a.doc ?: return
        if (busy) { Ui.toast(a, "Идёт сохранение…"); return }
        if (!a.isDirty()) { Ui.toast(a, "Нет несохранённых изменений"); if (finishAfter) a.finish(); return }
        if (a.canWrite) {
            Ui.showChoice(a, "Как сохранить?",
                "Перезаписать исходный файл «${d.displayName}» или сохранить изменения в новый файл.",
                listOf(
                    "Отмена" to {},
                    "Сохранить копию" to { prepare(Target.COPY, finishAfter) },
                    "Перезаписать файл" to { prepare(Target.OVERWRITE, finishAfter) },
                ))
        } else {
            Ui.showChoice(a, "Как сохранить?",
                "Этот файл открыт только для чтения — можно сохранить копию.",
                listOf(
                    "Отмена" to {},
                    "Сохранить копию" to { prepare(Target.COPY, finishAfter) },
                ))
        }
    }

    private fun prepare(target: Target, finishAfter: Boolean) {
        val d = a.doc ?: return
        if (busy) return
        val annots = a.view.annotations.all
        val forms = d.formsModified
        val expectedPages = d.pageCount
        val password = a.password
        busy = true
        a.showBusy("Сохраняем…")
        a.bg.runIo {
            var tmp: File? = null
            try {
                val t = File(a.cacheDir, "save-${System.currentTimeMillis()}.pdf")
                tmp = t
                // a) исходное состояние документа во временный файл
                if (forms) {
                    d.worker.submit(Callable {
                        ParcelFileDescriptor.open(t, ParcelFileDescriptor.MODE_READ_WRITE or
                            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { d.writeTo(it) }
                    }).get()
                } else {
                    FileIo.copyUriToFile(a.applicationContext, d.uri, t)
                }
                // b) пометки
                if (annots.isNotEmpty()) {
                    // Писатель работает с массивом байтов: файл + результат ≈ 2× размера в куче.
                    if (t.length() > Runtime.getRuntime().maxMemory() / 3)
                        throw UserMessageException("Файл слишком большой для сохранения пометок. Оригинал не изменён.")
                    val out = PdfAnnotationWriter.append(t.readBytes(), annots)
                    t.writeBytes(out)
                }
                // c) проверка результата
                verify(d, t, expectedPages, password)
                // d) запись
                when (target) {
                    Target.OVERWRITE -> {
                        overwrite(d, t)
                        a.bg.ui { finished(d, d.uri, finishAfter, "Сохранено") }
                    }
                    Target.COPY -> {
                        tmp = null // файл нужен до выбора места
                        a.bg.ui { launchCreate(d, t, finishAfter) }
                    }
                }
            } catch (e: Throwable) {
                val msg = messageFor(unwrap(e))
                a.bg.ui { failed(msg) }
            } finally {
                tmp?.delete()
            }
        }
    }

    private fun verify(d: PdfDoc, f: File, expectedPages: Int, password: String?) {
        val pages = try {
            d.worker.submit(Callable {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val r = if (password != null) PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
                            else PdfRenderer(pfd)
                    try { r.pageCount } finally { r.close() }
                }
            }).get()
        } catch (e: Throwable) {
            throw UserMessageException("Проверка сохранённого файла не прошла. Оригинал не изменён.", e)
        }
        if (pages != expectedPages) throw UserMessageException("Проверка сохранённого файла не прошла. Оригинал не изменён.")
    }

    private fun overwrite(d: PdfDoc, src: File) {
        val ctx = a.applicationContext
        val backup = try { makeBackup(d) } catch (e: Throwable) {
            val u = unwrap(e)
            if (u is SecurityException || u is FileNotFoundException) throw UserMessageException("Нет доступа к файлу. Оригинал не изменён.", e)
            throw UserMessageException("Не удалось сделать резервную копию. Оригинал не изменён.", e)
        }
        val opened = BooleanArray(1)
        try {
            FileIo.writeFileToUri(ctx, src, d.uri, opened)
        } catch (e: Throwable) {
            val noPerm = e is SecurityException || e is FileNotFoundException
            if (!opened[0]) {
                throw UserMessageException(
                    if (noPerm) "Нет права на запись в этот файл — сохраните копию" else "Не удалось сохранить файл, оригинал не изменён", e)
            }
            val restored = try { FileIo.writeFileToUri(ctx, backup, d.uri); true } catch (_: Throwable) { false }
            throw UserMessageException(when {
                restored && noPerm -> "Нет права на запись в этот файл — сохраните копию"
                restored -> "Не удалось сохранить файл, оригинал не изменён"
                else -> "Не удалось записать файл. Резервная копия оригинала сохранена во внутренней памяти приложения (${backup.name})."
            }, e)
        }
    }

    private fun makeBackup(d: PdfDoc): File {
        val dir = File(a.filesDir, "backups").apply { mkdirs() }
        val safe = d.displayName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80).ifBlank { "document.pdf" }
        val f = File(dir, "${System.currentTimeMillis()}-$safe")
        try {
            FileIo.copyUriToFile(a.applicationContext, d.uri, f)
        } catch (e: Throwable) { f.delete(); throw e }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_BACKUPS)?.forEach { it.delete() }
        return f
    }

    private fun launchCreate(d: PdfDoc, tmp: File, finishAfter: Boolean) {
        val base = d.displayName.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "").ifBlank { "Документ" }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, "$base (с пометками).pdf")
        pendingCopy = tmp
        pendingFinish = finishAfter
        a.hideBusy()
        try {
            a.startActivityForResult(i, REQ_CREATE_COPY)
        } catch (e: ActivityNotFoundException) {
            tmp.delete(); pendingCopy = null
            failed("Не найдено приложение для выбора места сохранения")
        }
    }

    /** Результат ACTION_CREATE_DOCUMENT. */
    fun onCopyTarget(target: Uri?, flags: Int) {
        val tmp = pendingCopy ?: run { busy = false; return }
        pendingCopy = null
        val d = a.doc
        if (target == null || d == null) { tmp.delete(); busy = false; return }
        a.showBusy("Сохраняем копию…")
        val finishAfter = pendingFinish
        a.bg.runIo {
            try {
                FileIo.writeFileToUri(a.applicationContext, tmp, target)
                try {
                    val f = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    a.contentResolver.takePersistableUriPermission(target, if (f != 0) f else Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                a.bg.ui { finished(d, target, finishAfter, "Копия сохранена") }
            } catch (e: Throwable) {
                val u = unwrap(e)
                val msg = if (u is SecurityException || u is FileNotFoundException) "Нет доступа к выбранному месту сохранения"
                          else "Не удалось сохранить копию"
                a.bg.ui { failed(msg) }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun finished(d: PdfDoc, uri: Uri, finishAfter: Boolean, message: String) {
        busy = false
        a.hideBusy()
        a.onSaved(d, uri, finishAfter)
        Ui.toast(a, message)
    }

    private fun failed(msg: String) {
        busy = false
        a.hideBusy()
        Ui.showChoice(a, "Не удалось сохранить", msg, listOf("OK" to {}))
    }

    private fun messageFor(e: Throwable): String = when (e) {
        is UserMessageException -> e.message ?: "Не удалось сохранить файл, оригинал не изменён"
        is PdfFormatException -> e.message ?: "Не удалось сохранить файл, оригинал не изменён"
        is OutOfMemoryError -> "Файл слишком большой для сохранения пометок. Оригинал не изменён."
        is SecurityException, is FileNotFoundException -> "Нет доступа к файлу. Оригинал не изменён."
        else -> "Не удалось сохранить файл, оригинал не изменён"
    }
}
