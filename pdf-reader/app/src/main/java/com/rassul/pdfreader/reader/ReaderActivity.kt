package com.rassul.pdfreader.reader

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.data.Bookmark
import com.rassul.pdfreader.data.BookmarkStore
import com.rassul.pdfreader.data.PositionStore
import com.rassul.pdfreader.data.ReadingMode
import com.rassul.pdfreader.data.ReadingPosition
import com.rassul.pdfreader.data.RecentStore
import com.rassul.pdfreader.data.SettingsStore
import com.rassul.pdfreader.data.ThumbnailStore
import com.rassul.pdfreader.reader.screen.AnnotToolbar
import com.rassul.pdfreader.reader.screen.Bg
import com.rassul.pdfreader.reader.screen.DraftStore
import com.rassul.pdfreader.reader.screen.FormController
import com.rassul.pdfreader.reader.screen.OutlineController
import com.rassul.pdfreader.reader.screen.PasswordDialog
import com.rassul.pdfreader.reader.screen.SaveController
import com.rassul.pdfreader.reader.screen.SearchController
import com.rassul.pdfreader.reader.screen.SelectionPopup
import com.rassul.pdfreader.ui.Icon
import com.rassul.pdfreader.ui.SheetItem
import com.rassul.pdfreader.ui.TopBar
import com.rassul.pdfreader.ui.Ui
import java.io.FileNotFoundException
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException

/*
 * КОНТРАКТ (владелец реализации — агент «reader-screen»).
 * Экран чтения: панели, поиск, оглавление, закладки, инструменты пометок, формы, сохранение.
 * Вспомогательные классы — в reader/screen/.
 */
class ReaderActivity : Activity() {
    companion object {
        /** Интент для открытия документа из главного экрана. */
        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, ReaderActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)

