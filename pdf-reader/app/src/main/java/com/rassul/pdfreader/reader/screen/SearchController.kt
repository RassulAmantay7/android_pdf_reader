package com.rassul.pdfreader.reader.screen

import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.rassul.pdfreader.reader.PdfDoc
import com.rassul.pdfreader.reader.ReaderActivity
import com.rassul.pdfreader.reader.SearchHit
import com.rassul.pdfreader.ui.Icon
import com.rassul.pdfreader.ui.Ui
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Панель поиска и сам поиск. Поиск идёт постранично: на каждую страницу — отдельная
 * задача в doc.worker (чтобы рендер страниц не ждал конца поиска), результаты
 * показываются по мере нахождения, новый запрос отменяет предыдущий (поколение gen).
 * Регистронезависимость обеспечивает PdfRenderer.Page.searchText (pdfium ищет без учёта регистра).
 */
internal class SearchController(private val a: ReaderActivity) {
    val panel: LinearLayout
    private val field = EditText(a)
    private val counter = TextView(a)
    private val status = TextView(a)
    private val upBtn: View
    private val downBtn: View

    var active = false
        private set
    private val gen = AtomicInteger(0)
    private val hits = ArrayList<SearchHit>()
    private var current = -1
    private var lastQuery = ""
    private var done = true
    private var scanned = 0
    private var total = 0
    private var textSeen = AtomicBoolean(false)
    private val debounce = Runnable { val q = field.text?.toString()?.trim() ?: ""; if (q.length >= 2 && q != lastQuery) run(q) }

