package com.rassul.pdfreader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rassul.pdfreader.data.RecentEntry
import com.rassul.pdfreader.data.RecentStore
import com.rassul.pdfreader.data.SettingsStore
import com.rassul.pdfreader.data.ThumbnailStore
import com.rassul.pdfreader.reader.ReaderActivity
import com.rassul.pdfreader.settings.showSettingsSheet
import com.rassul.pdfreader.ui.CoverView
import com.rassul.pdfreader.ui.EmptyIllustration
import com.rassul.pdfreader.ui.Format
import com.rassul.pdfreader.ui.Icon
import com.rassul.pdfreader.ui.ProgressLine
import com.rassul.pdfreader.ui.SheetItem
import com.rassul.pdfreader.ui.Tokens
import com.rassul.pdfreader.ui.Ui
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/*
 * КОНТРАКТ (владелец реализации — агент «ui-designer»).
 * Главный экран: недавние файлы с обложками и прогрессом, кнопка «Открыть PDF»
 * (ACTION_OPEN_DOCUMENT + takePersistableUriPermission), настройки.
 * Открытие документа: startActivity(ReaderActivity.intent(this, uri)).
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_OPEN = 41
        private val io = Executors.newSingleThreadExecutor()
    }

    private lateinit var recents: RecentStore
    private lateinit var thumbs: ThumbnailStore
    private lateinit var frame: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var listHeader: View
    private lateinit var empty: View
    private lateinit var fab: TextView
    private var shown: List<RecentEntry>? = null
    private var columns = 2
    private var firstBuild = true
    private var fabHidden = false
    private var wasDark = true
    private var insetBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(this)
        wasDark = Ui.colors.isDark
        recents = RecentStore(this)
        thumbs = ThumbnailStore(this)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (SettingsStore(this).darkTheme != wasDark) { recreate(); return }
        val c = computeColumns()
        if (c != columns) { shown = null; refresh() }
    }

    // ------------------------------------------------------------------ построение

    private fun buildScreen() {
        val p = Ui.colors
        frame = FrameLayout(this)
        frame.setBackgroundColor(p.bg)

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.isFillViewport = true
        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Шапка
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 4))
        val title = TextView(this).apply {
            text = "PDF Ридер"
            typeface = Tokens.FONT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_DISPLAY)
            setTextColor(p.text)
            isAccessibilityHeading = true
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.iconButton(this, Icon.SETTINGS, "Настройки") {
            showSettingsSheet(this) { shown = null; refresh() }
        })
        column.addView(header)

        // Заголовок списка
        val lh = LinearLayout(this)
        lh.orientation = LinearLayout.HORIZONTAL
        lh.gravity = Gravity.BOTTOM
        lh.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 12))
        lh.addView(TextView(this).apply {
            text = "Недавние"
            typeface = Tokens.FONT_MEDIUM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_SUBTITLE)
            setTextColor(p.text)
            isAccessibilityHeading = true
        })
        countLabel = Ui.label(this, "").apply { setPadding(Ui.dp(this@MainActivity, 8), 0, 0, Ui.dp(this@MainActivity, 1)) }
        lh.addView(countLabel)
        listHeader = lh
        column.addView(lh)

        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        column.addView(grid)

        empty = buildEmptyState()
        column.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Плавающая кнопка «Открыть PDF»
        fab = TextView(this).apply {
            text = "Открыть PDF"
            typeface = Tokens.FONT_MEDIUM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_BODY)
            setTextColor(p.onPrimary)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = Ui.dp(this@MainActivity, 56)
            setPadding(Ui.dp(this@MainActivity, 18), 0, Ui.dp(this@MainActivity, 22), 0)
            compoundDrawablePadding = Ui.dp(this@MainActivity, 10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this@MainActivity, Icon.ADD, p.onPrimary), null, null, null)
            background = Ui.ripple(this@MainActivity, Ui.roundRect(this@MainActivity, p.primary, 18f), oval = false, radiusDp = 18f)
            elevation = Ui.dp(this@MainActivity, Tokens.ELEV_FAB).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            outlineSpotShadowColor = if (p.isDark) 0xFF000000.toInt() else p.primary
            isClickable = true
            isFocusable = true
            setOnClickListener { openPicker() }
        }
        Ui.pressScale(fab)
        frame.addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        })

        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 4 && y > Ui.dp(this, 48)) setFabHidden(true)
            else if (y < oldY - 4) setFabHidden(false)
        }

        frame.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetBottom = bars.bottom
            column.setPadding(bars.left, bars.top + Ui.dp(this, 8), bars.right, bars.bottom + Ui.dp(this, 104))
            (fab.layoutParams as FrameLayout.LayoutParams).apply {
                rightMargin = bars.right + Ui.dp(this@MainActivity, 20)
                bottomMargin = bars.bottom + Ui.dp(this@MainActivity, 20)
            }
            fab.requestLayout()
            insets
        }
        setContentView(frame)
    }

    private fun buildEmptyState(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24))
        box.addView(EmptyIllustration(this))
        box.addView(Ui.title(this, "Здесь появятся недавние файлы").apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 24), 0, Ui.dp(this@MainActivity, 8))
        })
        box.addView(Ui.body(this, "Откройте PDF из памяти телефона — он сохранится в этом списке вместе с местом, где вы остановились.", secondary = true).apply {
            gravity = Gravity.CENTER
            maxWidth = Ui.dp(this@MainActivity, 340)
        })
        box.addView(Ui.button(this, "Открыть PDF", primary = true) { openPicker() }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this@MainActivity, Icon.OPEN, Ui.colors.onPrimary), null, null, null)
            compoundDrawablePadding = Ui.dp(this@MainActivity, 8)
            minHeight = Ui.dp(this@MainActivity, 52)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@MainActivity, 24)
        })
        return box
    }

    private fun computeColumns(): Int {
        val wDp = resources.configuration.screenWidthDp
        return ((wDp - 32) / 172).coerceIn(2, 6)
    }

    // ------------------------------------------------------------------ список

    private fun refresh() {
        val list = recents.list()
        val cols = computeColumns()
        if (list == shown && cols == columns) return
        shown = list
        columns = cols
        val isEmpty = list.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        listHeader.visibility = if (isEmpty) View.GONE else View.VISIBLE
        fab.visibility = if (isEmpty) View.GONE else View.VISIBLE
        countLabel.text = if (isEmpty) "" else list.size.toString()
        grid.removeAllViews()
        val gap = Ui.dp(this, 12)
        var row: LinearLayout? = null
        list.forEachIndexed { i, e ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = gap
                })
            }
            val card = card(e)
            row!!.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % cols != 0) marginStart = gap
            })
            if (firstBuild && Ui.animationsEnabled()) {
                card.alpha = 0f
                card.translationY = Ui.dp(this, 16).toFloat()
                card.animate().alpha(1f).translationY(0f).setStartDelay(Tokens.STAGGER * i.coerceAtMost(12))
                    .setDuration(Tokens.DUR_ENTER).setInterpolator(Tokens.EASE_ENTER).start()
            }
        }
        // добить последнюю строку пустыми ячейками, чтобы карточки были одной ширины
        val rem = list.size % cols
        if (rem != 0) repeat(cols - rem) {
            row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = gap })
        }
        if (isEmpty && firstBuild && Ui.animationsEnabled()) {
            empty.alpha = 0f
            empty.animate().alpha(1f).setDuration(Tokens.DUR_ENTER).start()
        }
        firstBuild = false
        setFabHidden(false)
    }

    private fun card(e: RecentEntry): View {
        val p = Ui.colors
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.roundRect(this, p.surface, Tokens.RADIUS_L, if (p.isDark) 0x12FFFFFF else 0, if (p.isDark) 1f else 0f)
        card.foreground = Ui.ripple(this, null, oval = false, radiusDp = Tokens.RADIUS_L)
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.elevation = if (p.isDark) 0f else Ui.dp(this, Tokens.ELEV_CARD).toFloat()
        card.isClickable = true
        card.isFocusable = true

        val cover = CoverView(this)
        val cached = thumbs.cached(e.uri)
        if (cached != null) cover.setBitmap(cached, animate = false)
        else io.execute {
            val b: Bitmap? = thumbs.get(e.uri)
            if (b != null) cover.post { cover.setBitmap(b, animate = true) }
        }
        card.addView(cover)

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 12))
        info.addView(TextView(this).apply {
            text = e.name
            typeface = Tokens.FONT_MEDIUM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(p.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        })
        val progress = ProgressLine(this)
        progress.progress = if (e.pageCount > 0) (e.page + 1f) / e.pageCount else 0f
        info.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@MainActivity, 10)
        })
        info.addView(TextView(this).apply {
            text = Format.pages(e.page, e.pageCount)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_CAPTION)
            setTextColor(p.textSecondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
        })
        info.addView(TextView(this).apply {
            text = Format.relativeTime(e.lastOpened)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_CAPTION)
            setTextColor(p.textSecondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(info)

        card.contentDescription = "${e.name}. ${Format.pages(e.page, e.pageCount)}. Открыт ${Format.relativeTime(e.lastOpened)}"
        card.setOnClickListener { openRecent(e) }
        card.setOnLongClickListener { showItemMenu(e); true }
        Ui.pressScale(card, 0.98f)
        return card
    }

    private fun showItemMenu(e: RecentEntry) {
        Ui.showListSheet(this, e.name, listOf(
            SheetItem("Открыть", Icon.OPEN) { openRecent(e) },
            SheetItem("Убрать из недавних", Icon.DELETE, secondary = "Сам файл не удаляется") { removeEntry(e) },
        ))
    }

    private fun removeEntry(e: RecentEntry) {
        val bmp = thumbs.get(e.uri)
        recents.remove(e.uri)
        thumbs.remove(e.uri)
        refresh()
        Ui.toast(this, "Убрано из недавних", "Отменить") {
            recents.restore(e)
            if (bmp != null) io.execute { thumbs.put(e.uri, bmp) }
            shown = null
            refresh()
        }
    }

    // ------------------------------------------------------------------ открытие

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_OPEN)
        } catch (_: ActivityNotFoundException) {
            Ui.toast(this, "Не найдено приложение для выбора файлов")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val granted = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, if (granted != 0) granted else Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        }
        openReader(uri)
    }

    private fun openReader(uri: Uri) {
        try {
            startActivity(ReaderActivity.intent(this, uri))
        } catch (_: Exception) {
            Ui.toast(this, "Не удалось открыть файл")
        }
    }

    /** Перед открытием проверяем, что доступ к файлу ещё есть (в фоне — провайдер может быть медленным). */
    private fun openRecent(e: RecentEntry) {
        val uri = Uri.parse(e.uri)
        Thread {
            val ok = try {
                contentResolver.openFileDescriptor(uri, "r")?.close()
                true
            } catch (_: SecurityException) { false
            } catch (_: FileNotFoundException) { false
            } catch (_: IllegalArgumentException) { false
            } catch (_: Exception) { false }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) openReader(uri) else Ui.showChoice(
                    this, "Файл недоступен",
                    "Не получается открыть «${e.name}». Возможно, файл перемещён или удалён, либо приложение, " +
                        "из которого он был открыт, больше не даёт к нему доступ. Откройте его заново через «Открыть PDF».",
                    listOf("Оставить" to {}, "Убрать из списка" to { removeEntry(e) }),
                )
            }
        }.start()
    }

    private fun setFabHidden(hide: Boolean) {
        if (hide == fabHidden || !::fab.isInitialized) return
        fabHidden = hide
        val dist = (fab.height + Ui.dp(this, 20) + insetBottom).toFloat()
        fab.animate().translationY(if (hide) dist else 0f).setDuration(if (hide) Tokens.DUR_EXIT else Tokens.DUR_ENTER)
            .setInterpolator(if (hide) Tokens.EASE_EXIT else Tokens.EASE_ENTER).start()
    }
}