        private const val SAVE_POSITION_DELAY_MS = 1500L
        private const val THUMB_WIDTH = 300
    }

    // --- состояние, доступное помощникам из reader/screen ---
    internal lateinit var bg: Bg
    internal lateinit var view: PdfReaderView
    internal lateinit var search: SearchController
    internal var doc: PdfDoc? = null
        private set
    internal var password: String? = null
        private set
    /** Можно ли перезаписать исходный файл (FR-37). */
    internal var canWrite = false
        private set

    private lateinit var settings: SettingsStore
    private lateinit var recent: RecentStore
    private lateinit var thumbs: ThumbnailStore
    private lateinit var bookmarks: BookmarkStore
    private lateinit var positions: PositionStore
    private lateinit var drafts: DraftStore

    private lateinit var root: FrameLayout
    private lateinit var topContainer: LinearLayout
    private lateinit var topBar: TopBar
    private lateinit var bookmarkOff: View
    private lateinit var bookmarkOn: View
    private lateinit var saveAction: View
    private lateinit var bottomContainer: LinearLayout
    private lateinit var pageBar: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var annotBar: AnnotToolbar
    private lateinit var selection: SelectionPopup
    private lateinit var loadingOverlay: View
    private lateinit var busyOverlay: FrameLayout
    private lateinit var busyText: TextView
    private lateinit var errorOverlay: LinearLayout

    private lateinit var forms: FormController
    private lateinit var outline: OutlineController
    private lateinit var saver: SaveController

    private var openGen = 0
    private var panelsVisible = true
    private var editMode = false
    private var seekTracking = false
    private var discardOnExit = false
    private var passwordDialog: PasswordDialog? = null
    private var backCallback: OnBackInvokedCallback? = null
    private val savePositionTask = Runnable { savePosition() }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(this)
        bg = Bg(this)
        settings = SettingsStore(this)
        recent = RecentStore(this)
        thumbs = ThumbnailStore(this)
        bookmarks = BookmarkStore(this)
        positions = PositionStore(this)
        drafts = DraftStore(this)

        buildUi()
        registerBack()

        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val data = intent?.data
        if (data == null) {
            showError("Файл не указан", "Откройте PDF с главного экрана или через «Открыть с помощью».", null)
            return
        }
        takePersistable(intent, data)
        openDocument(data, null, null)
    }

    override fun onPause() {
        super.onPause()
        bg.main.removeCallbacks(savePositionTask)
        savePosition()
        saveDraft()
    }

    override fun onDestroy() {
        openGen++
        if (::search.isInitialized) search.cancel()
        passwordDialog?.dismiss()
        backCallback?.let { try { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) } catch (_: Exception) { } }
        val d = doc
        doc = null
        try { view.setDocument(null) } catch (_: Throwable) { }
        try { d?.close() } catch (_: Throwable) { }
        bg.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity не пересоздаётся (configChanges): PdfReaderView сам пересчитает раскладку
        // по onSizeChanged. Меню выделения привязано к старым координатам — скрываем.
        if (::selection.isInitialized && selection.visible) { selection.hide(); view.clearSelection() }
        bg.main.post { if (!isDestroyed && doc != null) updatePageUi(view.currentPage, doc!!.pageCount) }
    }

    @Deprecated("Используется, пока приложение не включило enableOnBackInvokedCallback")
    override fun onBackPressed() {
        handleBack()
    }

    @Deprecated("startActivityForResult — без androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SaveController.REQ_CREATE_COPY) {
            saver.onCopyTarget(if (resultCode == RESULT_OK) data?.data else null, data?.flags ?: 0)
        }
    }

    private fun registerBack() {
        // Работает, если в манифесте включён android:enableOnBackInvokedCallback; иначе система
        // вызывает onBackPressed (оба пути ведут в handleBack).
        val cb = OnBackInvokedCallback { handleBack() }
        backCallback = cb
        try {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } catch (_: Exception) { }
    }

    /** Назад: выделение → поиск → режим пометок → «Сохранить изменения?» → выход. */
    internal fun handleBack() {
        if (::selection.isInitialized && selection.visible) { selection.hide(); view.clearSelection(); return }
        if (::search.isInitialized && search.active) { search.close(); return }
        if (editMode) { setEditMode(false); return }
        if (doc == null) { finish(); return }
        if (saver.busy) { Ui.toast(this, "Идёт сохранение…"); return }
        if (isDirty()) {
            Ui.showChoice(this, "Сохранить изменения?",
                "В документе есть несохранённые пометки или заполненные поля.",
                listOf(
                    "Не сохранять" to { discardChangesAndFinish() },
                    "Отмена" to {},
                    "Сохранить…" to { saver.start(finishAfter = true) },
                ))
            return
        }
        finish()
    }

    private fun discardChangesAndFinish() {
        discardOnExit = true
        val key = docKey()
        if (key != null) bg.runIo { drafts.delete(key) }
        finish()
    }

    // ------------------------------------------------------------------ интерфейс

    private fun buildUi() {
        val c = Ui.colors
        root = FrameLayout(this).apply { setBackgroundColor(c.pageBg) }

        view = PdfReaderView(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- верхняя панель
        val searchBtn = Ui.iconButton(this, Icon.SEARCH, "Поиск") { if (doc != null) search.open(null) }
        val tocBtn = Ui.iconButton(this, Icon.TOC, "Оглавление") { if (doc != null) outline.show() }
        bookmarkOff = Ui.iconButton(this, Icon.BOOKMARK, "Добавить закладку") { toggleBookmark() }
        bookmarkOn = Ui.iconButton(this, Icon.BOOKMARK_FILLED, "Убрать закладку") { toggleBookmark() }.apply { visibility = View.GONE }
        val bookmarkBox = FrameLayout(this).apply { addView(bookmarkOff); addView(bookmarkOn) }
        saveAction = Ui.iconButton(this, Icon.SAVE, "Сохранить") { saver.start(finishAfter = false) }.apply { visibility = View.GONE }
        val moreBtn = Ui.iconButton(this, Icon.MORE, "Ещё") { showMoreSheet() }
        topBar = Ui.topBar(this, "Открываем…", null, onBack = { handleBack() },
            actions = listOf(searchBtn, tocBtn, bookmarkBox, saveAction, moreBtn))

        search = SearchController(this)
        topContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.surface)
            isClickable = true // не пропускать касания к странице
            addView(topBar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(search.panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(topContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // --- нижняя панель: индикатор страницы + ползунок; панель инструментов пометок
        pageLabel = TextView(this).apply {
            setTextColor(c.text)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = Ui.dp(this@ReaderActivity, 64)
            minHeight = Ui.dp(this@ReaderActivity, 48)
            setPadding(Ui.dp(this@ReaderActivity, 12), 0, Ui.dp(this@ReaderActivity, 4), 0)
            contentDescription = "Перейти на страницу"
            setOnClickListener { showGoToPage() }
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(c.primary)
            thumbTintList = ColorStateList.valueOf(c.primary)
            progressBackgroundTintList = ColorStateList.valueOf(c.outline)
            contentDescription = "Быстрая прокрутка по страницам"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val d = doc ?: return
                    if (!fromUser) return
                    pageLabel.text = "${progress + 1} / ${d.pageCount}"
                    view.goToPage(progress, animate = false)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { seekTracking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seekTracking = false
                    val d = doc ?: return
                    view.goToPage(sb.progress, animate = false)
                    updatePageUi(sb.progress, d.pageCount)
                }
            })
        }
        pageBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panelBackground(this@ReaderActivity, 24f)
            elevation = Ui.dp(this@ReaderActivity, 6).toFloat()
            setPadding(Ui.dp(this@ReaderActivity, 4), 0, Ui.dp(this@ReaderActivity, 8), 0)
            isClickable = true
            addView(pageLabel)
            addView(seek, LinearLayout.LayoutParams(0, Ui.dp(this@ReaderActivity, 48), 1f))
            visibility = View.INVISIBLE
        }
        annotBar = AnnotToolbar(this)
        bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = Ui.dp(this@ReaderActivity, 12)
            addView(pageBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = m; marginEnd = m
            })
            addView(annotBar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = m; marginEnd = m
            })
        }
        root.addView(bottomContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        selection = SelectionPopup(this)
        root.addView(selection.view)

        // --- оверлеи: загрузка, длительная операция, ошибка
        loadingOverlay = centeredColumn(c.pageBg).apply {
            addView(progress())
            addView(Ui.body(this@ReaderActivity, "Открываем документ…", secondary = true).apply {
                setPadding(0, Ui.dp(this@ReaderActivity, 16), 0, 0)
            })
        }
        root.addView(loadingOverlay, matchParent())

        busyText = Ui.body(this, "", secondary = false)
        busyOverlay = FrameLayout(this).apply {
            setBackgroundColor(c.scrim)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            val card = LinearLayout(this@ReaderActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.panelBackground(this@ReaderActivity, 20f)
                val p = Ui.dp(this@ReaderActivity, 20)
                setPadding(p, p, p * 3 / 2, p)
                addView(progress(), LinearLayout.LayoutParams(Ui.dp(this@ReaderActivity, 36), Ui.dp(this@ReaderActivity, 36)).apply {
                    marginEnd = Ui.dp(this@ReaderActivity, 16)
                })
                addView(busyText)
            }
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(busyOverlay, matchParent())

        errorOverlay = centeredColumn(c.bg).apply { visibility = View.GONE }
        root.addView(errorOverlay, matchParent())

        forms = FormController(this)
        outline = OutlineController(this)
        saver = SaveController(this)

        wireView()
        applyInsets()
        setContentView(root)

        window.insetsController?.let { ic ->
            val light = !c.isDark
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            ic.setSystemBarsAppearance(if (light) mask else 0, mask)
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun centeredColumn(bgColor: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(bgColor)
        isClickable = true
        val p = Ui.dp(this@ReaderActivity, 32)
        setPadding(p, p, p, p)
    }

    private fun progress() = ProgressBar(this).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(Ui.colors.primary)
    }

    private fun applyInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            // Отступы панелей не зависят от того, скрыты ли системные панели (нет «прыжков»).
            val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topContainer.setPadding(i.left, i.top, i.right, 0)
            bottomContainer.setPadding(i.left, 0, i.right, i.bottom + Ui.dp(this, 12))
            errorOverlay.setPadding(i.left + Ui.dp(this, 32), i.top + Ui.dp(this, 32), i.right + Ui.dp(this, 32), i.bottom + Ui.dp(this, 32))
            insets
        }
    }

    /** Нижняя граница верхней панели в координатах root (для меню выделения). */
    internal fun topOverlayBottom(): Int =
        if (topContainer.visibility == View.VISIBLE && topContainer.alpha > 0f) topContainer.bottom else 0

    private fun wireView() {
        view.onPageChanged = { page, count ->
            updatePageUi(page, count)
            bg.main.removeCallbacks(savePositionTask)
            bg.main.postDelayed(savePositionTask, SAVE_POSITION_DELAY_MS)
        }
        view.onSingleTap = {
            if (selection.visible) { selection.hide(); view.clearSelection() }
            else if (!editMode) setPanelsVisible(!panelsVisible)
        }
        view.onSelectionChanged = { sel ->
            if (sel == null || sel.text.isEmpty()) selection.hide() else selection.show(sel)
        }
        view.onAnnotationsChanged = { onAnnotationsEdited() }
        view.onNoteRequested = { page, x, y ->
            Ui.showPrompt(this, "Новая заметка", "Текст заметки", multiline = true, okText = "Добавить",
                validate = { if (it.isBlank()) "Введите текст заметки" else null }) { text ->
                view.addNote(page, x, y, text.trim())
                onAnnotationsEdited()
            }
        }
        view.onNoteTapped = { note -> showNoteActions(note) }
        view.onFormWidgetTapped = { page, widget ->
            if (editMode) Ui.toast(this, "Чтобы заполнить поле, выйдите из режима пометок")
            else forms.onWidgetTapped(page, widget)
        }
        view.onExternalLink = { uri -> confirmExternalLink(uri) }
    }

    private fun setPanelsVisible(show: Boolean) {
        if (panelsVisible == show) return
        panelsVisible = show
        Ui.fade(topContainer, show, true)
        Ui.fade(bottomContainer, show, false)
        val ic = window.insetsController ?: return
        if (show) {
            ic.show(WindowInsets.Type.systemBars())
        } else {
            ic.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ic.hide(WindowInsets.Type.systemBars())
        }
    }

    /** Поиск открыт/закрыт: панель поиска заменяет верхнюю панель. */
    internal fun onSearchShown(shown: Boolean) {
        if (shown) setPanelsVisible(true)
        topBar.view.visibility = if (shown) View.GONE else View.VISIBLE
        search.panel.visibility = if (shown) View.VISIBLE else View.GONE
    }

    internal fun setEditMode(on: Boolean) {
        if (doc == null && on) return
        editMode = on
        if (on) {
            setPanelsVisible(true)
            selection.hide()
            view.clearSelection()
            annotBar.select(Tool.PEN)
            pageBar.visibility = View.GONE
            annotBar.view.visibility = View.VISIBLE
        } else {
            view.tool = Tool.NONE
            annotBar.view.visibility = View.GONE
            pageBar.visibility = View.VISIBLE
        }
        annotBar.refresh()
    }

    // ------------------------------------------------------------------ загрузка

    private fun takePersistable(intent: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        val f = intent.flags
        if (f and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        val mode = f and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (mode == 0) return
        try { contentResolver.takePersistableUriPermission(uri, mode) } catch (_: Exception) { }
    }

    private fun openDocument(uri: Uri, pw: String?, restore: ReadingPosition?) {
        val gen = ++openGen
        errorOverlay.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        topBar.setTitle("Открываем…", null)
        bg.runIo {
            var opened: PdfDoc? = null
            var error: Throwable? = null
            try { opened = PdfDoc.open(applicationContext, uri, pw) } catch (t: Throwable) { error = t }
            if (bg.stopped) { try { opened?.close() } catch (_: Throwable) { }; return@runIo }
            bg.main.post {
                if (isDestroyed || gen != openGen) { try { opened?.close() } catch (_: Throwable) { }; return@post }
                val d = opened
                if (d != null) { password = pw; onDocOpened(d, restore) }
                else onOpenFailed(uri, error, restore)
            }
        }
    }

    private fun onOpenFailed(uri: Uri, error: Throwable?, restore: ReadingPosition?) {
        if (error is PasswordRequiredException) {
            loadingOverlay.visibility = View.GONE
            askPassword(uri, restore)
            return
        }
        // PdfDoc.open заворачивает SecurityException в IOException — смотрим и на причину.
        val noAccess = error is FileNotFoundException || error is SecurityException || error?.cause is SecurityException
        val (title, details) = when {
            noAccess ->
                "Нет доступа к файлу" to "Файл больше недоступен, откройте его заново."
            else -> when (error) {
            is FileNotFoundException, is SecurityException ->
                "Нет доступа к файлу" to "Файл больше недоступен, откройте его заново."
            is OutOfMemoryError -> "Не удалось открыть файл" to "Недостаточно памяти для этого документа."
            is java.io.IOException -> "Файл повреждён или это не PDF" to "Не удалось открыть файл: он повреждён или это не PDF."
            else -> "Файл повреждён или это не PDF" to "Не удалось открыть файл: он повреждён или это не PDF."
        } }
        val removable = noAccess
        showError(title, details, if (removable) uri else null)
    }

    private fun askPassword(uri: Uri, restore: ReadingPosition?) {
        topBar.setTitle("Документ защищён паролем", null)
        passwordDialog?.dismiss()
        lateinit var dlg: PasswordDialog
        dlg = PasswordDialog(this, onSubmit = { pw ->
            dlg.setBusy(true)
            val gen = ++openGen
            bg.runIo {
                var opened: PdfDoc? = null
                var error: Throwable? = null
                try { opened = PdfDoc.open(applicationContext, uri, pw) } catch (t: Throwable) { error = t }
                if (bg.stopped) { try { opened?.close() } catch (_: Throwable) { }; return@runIo }
                bg.main.post {
                    if (isDestroyed || gen != openGen) { try { opened?.close() } catch (_: Throwable) { }; return@post }
                    val d = opened
                    when {
                        d != null -> { dlg.dismiss(); passwordDialog = null; password = pw; onDocOpened(d, restore) }
                        error is PasswordRequiredException -> { dlg.setBusy(false); dlg.showError("Неверный пароль") }
                        else -> { dlg.dismiss(); passwordDialog = null; onOpenFailed(uri, error, restore) }
                    }
                }
            }
        }, onCancel = { passwordDialog = null; finish() })
        passwordDialog = dlg
        dlg.show()
    }

    private fun showError(title: String, details: String, removableUri: Uri?) {
        loadingOverlay.visibility = View.GONE
        topContainer.visibility = View.GONE
        bottomContainer.visibility = View.GONE
        errorOverlay.removeAllViews()
        errorOverlay.addView(Ui.icon(this, Icon.PDF_FILE, Ui.colors.textSecondary).let { dr ->
            View(this).apply { background = dr }
        }, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)).apply { bottomMargin = Ui.dp(this@ReaderActivity, 20) })
        errorOverlay.addView(Ui.title(this, title).apply { gravity = Gravity.CENTER })
        errorOverlay.addView(Ui.body(this, details, secondary = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@ReaderActivity, 8), 0, Ui.dp(this@ReaderActivity, 24))
        })
        errorOverlay.addView(Ui.button(this, "Назад", primary = true) { finish() })
        if (removableUri != null) {
            errorOverlay.addView(Ui.button(this, "Убрать из недавних", primary = false) {
                try { recent.remove(removableUri.toString()) } catch (_: Throwable) { }
                finish()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@ReaderActivity, 8)
            })
        }
        errorOverlay.visibility = View.VISIBLE
        errorOverlay.bringToFront()
    }

    private fun onDocOpened(d: PdfDoc, restore: ReadingPosition?) {
        doc = d
        loadingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.GONE
        topContainer.visibility = View.VISIBLE
        bottomContainer.visibility = View.VISIBLE
        pageBar.visibility = if (editMode) View.GONE else View.VISIBLE
        outline.reset()

        view.mode = settings.readingMode
        view.nightMode = settings.nightPages
        view.setDocument(d)

        val key = d.uri.toString()
        val count = d.pageCount
        try { recent.touch(d.uri, d.displayName, count) } catch (_: Throwable) { }
        val pos = restore ?: try { positions.get(key) } catch (_: Throwable) { null }
        if (pos != null && count > 0) view.restorePosition(pos.copy(page = pos.page.coerceIn(0, count - 1)))

        seek.max = (count - 1).coerceAtLeast(0)
        updatePageUi(if (pos != null) pos.page.coerceIn(0, (count - 1).coerceAtLeast(0)) else 0, count)
        updateDirty()

        makeThumbnail(d)
        checkWritable(d)
        checkFormType(d)
        offerDraft(d)
    }

    /** Переоткрыть документ (после сохранения) с сохранением позиции. */
    private fun reopen(uri: Uri) {
        val pos = try { if (doc != null) view.currentPosition() else null } catch (_: Throwable) { null }
        val old = doc
        doc = null
        if (search.active) search.close()
        selection.hide()
        view.annotations.clear()
        try { view.setDocument(null) } catch (_: Throwable) { }
        try { old?.close() } catch (_: Throwable) { }
        canWrite = false
        updateDirty()
        openDocument(uri, password, pos)
    }

    private fun makeThumbnail(d: PdfDoc) {
        val key = d.uri.toString()
        bg.runIo {
            try {
                if (thumbs.get(key) != null) return@runIo
                val bmp = d.worker.submit(Callable {
                    d.runOnPage(0) { p ->
                        val w = THUMB_WIDTH
                        val h = (w * p.height.toFloat() / p.width.coerceAtLeast(1)).toInt().coerceIn(1, w * 3)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }).get()
                thumbs.put(key, bmp)
            } catch (_: Throwable) { /* обложка не критична */ }
        }
    }

    /** FR-37: можно ли перезаписать исходный файл. */
    private fun checkWritable(d: PdfDoc) {
        val uri = d.uri
        bg.runIo {
            val ok = try { computeWritable(uri) } catch (_: Throwable) { false }
            bg.ui { if (doc === d) canWrite = ok }
        }
    }

    private fun computeWritable(uri: Uri): Boolean {
        when (uri.scheme) {
            "file" -> return uri.path?.let { java.io.File(it).canWrite() } ?: false
            "content" -> {}
            else -> return false
        }
        val granted = checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED ||
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!granted) return false
        if (DocumentsContract.isDocumentUri(this, uri)) {
            val flags = try {
                contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
                }
            } catch (_: Exception) { null }
            if (flags != null && flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0) return false
        }
        return true
    }

    /** FR-33: сообщение для XFA-форм. */
    private fun checkFormType(d: PdfDoc) {
        if (d.formType == PdfRenderer.PDF_FORM_TYPE_XFA_FULL) {
            Ui.toast(this, "Этот тип формы (XFA) не поддерживается, поля нельзя заполнить")
        }
    }

    // ------------------------------------------------------------------ позиция и черновик

    private fun docKey(): String? = doc?.uri?.toString()

    private fun savePosition() {
        val d = doc ?: return
        try {
            val p = view.currentPosition()
            positions.put(d.uri.toString(), p)
            recent.updateProgress(d.uri, view.currentPage, d.pageCount)
        } catch (_: Throwable) { }
    }

    private fun saveDraft() {
        val key = docKey() ?: return
        if (discardOnExit) return
        val items = view.annotations.all
        bg.runIo { try { drafts.save(key, items) } catch (_: Throwable) { } }
    }

    private fun offerDraft(d: PdfDoc) {
        val key = d.uri.toString()
        bg.runIo {
            val items = try { drafts.load(key) } catch (_: Throwable) { emptyList() }
            if (items.isEmpty()) return@runIo
            bg.ui {
                if (doc !== d || !view.annotations.isEmpty) return@ui
                Ui.showChoice(this, "Восстановить пометки?",
                    "С прошлого раза остались несохранённые пометки (${items.size} шт.).",
                    listOf(
                        "Удалить" to { bg.runIo { drafts.delete(key) } },
                        "Восстановить" to {
                            if (doc === d) {
                                val n = d.pageCount
                                items.filter { it.pageIndex in 0 until n }.forEach { view.annotations.add(it) }
                                view.invalidate()
                                onAnnotationsEdited()
                            }
                        },
                    ))
            }
        }
    }

    // ------------------------------------------------------------------ состояние панели

    private fun updatePageUi(page: Int, count: Int) {
        val d = doc ?: return
        val p = page.coerceIn(0, (count - 1).coerceAtLeast(0))
        topBar.setTitle(d.displayName, "стр. ${p + 1} из $count")
        if (!seekTracking) {
            pageLabel.text = "${p + 1} / $count"
            if (seek.progress != p) seek.progress = p
        }
        updateBookmarkIcon(p)
    }

    private fun updateBookmarkIcon(page: Int = view.currentPage) {
        val key = docKey() ?: return
        val has = try { bookmarks.has(key, page) } catch (_: Throwable) { false }
        bookmarkOn.visibility = if (has) View.VISIBLE else View.GONE
        bookmarkOff.visibility = if (has) View.GONE else View.VISIBLE
    }

    internal fun isDirty(): Boolean = !view.annotations.isEmpty || doc?.formsModified == true

    internal fun updateDirty() {
        saveAction.visibility = if (doc != null && isDirty()) View.VISIBLE else View.GONE
    }

    /** Пометки изменились (добавление, ластик, отмена/повтор, маркер). */
    internal fun onAnnotationsEdited() {
        annotBar.refresh()
        updateDirty()
    }

    internal fun showBusy(text: String) {
        busyText.text = text
        busyOverlay.visibility = View.VISIBLE
        busyOverlay.bringToFront()
    }

    internal fun hideBusy() {
        busyOverlay.visibility = View.GONE
    }

    /** Успешное сохранение: пометки уже в файле — очищаем сеанс и переоткрываем документ. */
    internal fun onSaved(old: PdfDoc, newUri: Uri, finishAfter: Boolean) {
        val oldKey = old.uri.toString()
        view.annotations.clear()
        if (doc === old) old.formsModified = false
        bg.runIo { drafts.delete(oldKey) }
        if (newUri != old.uri) {
            // позиция и закладки переезжают на копию
            try {
                val pos = view.currentPosition()
                positions.put(newUri.toString(), pos)
                for (b in bookmarks.list(oldKey)) bookmarks.add(newUri.toString(), b)
            } catch (_: Throwable) { }
        }
        updateDirty()
        if (finishAfter) { discardOnExit = true; finish(); return }
        reopen(newUri)
    }

    // ------------------------------------------------------------------ действия

    private fun toggleBookmark() {
        val d = doc ?: return
        val key = d.uri.toString()
        val p = view.currentPage
        if (bookmarks.has(key, p)) {
            bookmarks.remove(key, p)
            Ui.toast(this, "Закладка удалена")
        } else {
            bookmarks.add(key, Bookmark(p, "Страница ${p + 1}", System.currentTimeMillis()))
            Ui.toast(this, "Закладка добавлена")
        }
        updateBookmarkIcon(p)
    }

    private fun showBookmarks() {
        val d = doc ?: return
        val key = d.uri.toString()
        val list = bookmarks.list(key).sortedBy { it.page }
        if (list.isEmpty()) {
            Ui.toast(this, "Закладок пока нет — нажмите значок закладки в верхней панели")
            return
        }
        var dlg: Dialog? = null
        val items = list.map { b ->
            SheetItem(
                text = b.title, icon = Icon.BOOKMARK_FILLED, secondary = "стр. ${b.page + 1}",
                onLongClick = { dlg?.dismiss(); bookmarkActions(b) },
            ) {
                dlg?.dismiss()
                if (b.page in 0 until d.pageCount) view.goToPage(b.page)
            }
        }
        dlg = Ui.showListSheet(this, "Закладки · удерживайте для изменения", items)
    }

    private fun bookmarkActions(b: Bookmark) {
        val key = docKey() ?: return
        var dlg: Dialog? = null
        dlg = Ui.showListSheet(this, b.title, listOf(
            SheetItem("Переименовать", icon = Icon.EDIT) {
                dlg?.dismiss()
                Ui.showPrompt(this, "Название закладки", "Название", initial = b.title, okText = "Сохранить",
                    validate = { if (it.isBlank()) "Введите название" else null }) { t ->
                    bookmarks.remove(key, b.page)
                    bookmarks.add(key, b.copy(title = t.trim()))
                    showBookmarks()
                }
            },
            SheetItem("Удалить", icon = Icon.DELETE) {
                dlg?.dismiss()
                Ui.showChoice(this, "Удалить закладку?", "«${b.title}», стр. ${b.page + 1}", listOf(
                    "Отмена" to {},
                    "Удалить" to {
                        bookmarks.remove(key, b.page)
                        updateBookmarkIcon()
                        Ui.toast(this, "Закладка удалена")
                    },
                ))
            },
        ))
    }

    private fun showGoToPage() {
        val d = doc ?: return
        val n = d.pageCount
        Ui.showPrompt(this, "Перейти на страницу", "Номер страницы (1–$n)", inputNumber = true, okText = "Перейти",
            validate = { s ->
                val v = s.trim().toIntOrNull()
                if (v == null || v < 1 || v > n) "Введите число от 1 до $n" else null
            }) { s ->
            val p = s.trim().toInt() - 1
            view.goToPage(p)
            updatePageUi(p, n)
        }
    }

    private fun showMoreSheet() {
        val d = doc ?: return
        var dlg: Dialog? = null
        fun item(text: String, icon: Icon, secondary: String? = null, action: () -> Unit) =
            SheetItem(text, icon = icon, secondary = secondary) { dlg?.dismiss(); action() }
        val paged = view.mode == ReadingMode.PAGED
        val dirty = isDirty()
        val items = listOf(
            item("Закладки", Icon.BOOKMARK) { showBookmarks() },
            item("Перейти на страницу", Icon.PAGE_GOTO, "стр. ${view.currentPage + 1} из ${d.pageCount}") { showGoToPage() },
            if (paged) item("Режим: Лента", Icon.SCROLL_VERTICAL, "Сейчас — Книга (листание по страницам)") { setReadingMode(ReadingMode.CONTINUOUS) }
            else item("Режим: Книга", Icon.PAGES_HORIZONTAL, "Сейчас — Лента (вертикальная прокрутка)") { setReadingMode(ReadingMode.PAGED) },
            item(if (view.nightMode) "Выключить ночной режим" else "Ночной режим страниц",
                if (view.nightMode) Icon.SUN else Icon.MOON,
                if (view.nightMode) "Сейчас включён" else "Тёмные страницы, светлый текст") { toggleNightMode() },
            item("Пометки", Icon.PEN, if (editMode) "Режим пометок включён" else "Перо, маркер, ластик, заметки") { setEditMode(!editMode) },
            item("Сохранить", Icon.SAVE, if (dirty) "Есть несохранённые изменения" else "Нет изменений") {
                if (dirty) saver.start(finishAfter = false) else Ui.toast(this, "Нет несохранённых изменений")
            },
        )
        dlg = Ui.showListSheet(this, d.displayName, items)
    }

    private fun setReadingMode(m: ReadingMode) {
        val pos = try { view.currentPosition() } catch (_: Throwable) { null }
        view.mode = m
        settings.readingMode = m
        if (pos != null) view.restorePosition(pos)
        Ui.toast(this, if (m == ReadingMode.PAGED) "Режим «Книга»" else "Режим «Лента»")
    }

    private fun toggleNightMode() {
        val on = !view.nightMode
        view.nightMode = on
        settings.nightPages = on
        view.invalidate()
    }

    private fun showNoteActions(note: NoteAnnotation) {
        var dlg: Dialog? = null
        dlg = Ui.showListSheet(this, "Заметка", listOf(
            SheetItem(note.text.ifBlank { "(пустая заметка)" }, icon = Icon.NOTE, secondary = "стр. ${note.pageIndex + 1}") {
                dlg?.dismiss(); editNote(note)
            },
            SheetItem("Изменить", icon = Icon.EDIT) { dlg?.dismiss(); editNote(note) },
            SheetItem("Удалить", icon = Icon.DELETE) {
                dlg?.dismiss()
                if (view.annotations.remove(note)) { view.refreshAnnotations(); onAnnotationsEdited() }
            },
        ))
    }

    private fun editNote(note: NoteAnnotation) {
        Ui.showPrompt(this, "Изменить заметку", "Текст заметки", initial = note.text, multiline = true, okText = "Сохранить",
            validate = { if (it.isBlank()) "Введите текст заметки" else null }) { text ->
            if (view.annotations.replace(note, note.copy(text = text.trim()))) {
                view.refreshAnnotations()
                onAnnotationsEdited()
            }
        }
    }

    private fun confirmExternalLink(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https", "mailto", "tel")) {
            Ui.toast(this, "Этот тип ссылки не поддерживается")
            return
        }
        Ui.showChoice(this, "Открыть ссылку в браузере?", uri.toString(), listOf(
            "Отмена" to {},
            "Открыть" to {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    Ui.toast(this, "Нет приложения, которое может открыть эту ссылку")
                }
            },
        ))
    }
}
