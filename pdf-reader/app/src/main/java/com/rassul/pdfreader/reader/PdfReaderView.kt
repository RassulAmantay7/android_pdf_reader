package com.rassul.pdfreader.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.RenderParams
import android.graphics.pdf.models.FormWidgetInfo
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.rassul.pdfreader.core.annot.AnnotationSession
import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.core.annot.PtPoint
import com.rassul.pdfreader.core.annot.PtRect
import com.rassul.pdfreader.core.geometry.AnnotationGeometry
import com.rassul.pdfreader.core.geometry.Box
import com.rassul.pdfreader.core.geometry.ContinuousLayout
import com.rassul.pdfreader.core.geometry.NightColorMatrix
import com.rassul.pdfreader.core.geometry.PagedGeometry
import com.rassul.pdfreader.core.geometry.RenderGeometry
import com.rassul.pdfreader.core.geometry.Viewport
import com.rassul.pdfreader.core.geometry.clampZoom
import com.rassul.pdfreader.core.geometry.doubleTapTargetZoom
import com.rassul.pdfreader.core.geometry.scrollToShow
import com.rassul.pdfreader.data.ReadingMode
import com.rassul.pdfreader.data.ReadingPosition
import com.rassul.pdfreader.reader.view.BaseBitmap
import com.rassul.pdfreader.reader.view.OverlayPainter
import com.rassul.pdfreader.reader.view.PageBitmapCache
import com.rassul.pdfreader.reader.view.PageExtras
import com.rassul.pdfreader.reader.view.PageExtrasCache
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * КОНТРАКТ (владелец реализации — агент «reader-view»). Публичное API ниже использует
 * ReaderActivity — НЕ МЕНЯТЬ сигнатуры (добавлять можно).
 * Все прямоугольники/точки «...Pt» — в координатах страницы (пункты, начало слева сверху),
 * как у PdfRenderer.
 *
 * Устройство:
 *  - вся геометрия (раскладка, экран↔контент↔страница, ограничения, участки рендера) — в
 *    :core (com.rassul.pdfreader.core.geometry), здесь только вызовы;
 *  - базовые растры страниц «по ширине» (LRU по памяти), при увеличении > 1.3× поверх —
 *    резкий участок видимой области («патч»), отрисованный через Page.render с Matrix;
 *  - весь доступ к PdfRenderer — на doc.worker, результаты возвращаются post{} в UI-поток.
 */

/** Совпадение поиска: страница и прямоугольники (пункты). */
data class SearchHit(val page: Int, val rectsPt: List<RectF>)

/** Текущее выделение текста. anchorOnScreen — прямоугольник в координатах View для показа меню. */
data class TextSelection(val page: Int, val text: String, val rectsPt: List<RectF>, val anchorOnScreen: RectF)

enum class Tool { NONE, PEN, HIGHLIGHTER, ERASER, NOTE }