    init {
        val c = Ui.colors
        panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(c.surface)
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, 56)
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
        }
        row.addView(Ui.iconButton(a, Icon.CLOSE, "Закрыть поиск") { close() })
        field.apply {
            hint = "Найти в документе"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(c.text)
            setHintTextColor(c.textSecondary)
            backgroundTintList = ColorStateList.valueOf(c.primary)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    val q = text?.toString()?.trim() ?: ""
                    if (q.isNotEmpty()) {
                        if (q == lastQuery && hits.isNotEmpty()) next() else run(q)
                        hideKeyboard()
                    }
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    a.bg.main.removeCallbacks(debounce)
                    if (active) a.bg.main.postDelayed(debounce, 600)
                }
            })
        }
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        counter.apply {
            setTextColor(c.textSecondary)
            textSize = 14f
            setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 4), 0)
        }
        row.addView(counter)
        upBtn = Ui.iconButton(a, Icon.CHEVRON_UP, "Предыдущее совпадение") { prev() }
        downBtn = Ui.iconButton(a, Icon.CHEVRON_DOWN, "Следующее совпадение") { next() }
        row.addView(upBtn)
        row.addView(downBtn)
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status.apply {
            setTextColor(c.textSecondary)
            textSize = 14f
            setPadding(Ui.dp(a, 60), 0, Ui.dp(a, 16), Ui.dp(a, 10))
            visibility = View.GONE
        }
        panel.addView(status)
        updateUi()
    }

    /** Открыть панель; prefill — подставить текст и сразу искать. */
    fun open(prefill: String?) {
        if (a.doc == null) return
        active = true
        a.onSearchShown(true)
        if (!prefill.isNullOrBlank()) {
            val q = prefill.trim().replace(Regex("\\s+"), " ").take(200)
            field.setText(q)
            field.setSelection(q.length)
            a.bg.main.removeCallbacks(debounce)
            run(q)
        } else {
            field.requestFocus()
            field.selectAll()
            showKeyboard()
        }
    }

    fun close() {
        if (!active) return
        cancel()
        a.bg.main.removeCallbacks(debounce)
        active = false
        hits.clear(); current = -1; lastQuery = ""; done = true
        hideKeyboard()
        try { a.view.clearSearch() } catch (_: Throwable) { }
        a.onSearchShown(false)
    }

    /** Отменить идущий поиск (без закрытия панели). */
    fun cancel() { gen.incrementAndGet() }

    fun run(query: String) {
        val d = a.doc ?: return
        val q = query.trim()
        if (q.isEmpty()) return
        val myGen = gen.incrementAndGet()
        lastQuery = q
        hits.clear(); current = -1; done = false; scanned = 0
        total = d.pageCount
        textSeen = AtomicBoolean(false)
        try { a.view.clearSearch() } catch (_: Throwable) { }
        updateUi()
        if (total <= 0) { done = true; updateUi(); return }
        val start = a.view.currentPage.coerceIn(0, total - 1)
        step(d, q, myGen, start, 0, total, textSeen)
    }

    private fun step(d: PdfDoc, q: String, myGen: Int, start: Int, i: Int, n: Int, seen: AtomicBoolean) {
        try {
            d.worker.execute {
                if (gen.get() != myGen) return@execute
                val page = (start + i) % n
                var found: List<SearchHit> = emptyList()
                try {
                    d.runOnPage(page) { p ->
                        val m = p.searchText(q)
                        found = m.map { SearchHit(page, ArrayList(it.bounds)) }
                        if (found.isNotEmpty()) seen.set(true)
                        else if (!seen.get() && p.textContents.any { it.text?.isNotBlank() == true }) seen.set(true)
                    }
                } catch (_: Throwable) { /* страница не читается — пропускаем */ }
                a.bg.ui { if (gen.get() == myGen) onPageDone(page, found, i + 1 >= n) }
                if (i + 1 < n && gen.get() == myGen) step(d, q, myGen, start, i + 1, n, seen)
            }
        } catch (_: RejectedExecutionException) { }
    }

    private fun onPageDone(page: Int, found: List<SearchHit>, last: Boolean) {
        scanned++
        if (found.isNotEmpty()) {
            var pos = hits.size
            for (k in hits.indices) if (hits[k].page > page) { pos = k; break }
            hits.addAll(pos, found)
            if (current >= 0 && pos <= current) current += found.size
            if (current < 0) { current = pos; goToCurrent() } else pushHits()
        }
        if (last) done = true
        updateUi()
    }

    fun next() {
        if (hits.isEmpty()) return
        current = (current + 1) % hits.size
        goToCurrent(); updateUi()
    }

    fun prev() {
        if (hits.isEmpty()) return
        current = (current - 1 + hits.size) % hits.size
        goToCurrent(); updateUi()
    }

    private fun goToCurrent() {
        val h = hits.getOrNull(current) ?: return
        val r = h.rectsPt.firstOrNull()
        a.view.goToPagePoint(h.page, r?.centerX() ?: 0f, r?.top ?: 0f)
        pushHits()
    }

    private fun pushHits() {
        a.view.setSearchHits(ArrayList(hits), current)
    }

    private fun updateUi() {
        val n = hits.size
        counter.text = when {
            lastQuery.isEmpty() -> ""
            n == 0 && !done -> "…"
            n == 0 -> "0"
            else -> "${current + 1} из $n" + if (done) "" else "…"
        }
        val enabled = n > 0
        for (b in listOf(upBtn, downBtn)) { b.isEnabled = enabled; b.alpha = if (enabled) 1f else 0.38f }
        val msg = when {
            lastQuery.isEmpty() -> null
            !done -> "Поиск… просмотрено страниц: $scanned из $total"
            n == 0 && !textSeen.get() -> "В этом документе нет текстового слоя, поиск недоступен"
            n == 0 -> "Ничего не найдено"
            else -> null
        }
        status.text = msg ?: ""
        status.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun showKeyboard() {
        field.post {
            field.requestFocus()
            a.window?.insetsController?.show(WindowInsets.Type.ime())
                ?: (a.getSystemService(InputMethodManager::class.java))?.showSoftInput(field, 0)
        }
    }

    private fun hideKeyboard() {
        a.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }
}