class PdfReaderView(context: Context) : View(context) {

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val SHARP_THRESHOLD = 1.3f
        const val GAP_DP = 8f
        const val MARGIN_DP = 8f
        const val HIGHLIGHTER_OPACITY = 0.35f
        const val SHARP_DELAY_MS = 120L
        const val ZOOM_ANIM_MS = 250L
        const val FLIP_ANIM_MS = 260L
        /** «Мягкий» белый для незагруженной страницы. */
        const val PLACEHOLDER = 0xFFFFFFFF.toInt()
        /** В ночном режиме фон страницы-заглушки = белый через ту же матрицу (≈ чёрный). */
        const val PLACEHOLDER_NIGHT = 0xFF000000.toInt()
    }

    // ---------------------------------------------------------------- публичные свойства

    var mode: ReadingMode = ReadingMode.CONTINUOUS
        set(v) {
            if (field == v) return
            val page = currentPage
            field = v
            cancelMotion()
            flipRaw = 0f
            pagedIndex = page.coerceAtLeast(0)
            relayout(ReadingPosition(page, 0f, 1f))
        }

    /** Ночной режим страниц (инверсия яркости с сохранением оттенка). */
    var nightMode: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            pagePaint.colorFilter = if (v) nightFilter else null
            invalidate()
        }

    var tool: Tool = Tool.NONE
        set(v) {
            if (field == v) return
            field = v
            strokePoints = null; strokePage = -1
            if (dragMode == Drag.STROKE || dragMode == Drag.ERASE) dragMode = Drag.NONE
            invalidate()
        }

    var penColor: Int = 0xFF2962FF.toInt()
    var penWidthPt: Float = 2f
    var highlighterColor: Int = 0xFFFFEB3B.toInt()
    /** Толщина маркера (инструмент HIGHLIGHTER), пункты. */
    var highlighterWidthPt: Float = 12f
    /** Цвет заметок, создаваемых addNote. */
    var noteColor: Int = OverlayPainter.NOTE_COLOR_DEFAULT

    /** Цвет фона между страницами и вокруг них. */
    var gapColor: Int = 0xFF2B2B2B.toInt()
        set(v) { field = v; invalidate() }

    /** Пометки текущего сеанса (рисуются поверх страниц). */
    val annotations: AnnotationSession = AnnotationSession()

    // ---------------------------------------------------------------- обратные вызовы (UI-поток)
    var onPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null
    /** Одиночный тап по странице без инструмента/ссылки/формы — показать/скрыть панели. */
    var onSingleTap: (() -> Unit)? = null
    var onSelectionChanged: ((TextSelection?) -> Unit)? = null
    var onAnnotationsChanged: (() -> Unit)? = null
    /** Тап с инструментом NOTE: Activity спрашивает текст и вызывает addNote. */
    var onNoteRequested: ((page: Int, xPt: Float, yPt: Float) -> Unit)? = null
    /** Тап по существующей заметке сеанса — редактировать/удалить. */
    var onNoteTapped: ((note: NoteAnnotation) -> Unit)? = null
    /** Тап по полю формы (tool == NONE). */
    var onFormWidgetTapped: ((page: Int, widget: FormWidgetInfo) -> Unit)? = null
    /** Внешняя ссылка (http/mailto…). */
    var onExternalLink: ((Uri) -> Unit)? = null

    // ---------------------------------------------------------------- состояние

    private val density = resources.displayMetrics.density
    private val gapPx get() = GAP_DP * density
    private val marginPx get() = MARGIN_DP * density

    private var doc: PdfDoc? = null
    private var docSerial = 0
    private var pageCount = 0
    private var widthsPt = FloatArray(0)
    private var heightsPt = FloatArray(0)

    private var layoutC: ContinuousLayout? = null
    private var vp = Viewport(0f, 0f, 0f, 0f, 1f, 0f, 0f)
    private var pendingPosition: ReadingPosition? = null

    // PAGED
    private var pagedIndex = 0
    private var fitW = 0f
    private var fitH = 0f
    /** Смещение перелистывания (до «резины»), px. > 0 — тянут к предыдущей странице. */
    private var flipRaw = 0f
    private var flipAnimator: ValueAnimator? = null
    /** Во время анимации flipRaw — уже итоговое смещение (без повторной «резины»). */
    private var flipAnimating = false
    private var dragStartAtLeft = false
    private var dragStartAtRight = false

    private var lastReportedPage = -1

    // рендер
    private val cacheBytes = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val baseCache = PageBitmapCache(cacheBytes)
    private val baseJobs = HashMap<Int, Future<*>>()
    @Volatile private var wantedPages: Set<Int> = emptySet()
    private var globalGen = 0
    private val pageGen = HashMap<Int, Int>()

    private class Patch(val page: Int, val bitmap: Bitmap, val ptBox: Box, val stamp: Long, val zoom: Float)
    private val patches = HashMap<Int, Patch>()
    private var sharpJob: Future<*>? = null
    @Volatile private var sharpSeq = 0
    private val sharpRunnable = Runnable { requestSharp() }

    private val renderParams: RenderParams by lazy {
        // RENDER_MODE_FOR_DISPLAY + показ аннотаций выделения и текстовых заметок, уже
        // имеющихся в файле (FR-30). Флаги — RenderParams.FLAG_RENDER_*_ANNOTATIONS (API 35).
        RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
            .setRenderFlags(RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS)
            .build()
    }

    private val nightFilter = ColorMatrixColorFilter(ColorMatrix(NightColorMatrix.create()))
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val layerPaint = Paint()
    private val overlay = OverlayPainter()
    private val tmpRectF = RectF()

    // ссылки/формы
    private val extras = PageExtrasCache()
    private val extrasJobs = HashMap<Int, MutableList<(PageExtras) -> Unit>>()

    // поиск
    private var searchHits: List<SearchHit> = emptyList()
    private var searchCurrent: SearchHit? = null

    // выделение
    private var selection: TextSelection? = null
    private var selStart: SelectionBoundary? = null
    private var selStop: SelectionBoundary? = null
    private var selAnchorPt: RectF? = null  // слово, с которого началось выделение (пункты)
    private var selInFlight = false
    private var selPending: (() -> Unit)? = null
    private var selSerial = 0

    // жесты
    private enum class Drag { NONE, SELECT_EXTEND, HANDLE_START, HANDLE_END, STROKE, ERASE }
    private var dragMode = Drag.NONE
    private var strokePoints: ArrayList<PtPoint>? = null
    private var strokePage = -1
    private val strokePath = Path()
    private var lastFocusX = Float.NaN
    private var lastFocusY = Float.NaN
    private var velocity: VelocityTracker? = null
    private var flingHandled = false
    private val scroller = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null
    private var gestureActive = false

    private val handleRadius get() = 11f * density

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ================================================================ документ

    /** Назначить документ (null — очистить). Вызывать из UI-потока. */
    fun setDocument(doc: PdfDoc?) {
        val old = this.doc
        if (old === doc) return
        old?.onPageSizesChanged = null
        cancelMotion()
        cancelAllJobs()
        baseCache.clear()
        clearPatches()
        extras.clear(); extrasJobs.clear()
        pageGen.clear(); globalGen++
        searchHits = emptyList(); searchCurrent = null
        if (selection != null) clearSelection()
        if (doc == null || old == null || old.uri != doc.uri) annotations.clear()
        docSerial++
        this.doc = doc
        pageCount = doc?.pageCount ?: 0
        pagedIndex = 0; flipRaw = 0f
        lastReportedPage = -1
        readSizes()
        doc?.onPageSizesChanged = {
            if (this.doc === doc) { val p = currentPosition(); readSizes(); relayout(p) }
        }
        val pos = pendingPosition ?: ReadingPosition(0, 0f, 1f)
        pendingPosition = null
        relayout(pos)
    }

    private fun readSizes() {
        val d = doc
        widthsPt = FloatArray(pageCount) { d!!.pageSize(it).width }
        heightsPt = FloatArray(pageCount) { d!!.pageSize(it).height }
    }

    private val ready: Boolean get() = doc != null && pageCount > 0 && width > 0 && height > 0

    /** Пересчитать раскладку под текущие размеры и режим и встать на позицию pos. */
    private fun relayout(pos: ReadingPosition) {
        if (!ready) { pendingPosition = pos; layoutC = null; invalidate(); return }
        val w = width.toFloat(); val h = height.toFloat()
        if (mode == ReadingMode.CONTINUOUS) {
            val l = ContinuousLayout(widthsPt, heightsPt, w, gapPx, marginPx)
            layoutC = l
            vp = Viewport(w, h, l.contentWidth, l.contentHeight, clampZoom(pos.zoom, MIN_ZOOM, MAX_ZOOM), 0f, 0f)
            val y = l.contentYFor(pos.page, pos.offsetFraction) * vp.zoom
            // По горизонтали — центр, если увеличено (сохранять X между поворотами бессмысленно).
            val x = (vp.contentW * vp.zoom - w) / 2f
            vp = vp.withScroll(x, y).clamped()
        } else {
            layoutC = null
            pagedIndex = pos.page.coerceIn(0, pageCount - 1)
            setupPagedFit()
            vp = Viewport(w, h, fitW, fitH, clampZoom(pos.zoom, MIN_ZOOM, MAX_ZOOM), 0f, 0f)
            vp = vp.withScroll((fitW * vp.zoom - w) / 2f, pos.offsetFraction * fitH * vp.zoom).clamped()
        }
        afterViewportChanged()
    }

    private fun setupPagedFit() {
        val (fw, fh, _) = PagedGeometry.fit(widthsPt[pagedIndex], heightsPt[pagedIndex], width.toFloat(), height.toFloat(), marginPx)
        fitW = fw; fitH = fh
    }

    // ================================================================ навигация

    /** Текущая страница (0-based): самая видимая / показанная. */
    val currentPage: Int
        get() {
            if (pageCount == 0) return 0
            if (mode == ReadingMode.PAGED) return pagedIndex
            val l = layoutC ?: return pendingPosition?.page ?: 0
            val vis = vp.visibleContent
            return l.mostVisiblePage(vis.top, vis.bottom).coerceAtLeast(0)
        }

    /** Число страниц документа (0, если документа нет). */
    val documentPageCount: Int get() = pageCount

    /** Текущий масштаб (1 = «по ширине» / «страница целиком»). */
    val zoom: Float get() = vp.zoom

    fun goToPage(index: Int, animate: Boolean = true) {
        if (pageCount == 0) return
        val i = index.coerceIn(0, pageCount - 1)
        if (!ready) { pendingPosition = ReadingPosition(i, 0f, 1f); return }
        cancelMotion()
        if (mode == ReadingMode.PAGED) {
            if (i == pagedIndex) return
            if (animate && abs(i - pagedIndex) == 1) animateFlip(if (i > pagedIndex) 1 else -1)
            else showPagedIndex(i)
            return
        }
        val l = layoutC ?: return
        val targetY = Viewport.clampAxis(l.contentYFor(i, 0f) * vp.zoom, vp.contentH * vp.zoom, vp.viewH)
        smoothScrollTo(vp.scrollX, targetY, animate)
    }

    /** Перейти так, чтобы точка страницы была видна (для поиска и ссылок). */
    fun goToPagePoint(index: Int, xPt: Float, yPt: Float) {
        if (pageCount == 0) return
        val i = index.coerceIn(0, pageCount - 1)
        if (!ready) { pendingPosition = ReadingPosition(i, (yPt / heightsPt[i]).coerceIn(0f, 1f), 1f); return }
        cancelMotion()
        val margin = 48f * density
        if (mode == ReadingMode.PAGED) {
            if (i != pagedIndex) showPagedIndex(i)
            val s = fitW / widthsPt[i]
            val sx = scrollToShow(vp.scrollX, xPt * s * vp.zoom, vp.viewW, margin)
            val sy = scrollToShow(vp.scrollY, yPt * s * vp.zoom, vp.viewH, margin)
            setViewport(vp.withScroll(sx, sy))
            return
        }
        val l = layoutC ?: return
        val (cx, cy) = l.pointToContent(i, xPt, yPt)
        val sx = if (vp.zoom > 1.01f) scrollToShow(vp.scrollX, cx * vp.zoom, vp.viewW, margin) else vp.scrollX
        val sy = scrollToShow(vp.scrollY, cy * vp.zoom, vp.viewH, margin)
        smoothScrollTo(sx, sy, true)
    }

    private fun smoothScrollTo(x: Float, y: Float, animate: Boolean) {
        val target = vp.withScroll(x, y).clamped()
        val dy = target.scrollY - vp.scrollY; val dx = target.scrollX - vp.scrollX
        if (animate && abs(dy) < vp.viewH * 3 && (abs(dx) > 1 || abs(dy) > 1)) {
            scroller.startScroll(vp.scrollX.roundToInt(), vp.scrollY.roundToInt(), dx.roundToInt(), dy.roundToInt(), 350)
            postInvalidateOnAnimation()
        } else setViewport(target)
    }

    fun currentPosition(): ReadingPosition {
        if (pageCount == 0) return ReadingPosition(0, 0f, 1f)
        if (!ready || (mode == ReadingMode.CONTINUOUS && layoutC == null)) return pendingPosition ?: ReadingPosition(0, 0f, 1f)
        return if (mode == ReadingMode.PAGED) {
            val f = if (fitH > 0f) (vp.scrollY / (fitH * vp.zoom)).coerceIn(0f, 1f) else 0f
            ReadingPosition(pagedIndex, f, vp.zoom)
        } else {
            val (p, f) = layoutC!!.positionAt(vp.toContentY(0f))
            ReadingPosition(p, f, vp.zoom)
        }
    }

    fun restorePosition(p: ReadingPosition) {
        if (pageCount == 0 && doc == null) { pendingPosition = p; return }
        cancelMotion()
        val safe = ReadingPosition(p.page.coerceIn(0, maxOf(0, pageCount - 1)), p.offsetFraction, p.zoom)
        relayout(safe)
    }

    private fun showPagedIndex(i: Int) {
        flipAnimator?.cancel(); flipAnimator = null
        flipAnimating = false
        flipRaw = 0f
        pagedIndex = i
        setupPagedFit()
        vp = Viewport(width.toFloat(), height.toFloat(), fitW, fitH, 1f, 0f, 0f).clamped()
        clearPatches()
        afterViewportChanged()
    }

    // ================================================================ поиск

    fun setSearchHits(hits: List<SearchHit>, current: Int) {
        searchHits = hits
        searchCurrent = hits.getOrNull(current)
        invalidate()
    }

    fun clearSearch() {
        searchHits = emptyList(); searchCurrent = null
        invalidate()
    }

    // ================================================================ выделение

    fun clearSelection() {
        val had = selection != null || selStart != null
        selection = null; selStart = null; selStop = null; selAnchorPt = null
        selSerial++; selPending = null
        if (dragMode == Drag.SELECT_EXTEND || dragMode == Drag.HANDLE_START || dragMode == Drag.HANDLE_END) dragMode = Drag.NONE
        invalidate()
        if (had) onSelectionChanged?.invoke(null)
    }

    /** Превратить текущее выделение в HighlightAnnotation (цвет highlighterColor). */
    fun highlightSelection() {
        val s = selection ?: return
        if (s.rectsPt.isEmpty()) return
        annotations.add(HighlightAnnotation(s.page, s.rectsPt.map { PtRect(it.left, it.top, it.right, it.bottom) }, highlighterColor, s.text))
        clearSelection()
        annotationsChanged()
    }

    /** Запросить выделение у PdfRenderer (worker). Не более одного запроса одновременно, последний — в очереди. */
    private fun requestSelection(page: Int, start: SelectionBoundary, stop: SelectionBoundary, initial: Boolean) {
        val d = doc ?: return
        val serial = selSerial
        val run: () -> Unit = {
            selInFlight = true
            val f = submit(d) {
                val ps: PageSelection? = try { d.runOnPage(page) { it.selectContent(start, stop) } } catch (_: Exception) { null }
                post {
                    selInFlight = false
                    if (doc === d && serial == selSerial) applySelection(page, ps, initial)
                    val next = selPending; selPending = null
                    next?.invoke()
                }
            }
            if (f == null) selInFlight = false
        }
        if (selInFlight) selPending = run else run()
    }

    private fun applySelection(page: Int, ps: PageSelection?, initial: Boolean) {
        val contents = ps?.selectedTextContents.orEmpty()
        val text = contents.joinToString("") { it.text ?: "" }
        val rects = contents.flatMap { it.bounds.orEmpty() }.filter { it.width() > 0f && it.height() > 0f }
        if (ps == null || rects.isEmpty() || text.isBlank()) {
            if (initial) clearSelection()
            return
        }
        selStart = ps.start; selStop = ps.stop
        if (initial) {
            val u = RectF(rects[0]); for (r in rects) u.union(r)
            selAnchorPt = u
        }
        selection = TextSelection(page, text, rects, anchorFor(page, rects))
        invalidate()
        onSelectionChanged?.invoke(selection)
    }

    private fun anchorFor(page: Int, rects: List<RectF>): RectF {
        val box = pageScreenBox(page) ?: return RectF()
        val s = box.width / widthsPt[page]
        val u = RectF(rects[0]); for (r in rects) u.union(r)
        return RectF(box.left + u.left * s, box.top + u.top * s, box.left + u.right * s, box.top + u.bottom * s)
    }

    private fun refreshSelectionAnchor() {
        val s = selection ?: return
        val a = anchorFor(s.page, s.rectsPt)
        if (a != s.anchorOnScreen) {
            selection = s.copy(anchorOnScreen = a)
            onSelectionChanged?.invoke(selection)
        }
    }

    // ================================================================ пометки

    fun addNote(page: Int, xPt: Float, yPt: Float, text: String) {
        if (page !in 0 until pageCount) return
        val half = NoteAnnotation.ICON_SIZE_PT / 2f
        val x = (xPt - half).coerceIn(0f, maxOf(0f, widthsPt[page] - NoteAnnotation.ICON_SIZE_PT))
        val y = (yPt - half).coerceIn(0f, maxOf(0f, heightsPt[page] - NoteAnnotation.ICON_SIZE_PT))
        annotations.add(NoteAnnotation(page, PtPoint(x, y), text, noteColor))
        annotationsChanged()
    }

    fun undo(): Boolean {
        val r = annotations.undo()
        if (r) annotationsChanged()
        return r
    }

    fun redo(): Boolean {
        val r = annotations.redo()
        if (r) annotationsChanged()
        return r
    }

    /** Перерисовать пометки после прямой правки [annotations] снаружи (replace/remove заметки). */
    fun refreshAnnotations() = annotationsChanged()

    private fun annotationsChanged() {
        overlay.retain(annotations.all)
        invalidate()
        onAnnotationsChanged?.invoke()
    }

    // ================================================================ сброс страниц

    /** Перерисовать страницу заново из PdfRenderer (после правки формы). */
    fun invalidatePage(index: Int) {
        pageGen[index] = (pageGen[index] ?: 0) + 1
        extras.remove(index)
        baseJobs.remove(index)?.cancel(false)
        scheduleRenders()
        scheduleSharp(0)
        invalidate()
    }

    /** Сбросить кэш страниц целиком (после смены документа/сохранения). */
    fun invalidateAllPages() {
        globalGen++
        extras.clear()
        cancelAllJobs()
        if (doc != null) { readSizes(); relayout(currentPosition()) } else invalidate()
    }

    private fun stampOf(page: Int): Long = (globalGen.toLong() shl 32) or ((pageGen[page] ?: 0).toLong() and 0xffffffffL)

    // ================================================================ геометрия страниц на экране

    /** Прямоугольник страницы на экране или null, если она сейчас не раскладывается. */
    private fun pageScreenBox(i: Int): Box? {
        if (!ready || i !in 0 until pageCount) return null
        if (mode == ReadingMode.CONTINUOUS) {
            val l = layoutC ?: return null
            return vp.toScreen(l.pageBox(i))
        }
        val off = flipOffset()
        return when (i) {
            pagedIndex -> vp.toScreen(Box(0f, 0f, fitW, fitH)).offset(off, 0f)
            pagedIndex + 1, pagedIndex - 1 -> {
                val (fw, fh, _) = PagedGeometry.fit(widthsPt[i], heightsPt[i], vp.viewW, vp.viewH, marginPx)
                val slot = off + (if (i > pagedIndex) 1 else -1) * (vp.viewW + gapPx)
                PagedGeometry.slotBox(fw, fh, vp.viewW, vp.viewH, slot)
            }
            else -> null
        }
    }

    private fun flipOffset(): Float {
        if (flipRaw == 0f) return 0f
        if (flipAnimating) return flipRaw
        val hasNeighbor = if (flipRaw < 0f) pagedIndex < pageCount - 1 else pagedIndex > 0
        val limit = vp.viewW + gapPx
        return if (hasNeighbor) flipRaw.coerceIn(-limit, limit) else PagedGeometry.rubberBand(flipRaw, vp.viewW)
    }

    private fun visiblePages(): IntRange {
        if (!ready) return IntRange.EMPTY
        if (mode == ReadingMode.CONTINUOUS) {
            val l = layoutC ?: return IntRange.EMPTY
            val v = vp.visibleContent
            return l.visibleRange(v.top, v.bottom)
        }
        val off = flipOffset()
        return when {
            off < 0f && pagedIndex < pageCount - 1 -> pagedIndex..pagedIndex + 1
            off > 0f && pagedIndex > 0 -> pagedIndex - 1..pagedIndex
            else -> pagedIndex..pagedIndex
        }
    }

    /** Экран → (страница, x pt, y pt). Точки в зазоре не относятся ни к какой странице. */
    private fun screenToPage(sx: Float, sy: Float, slackPx: Float = 0f): Triple<Int, Float, Float>? {
        for (i in visiblePages()) {
            val b = pageScreenBox(i) ?: continue
            if (sx >= b.left - slackPx && sx <= b.right + slackPx && sy >= b.top - slackPx && sy <= b.bottom + slackPx) {
                val s = b.width / widthsPt[i]
                val (x, y) = RenderGeometry.screenToPage(sx, sy, b.left, b.top, s, 0f, 0f)
                return Triple(i, x, y)
            }
        }
        return null
    }

    private fun pxPerPt(i: Int): Float = (pageScreenBox(i)?.width ?: 0f) / widthsPt[i]

    // ================================================================ изменение окна просмотра

    private fun setViewport(v: Viewport) {
        vp = v.clamped()
        afterViewportChanged()
    }

    private fun afterViewportChanged() {
        invalidate()
        if (!ready) return
        val cp = currentPage
        if (cp != lastReportedPage) {
            lastReportedPage = cp
            contentDescription = "Страница ${cp + 1} из $pageCount"
            onPageChanged?.invoke(cp, pageCount)
        }
        scheduleRenders()
        scheduleSharp(SHARP_DELAY_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Позицию (страница + доля + масштаб) берём из старой раскладки, затем раскладываем заново.
        val pos = if (oldw > 0 && oldh > 0 && doc != null && (layoutC != null || mode == ReadingMode.PAGED)) currentPosition()
        else pendingPosition ?: ReadingPosition(0, 0f, 1f)
        pendingPosition = null
        cancelMotion()
        clearPatches()
        relayout(pos)
    }

    // ================================================================ рендер

    private fun submit(d: PdfDoc, task: () -> Unit): Future<*>? = try {
        d.worker.submit { try { task() } catch (_: Throwable) {} }
    } catch (_: Exception) { null } // worker остановлен (документ закрыт)

    private fun baseTargetWidth(i: Int): Float =
        if (mode == ReadingMode.CONTINUOUS) (layoutC?.pageBox(i)?.width ?: 0f)
        else PagedGeometry.fit(widthsPt[i], heightsPt[i], width.toFloat(), height.toFloat(), marginPx).first

    private fun maxBasePixels(): Long = maxOf(1L, minOf(cacheBytes / 4L / 3L, 16L * 1024 * 1024))

    private fun needsBase(i: Int): Boolean {
        val e = baseCache[i] ?: return true
        if (e.stamp != stampOf(i)) return true
        val want = RenderGeometry.baseBitmapSize(widthsPt[i], heightsPt[i], baseTargetWidth(i), maxBasePixels())
        return abs(e.bitmap.width - want.width) > 1
    }

    /** Рендер базовых растров для видимых страниц и соседних (±1), отмена ненужных. */
    private fun scheduleRenders() {
        val d = doc ?: return
        if (!ready) return
        val vis = visiblePages()
        if (vis.isEmpty()) return
        val wanted = LinkedHashSet<Int>()
        for (i in vis) wanted.add(i)
        if (vis.first - 1 >= 0) wanted.add(vis.first - 1)
        if (vis.last + 1 < pageCount) wanted.add(vis.last + 1)
        if (mode == ReadingMode.PAGED) { if (pagedIndex > 0) wanted.add(pagedIndex - 1); if (pagedIndex + 1 < pageCount) wanted.add(pagedIndex + 1) }
        wantedPages = wanted
        val it = baseJobs.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key !in wanted) { e.value.cancel(false); it.remove() } }
        for (i in wanted) {
            if (i in baseJobs || !needsBase(i)) continue
            val size = RenderGeometry.baseBitmapSize(widthsPt[i], heightsPt[i], baseTargetWidth(i), maxBasePixels())
            val stamp = stampOf(i)
            val serial = docSerial
            val f = submit(d) {
                if (i !in wantedPages) { post { if (docSerial == serial) baseJobs.remove(i) }; return@submit }
                val bmp = try {
                    Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                } catch (_: OutOfMemoryError) {
                    post { if (docSerial == serial) { baseJobs.remove(i); baseCache.clear() } }
                    return@submit
                }
                // PdfRenderer не заливает фон: без белого фона прозрачные области страницы станут цветом фона.
                bmp.eraseColor(Color.WHITE)
                try {
                    d.runOnPage(i) { it.render(bmp, null, null, renderParams) }
                } catch (e: Exception) {
                    bmp.recycle(); post { if (docSerial == serial) baseJobs.remove(i) }; return@submit
                }
                post {
                    if (docSerial != serial || doc !== d) { bmp.recycle(); return@post }
                    baseJobs.remove(i)
                    baseCache.put(i, BaseBitmap(bmp, stamp))
                    invalidate()
                    // Если пока рендерили, страница снова устарела — ещё раз.
                    if (needsBase(i) && i in wantedPages) scheduleRenders()
                }
            }
            if (f != null) baseJobs[i] = f
        }
    }

    private fun scheduleSharp(delay: Long) {
        removeCallbacks(sharpRunnable)
        if (!ready) return
        postDelayed(sharpRunnable, delay)
    }

    /** Резкая дорисовка видимого участка при увеличении (после остановки движения). */
    private fun requestSharp() {
        val d = doc ?: return
        if (!ready) return
        if (gestureActive || zoomAnimator?.isRunning == true || flipAnimator?.isRunning == true || !scroller.isFinished || flipRaw != 0f) {
            scheduleSharp(SHARP_DELAY_MS); return
        }
        refreshSelectionAnchor()
        val vis = visiblePages()
        // Патчи невидимых страниц больше не нужны.
        patches.keys.filter { it !in vis }.forEach { removePatch(it) }
        val jobs = ArrayList<Triple<Int, com.rassul.pdfreader.core.geometry.SharpRegion, Long>>()
        for (i in vis) {
            val box = pageScreenBox(i) ?: continue
            val base = baseCache[i]
            val baseW = base?.bitmap?.width ?: RenderGeometry.baseBitmapSize(widthsPt[i], heightsPt[i], baseTargetWidth(i), maxBasePixels()).width
            if (!RenderGeometry.needsSharp(box.width, baseW, SHARP_THRESHOLD)) { removePatch(i); continue }
            val p = patches[i]
            val region = RenderGeometry.sharpRegion(box, widthsPt[i], vp.viewW, vp.viewH, (vp.viewW * vp.viewH * 1.05f).toLong()) ?: continue
            val stamp = stampOf(i)
            // Уже есть подходящий патч (тот же масштаб, покрывает видимую часть)?
            if (p != null && p.stamp == stamp && abs(p.zoom - vp.zoom) < 1e-3f) {
                val s = region.pxPerPt
                val need = Box((region.regionLeft - region.translateX) / s, (region.regionTop - region.translateY) / s,
                    (region.regionRight - region.translateX) / s, (region.regionBottom - region.translateY) / s)
                if (p.ptBox.left <= need.left + 0.5f && p.ptBox.top <= need.top + 0.5f &&
                    p.ptBox.right >= need.right - 0.5f && p.ptBox.bottom >= need.bottom - 0.5f) continue
            }
            jobs.add(Triple(i, region, stamp))
        }
        sharpJob?.cancel(false)
        if (jobs.isEmpty()) return
        val seq = ++sharpSeq
        val serial = docSerial
        val zoomAt = vp.zoom
        sharpJob = submit(d) {
            for ((i, r, stamp) in jobs) {
                if (seq != sharpSeq) return@submit
                val bmp = try { Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return@submit }
                bmp.eraseColor(Color.WHITE)
                // Матрица: пиксель = pt · s + translate (см. RenderGeometry.SharpRegion).
                val m = Matrix().apply { setScale(r.pxPerPt, r.pxPerPt); postTranslate(r.translateX, r.translateY) }
                try {
                    d.runOnPage(i) { it.render(bmp, null, m, renderParams) }
                } catch (_: Exception) { bmp.recycle(); return@submit }
                val s = r.pxPerPt
                val ptBox = Box(-r.translateX / s, -r.translateY / s, (r.width - r.translateX) / s, (r.height - r.translateY) / s)
                post {
                    if (docSerial != serial || seq != sharpSeq) { bmp.recycle(); return@post }
                    removePatch(i)
                    patches[i] = Patch(i, bmp, ptBox, stamp, zoomAt)
                    invalidate()
                }
            }
        }
    }

    private fun removePatch(i: Int) { patches.remove(i)?.let { baseCache.retire(it.bitmap) } }

    private fun clearPatches() {
        sharpSeq++
        sharpJob?.cancel(false); sharpJob = null
        for (p in patches.values) baseCache.retire(p.bitmap)
        patches.clear()
    }

    private fun cancelAllJobs() {
        for (f in baseJobs.values) f.cancel(false)
        baseJobs.clear()
        wantedPages = emptySet()
        clearPatches()
        removeCallbacks(sharpRunnable)
    }

    // ================================================================ отрисовка

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(gapColor)
        if (!ready) return
        val noteMinPx = 28f * density
        for (i in visiblePages()) {
            val b = pageScreenBox(i) ?: continue
            tmpRectF.set(b.left, b.top, b.right, b.bottom)
            val base = baseCache[i]
            if (base != null && !base.bitmap.isRecycled) {
                canvas.drawBitmap(base.bitmap, null, tmpRectF, pagePaint)
            } else {
                fillPaint.color = if (nightMode) PLACEHOLDER_NIGHT else PLACEHOLDER
                canvas.drawRect(tmpRectF, fillPaint)
            }
            val s = b.width / widthsPt[i]
            patches[i]?.let { p ->
                if (!p.bitmap.isRecycled) {
                    tmpRectF.set(b.left + p.ptBox.left * s, b.top + p.ptBox.top * s, b.left + p.ptBox.right * s, b.top + p.ptBox.bottom * s)
                    canvas.save()
                    canvas.clipRect(b.left, b.top, b.right, b.bottom)
                    canvas.drawBitmap(p.bitmap, null, tmpRectF, pagePaint)
                    canvas.restore()
                }
            }
            drawPageOverlays(canvas, i, b, s, noteMinPx)
        }
        drawSelectionHandles(canvas)
    }

    private fun drawPageOverlays(canvas: Canvas, i: Int, b: Box, s: Float, noteMinPx: Float) {
        val list = annotations.onPage(i)
        val stroking = strokePage == i && strokePoints != null
        val noteSizePt = maxOf(NoteAnnotation.ICON_SIZE_PT, noteMinPx / s)
        if (list.isNotEmpty() || stroking) {
            // В ночном режиме слой пометок проходит через ту же матрицу, что и страница, —
            // чтобы пометки выглядели одинаково до и после записи в PDF.
            val save = if (nightMode) {
                layerPaint.colorFilter = nightFilter
                canvas.saveLayer(b.left, b.top, b.right, b.bottom, layerPaint)
            } else canvas.save()
            canvas.clipRect(b.left, b.top, b.right, b.bottom)
            canvas.translate(b.left, b.top)
            canvas.scale(s, s)
            overlay.drawAnnotations(canvas, list, noteSizePt, multiply = !nightMode)
            if (stroking) {
                val pts = strokePoints!!
                strokePath.rewind(); overlay.buildPath(pts, strokePath)
                if (tool == Tool.HIGHLIGHTER) overlay.drawInk(canvas, highlighterColor, highlighterWidthPt, HIGHLIGHTER_OPACITY, strokePath)
                else overlay.drawInk(canvas, penColor, penWidthPt, 1f, strokePath)
            }
            canvas.restoreToCount(save)
        }
        val hits = searchHits
        val sel = selection
        if (hits.isEmpty() && (sel == null || sel.page != i)) return
        val save = canvas.save()
        canvas.translate(b.left, b.top)
        canvas.scale(s, s)
        for (h in hits) if (h.page == i && h !== searchCurrent) overlay.drawRects(canvas, h.rectsPt, OverlayPainter.SEARCH_FILL, null, 0f)
        searchCurrent?.let { h ->
            if (h.page == i) overlay.drawRects(canvas, h.rectsPt, OverlayPainter.SEARCH_CURRENT_FILL, OverlayPainter.SEARCH_CURRENT_STROKE, 2f * density / s)
        }
        if (sel != null && sel.page == i) overlay.drawRects(canvas, sel.rectsPt, OverlayPainter.SELECTION_FILL, null, 0f)
        canvas.restoreToCount(save)
    }

    /** Экранные точки ручек выделения: (x, y) начала и конца (нижние углы первой/последней строки). */
    private fun handlePoints(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val sel = selection ?: return null
        val b = pageScreenBox(sel.page) ?: return null
        val s = b.width / widthsPt[sel.page]
        val first = sel.rectsPt.first(); val last = sel.rectsPt.last()
        return (b.left + first.left * s to b.top + first.bottom * s) to (b.left + last.right * s to b.top + last.bottom * s)
    }

    private fun drawSelectionHandles(canvas: Canvas) {
        val (a, z) = handlePoints() ?: return
        overlay.drawHandle(canvas, a.first, a.second, handleRadius, left = true)
        overlay.drawHandle(canvas, z.first, z.second, handleRadius, left = false)
    }

    // ================================================================ анимации

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            vp = vp.withScroll(scroller.currX.toFloat(), scroller.currY.toFloat()).clamped()
            afterViewportChanged()
            postInvalidateOnAnimation()
        }
    }

    private fun cancelMotion() {
        scroller.forceFinished(true)
        zoomAnimator?.cancel(); zoomAnimator = null
        if (flipAnimator?.isRunning == true) flipAnimator?.end()
        flipAnimator = null
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float, from: Viewport): Viewport =
        layoutC?.takeIf { mode == ReadingMode.CONTINUOUS }?.zoomAnchored(from, target, fx, fy)
            ?: from.zoomAround(target, fx, fy)

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        cancelMotion()
        val start = vp
        val z0 = start.zoom
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                setViewport(zoomTo(z0 + (target - z0) * t, fx, fy, start))
            }
            start()
        }
    }

    /** Анимация перелистывания в режиме PAGED: dir = +1 (вперёд), −1 (назад), 0 (вернуть на место). */
    private fun animateFlip(dir: Int) {
        flipAnimator?.cancel()
        val limit = vp.viewW + gapPx
        val from = flipOffset()
        flipRaw = from
        flipAnimating = true
        val to = -dir * limit
        flipAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (dir == 0) FLIP_ANIM_MS * 3 / 4 else FLIP_ANIM_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a -> flipRaw = a.animatedValue as Float; invalidate(); scheduleRenders() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    flipAnimator = null
                    flipAnimating = false
                    if (dir != 0) showPagedIndex((pagedIndex + dir).coerceIn(0, pageCount - 1))
                    else { flipRaw = 0f; afterViewportChanged() }
                }
            })
            start()
        }
    }

    // ================================================================ жесты

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1 || scaleDetector.isInProgress) return true // двумя пальцами — своя прокрутка по фокусу
            panBy(dx, dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e2.pointerCount > 1 || !ready) return false
            if (mode == ReadingMode.PAGED && flipRaw != 0f) return false // решит ACTION_UP
            flingHandled = true
            scroller.fling(vp.scrollX.roundToInt(), vp.scrollY.roundToInt(), (-vx).roundToInt(), (-vy).roundToInt(),
                vp.minScrollX.roundToInt(), vp.maxScrollX.roundToInt(), vp.minScrollY.roundToInt(), vp.maxScrollY.roundToInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (tool == Tool.NOTE) { handleNoteTap(e.x, e.y); return true }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tool == Tool.NOTE) return true
            handleTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool != Tool.NONE || !ready) return false
            animateZoom(doubleTapTargetZoom(vp.zoom, DOUBLE_TAP_ZOOM, MIN_ZOOM), e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (tool != Tool.NONE || !ready || flipRaw != 0f) return
            val hit = screenToPage(e.x, e.y) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            clearSelection()
            val pt = SelectionBoundary(Point(hit.second.roundToInt(), hit.third.roundToInt()))
            requestSelection(hit.first, pt, pt, initial = true)
            dragMode = Drag.SELECT_EXTEND
        }
    }).apply { setIsLongpressEnabled(true) }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (!ready) return false
            cancelStroke()
            if (mode == ReadingMode.PAGED && flipRaw != 0f) animateFlip(0)
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val z = clampZoom(vp.zoom * d.scaleFactor, MIN_ZOOM, MAX_ZOOM)
            if (z != vp.zoom) setViewport(zoomTo(z, d.focusX, d.focusY, vp))
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private fun panBy(dx: Float, dy: Float) {
        if (!ready) return
        if (mode == ReadingMode.CONTINUOUS) { setViewport(vp.withScroll(vp.scrollX + dx, vp.scrollY + dy)); return }
        // PAGED: горизонтальный «перелив» за край страницы уходит в перелистывание —
        // только если жест начался у этого края (иначе увеличенную страницу просто панорамируем).
        var rest = dx
        if (flipRaw != 0f) {
            val nf = flipRaw - rest
            if (nf * flipRaw > 0f) { flipRaw = nf; rest = 0f }
            else { rest = dx - flipRaw; flipRaw = 0f } // перешли через ноль: на возврат ушло flipRaw, остаток — в панораму
        }
        val (sx, over) = Viewport.panWithOverflow(vp.scrollX, rest, vp.minScrollX, vp.maxScrollX)
        if (over != 0f) {
            val allowed = if (over > 0f) dragStartAtRight else dragStartAtLeft
            if (allowed) flipRaw -= over
        }
        vp = vp.withScroll(sx, vp.scrollY + dy).clamped()
        afterViewportChanged()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ready) return super.onTouchEvent(e)
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(e)
        scaleDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureActive = true
                flingHandled = false
                scroller.forceFinished(true)
                zoomAnimator?.cancel(); zoomAnimator = null
                if (flipAnimator?.isRunning == true) flipAnimator?.end()
                dragStartAtLeft = vp.scrollX <= vp.minScrollX + 1f
                dragStartAtRight = vp.scrollX >= vp.maxScrollX - 1f
                lastFocusX = Float.NaN
                dragMode = Drag.NONE
                handleHit(e.x, e.y)?.let { dragMode = it; parent?.requestDisallowInterceptTouchEvent(true) }
                if (dragMode == Drag.NONE) when (tool) {
                    Tool.PEN, Tool.HIGHLIGHTER -> screenToPage(e.x, e.y)?.let { (p, x, y) ->
                        dragMode = Drag.STROKE; strokePage = p
                        strokePoints = arrayListOf(PtPoint(x, y)); invalidate()
                    }
                    Tool.ERASER -> if (screenToPage(e.x, e.y) != null) { dragMode = Drag.ERASE; eraseAt(e.x, e.y) }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Второй палец: рисование/ластик/выделение прерываются — работают масштаб и прокрутка.
                if (dragMode == Drag.STROKE) cancelStroke()
                if (dragMode != Drag.NONE) dragMode = Drag.NONE
                lastFocusX = Float.NaN
            }
            MotionEvent.ACTION_POINTER_UP -> lastFocusX = Float.NaN
        }

        // Прокрутка двумя пальцами — по смещению «центра» касаний.
        if (e.pointerCount >= 2 && e.actionMasked == MotionEvent.ACTION_MOVE) {
            var fx = 0f; var fy = 0f
            for (k in 0 until e.pointerCount) { fx += e.getX(k); fy += e.getY(k) }
            fx /= e.pointerCount; fy /= e.pointerCount
            if (!lastFocusX.isNaN()) panBy(lastFocusX - fx, lastFocusY - fy)
            lastFocusX = fx; lastFocusY = fy
        }

        when (dragMode) {
            Drag.STROKE -> onStrokeEvent(e)
            Drag.ERASE -> if (e.actionMasked == MotionEvent.ACTION_MOVE) eraseAt(e.x, e.y)
            Drag.SELECT_EXTEND, Drag.HANDLE_START, Drag.HANDLE_END -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) onSelectionDrag(e.x, e.y)
                if (dragMode == Drag.SELECT_EXTEND) gestures.onTouchEvent(e) // для onLongPress/ACTION_UP-состояния
            }
            Drag.NONE -> gestures.onTouchEvent(e)
        }

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureActive = false
            vt.computeCurrentVelocity(1000)
            val vx = vt.xVelocity
            vt.recycle(); velocity = null
            if (dragMode == Drag.STROKE) {
                if (e.actionMasked == MotionEvent.ACTION_UP) finishStroke() else cancelStroke()
            }
            dragMode = Drag.NONE
            if (mode == ReadingMode.PAGED && flipRaw != 0f) {
                val dir = PagedGeometry.decideFlip(flipOffset(), vx, vp.viewW, pagedIndex > 0, pagedIndex < pageCount - 1)
                animateFlip(dir)
            }
            scheduleSharp(SHARP_DELAY_MS)
        }
        return true
    }

    private fun handleHit(x: Float, y: Float): Drag? {
        val (a, z) = handlePoints() ?: return null
        val r = handleRadius * 2.2f
        // Центры кругов ручек смещены наружу на радиус (см. OverlayPainter.drawHandle).
        if (hypot(x - (a.first - handleRadius), y - (a.second + handleRadius)) <= r) return Drag.HANDLE_START
        if (hypot(x - (z.first + handleRadius), y - (z.second + handleRadius)) <= r) return Drag.HANDLE_END
        return null
    }

    private fun onSelectionDrag(sx: Float, sy: Float) {
        val sel = selection ?: return
        val b = pageScreenBox(sel.page) ?: return
        val s = b.width / widthsPt[sel.page]
        // Точку ограничиваем страницей выделения (выделение — в пределах одной страницы).
        val x = ((sx - b.left) / s).coerceIn(0f, widthsPt[sel.page])
        // Для ручек целимся в середину строки над пальцем (палец закрывает текст ниже ручки).
        val yAdj = if (dragMode == Drag.SELECT_EXTEND) sy else sy - handleRadius * 1.5f
        val y = ((yAdj - b.top) / s).coerceIn(0f, heightsPt[sel.page])
        val p = SelectionBoundary(Point(x.roundToInt(), y.roundToInt()))
        when (dragMode) {
            Drag.HANDLE_START -> selStop?.let { requestSelection(sel.page, p, it, false) }
            Drag.HANDLE_END -> selStart?.let { requestSelection(sel.page, it, p, false) }
            Drag.SELECT_EXTEND -> {
                val anchor = selAnchorPt ?: return
                // До слова-якоря — тянем начало, после — конец.
                val before = y < anchor.top || (y <= anchor.bottom && x < anchor.left)
                val a0 = SelectionBoundary(Point(anchor.left.roundToInt() + 1, anchor.centerY().roundToInt()))
                val a1 = SelectionBoundary(Point(anchor.right.roundToInt() - 1, anchor.centerY().roundToInt()))
                if (before) requestSelection(sel.page, p, a1, false) else requestSelection(sel.page, a0, p, false)
            }
            else -> {}
        }
    }

    private fun onStrokeEvent(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_MOVE) return
        val pts = strokePoints ?: return
        val b = pageScreenBox(strokePage) ?: return
        val s = b.width / widthsPt[strokePage]
        val minDist = 1.5f / s // ≥1.5 экранных px между точками
        fun add(sx: Float, sy: Float) {
            val x = ((sx - b.left) / s).coerceIn(0f, widthsPt[strokePage])
            val y = ((sy - b.top) / s).coerceIn(0f, heightsPt[strokePage])
            val last = pts.last()
            if (hypot(x - last.x, y - last.y) >= minDist) pts.add(PtPoint(x, y))
        }
        for (h in 0 until e.historySize) add(e.getHistoricalX(h), e.getHistoricalY(h))
        add(e.x, e.y)
        invalidate()
    }

    private fun finishStroke() {
        val pts = strokePoints; val page = strokePage
        strokePoints = null; strokePage = -1
        if (pts == null || page < 0 || pts.isEmpty()) { invalidate(); return }
        val stroke = if (pts.size == 1) listOf(pts[0], PtPoint(pts[0].x + 0.01f, pts[0].y)) else pts.toList()
        val a = if (tool == Tool.HIGHLIGHTER) InkAnnotation(page, listOf(stroke), highlighterColor, highlighterWidthPt, HIGHLIGHTER_OPACITY)
        else InkAnnotation(page, listOf(stroke), penColor, penWidthPt, 1f)
        annotations.add(a)
        annotationsChanged()
    }

    private fun cancelStroke() {
        if (strokePoints != null) { strokePoints = null; strokePage = -1; invalidate() }
        if (dragMode == Drag.STROKE) dragMode = Drag.NONE
    }

    private fun eraseAt(sx: Float, sy: Float) {
        val (page, x, y) = screenToPage(sx, sy, 12f * density) ?: return
        val s = pxPerPt(page)
        if (s <= 0f) return
        val tol = 12f * density / s
        val noteSize = maxOf(NoteAnnotation.ICON_SIZE_PT, 28f * density / s)
        val victims = annotations.onPage(page).filter { AnnotationGeometry.hits(it, x, y, tol, noteSize) }
        if (victims.isEmpty()) return
        for (v in victims) annotations.remove(v)
        annotationsChanged()
    }

    private fun noteAt(sx: Float, sy: Float): NoteAnnotation? {
        val (page, x, y) = screenToPage(sx, sy, 8f * density) ?: return null
        val s = pxPerPt(page)
        if (s <= 0f) return null
        val size = maxOf(NoteAnnotation.ICON_SIZE_PT, 28f * density / s)
        return annotations.onPage(page).filterIsInstance<NoteAnnotation>()
            .lastOrNull { AnnotationGeometry.hits(it, x, y, 4f * density / s, size) }
    }

    private fun handleNoteTap(sx: Float, sy: Float) {
        noteAt(sx, sy)?.let { onNoteTapped?.invoke(it); return }
        val (page, x, y) = screenToPage(sx, sy) ?: return
        onNoteRequested?.invoke(page, x, y)
    }

    /** Тап без инструмента: выделение → заметка → поле формы → ссылка → панели. */
    private fun handleTap(sx: Float, sy: Float) {
        if (selection != null) { clearSelection(); return }
        noteAt(sx, sy)?.let { onNoteTapped?.invoke(it); return }
        if (tool != Tool.NONE) { onSingleTap?.invoke(); return }
        val hit = screenToPage(sx, sy)
        if (hit == null) { onSingleTap?.invoke(); return }
        val (page, x, y) = hit
        withExtras(page) { ex ->
            val w = if (doc?.hasForms == true) ex.widgetAt(x, y) else null
            if (w != null) { onFormWidgetTapped?.invoke(page, w); return@withExtras }
            ex.gotoAt(x, y)?.let { g ->
                val dst = g.destination
                val p = dst.pageNumber
                if (p in 0 until pageCount) {
                    if (dst.xCoordinate == 0f && dst.yCoordinate == 0f) goToPage(p) else goToPagePoint(p, dst.xCoordinate, dst.yCoordinate)
                }
                return@withExtras
            }
            ex.externalLinkAt(x, y)?.let { l -> l.uri?.let { onExternalLink?.invoke(it) }; return@withExtras }
            onSingleTap?.invoke()
        }
    }

    /** Ссылки и поля страницы: из кэша сразу, иначе загрузить на worker и вызвать в UI-потоке. */
    private fun withExtras(page: Int, cb: (PageExtras) -> Unit) {
        extras[page]?.let { cb(it); return }
        val d = doc ?: return
        val waiting = extrasJobs[page]
        if (waiting != null) { waiting.add(cb); return }
        extrasJobs[page] = mutableListOf(cb)
        val serial = docSerial
        val hasForms = d.hasForms
        val f = submit(d) {
            val ex = try {
                d.runOnPage(page) { p ->
                    PageExtras(
                        links = try { p.linkContents } catch (_: Exception) { emptyList() },
                        gotos = try { p.gotoLinks } catch (_: Exception) { emptyList() },
                        widgets = if (hasForms) try { p.formWidgetInfos } catch (_: Exception) { emptyList() } else emptyList(),
                    )
                }
            } catch (_: Exception) { PageExtras(emptyList(), emptyList(), emptyList()) }
            post {
                if (docSerial != serial) return@post
                extras.put(page, ex)
                extrasJobs.remove(page)?.forEach { it(ex) }
            }
        }
        if (f == null) extrasJobs.remove(page)
    }

    override fun onDetachedFromWindow() {
        cancelMotion()
        removeCallbacks(sharpRunnable)
        super.onDetachedFromWindow()
    }
}
