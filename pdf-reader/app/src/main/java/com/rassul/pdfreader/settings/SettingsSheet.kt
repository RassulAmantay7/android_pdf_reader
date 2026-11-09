package com.rassul.pdfreader.settings

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.rassul.pdfreader.data.ReadingMode
import com.rassul.pdfreader.data.RecentStore
import com.rassul.pdfreader.data.SettingsStore
import com.rassul.pdfreader.data.ThemeMode
import com.rassul.pdfreader.data.ThumbnailStore
import com.rassul.pdfreader.ui.Icon
import com.rassul.pdfreader.ui.OverlayDialog
import com.rassul.pdfreader.ui.Tokens
import com.rassul.pdfreader.ui.Ui

/**
 * Настройки — нижний лист (FR-18, FR-40, FR-41).
 * onChanged вызывается после любого изменения (кроме темы — при смене темы активити пересоздаётся).
 */
fun showSettingsSheet(activity: Activity, onChanged: () -> Unit) {
    val ctx: Context = activity
    val s = SettingsStore(activity)
    val p = Ui.colors
    val box = LinearLayout(ctx)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), Ui.dp(ctx, 8))
    lateinit var dialog: android.app.Dialog

    fun padded(v: View): View {
        val wrap = LinearLayout(ctx)
        wrap.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 4), Ui.dp(ctx, 20), Ui.dp(ctx, 8))
        wrap.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    // ---- Оформление
    box.addView(Ui.sectionHeader(ctx, "Оформление").apply { setPadding(paddingLeft, Ui.dp(ctx, 4), paddingRight, paddingBottom) })
    box.addView(Ui.label(ctx, "Тема приложения").apply { setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20), Ui.dp(ctx, 8)) })
    val themes = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)
    box.addView(padded(Ui.segmented(ctx, listOf("Тёмная", "Светлая", "Как в системе"), themes.indexOf(s.themeMode)) { i ->
        val wasDark = s.darkTheme
        s.themeMode = themes[i]
        if (s.darkTheme != wasDark) {
            // Дать подложке доехать, затем пересоздать экран в новой теме.
            box.postDelayed({
                (dialog as? OverlayDialog)?.dismissNow() ?: dialog.dismiss()
                activity.recreate()
            }, Tokens.DUR_STANDARD + 40)
        }
    }))

    // ---- Чтение
    box.addView(Ui.sectionHeader(ctx, "Чтение"))
    box.addView(Ui.label(ctx, "Режим просмотра по умолчанию").apply { setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20), Ui.dp(ctx, 8)) })
    val modes = listOf(ReadingMode.CONTINUOUS, ReadingMode.PAGED)
    box.addView(padded(Ui.segmented(ctx, listOf("Лента", "Книга"), modes.indexOf(s.readingMode)) { i ->
        s.readingMode = modes[i]; onChanged()
    }))
    box.addView(Ui.switchRow(ctx, "Ночной режим страниц", "Светлый текст на тёмном фоне", s.nightPages) {
        s.nightPages = it; onChanged()
    })
    box.addView(Ui.switchRow(ctx, "Не гасить экран", "Пока открыт документ", s.keepScreenOn) {
        s.keepScreenOn = it; onChanged()
    })

    // ---- Данные
    box.addView(Ui.sectionHeader(ctx, "Данные"))
    val clear = Ui.settingRow(ctx, "Очистить недавние", "Список и обложки. Сами файлы не удаляются", p.danger)
    clear.addView(ImageView(ctx).apply { setImageDrawable(Ui.icon(ctx, Icon.DELETE, p.danger)) })
    clear.setOnClickListener {
        Ui.showChoice(activity, "Очистить список недавних?", "Обложки тоже удалятся. Сами PDF-файлы останутся на месте.",
            listOf("Отмена" to {}, "Очистить" to {
                RecentStore(activity).clear()
                ThumbnailStore(activity).clear()
                onChanged()
                Ui.toast(activity, "Список недавних очищен")
            }))
    }
    box.addView(clear)

    // ---- О приложении
    box.addView(Ui.sectionHeader(ctx, "О приложении"))
    val version = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "—"
    } catch (_: Exception) { "—" }
    val about = LinearLayout(ctx)
    about.orientation = LinearLayout.HORIZONTAL
    about.gravity = Gravity.CENTER_VERTICAL
    about.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), Ui.dp(ctx, 8))
    val logo = ImageView(ctx)
    logo.setImageDrawable(Ui.icon(ctx, Icon.PDF_FILE, p.primary))
    logo.scaleType = ImageView.ScaleType.CENTER
    logo.background = Ui.roundRect(ctx, Tokens.primaryContainer(p), 14f)
    about.addView(logo, LinearLayout.LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48)).apply { marginEnd = Ui.dp(ctx, 16) })
    val col = LinearLayout(ctx)
    col.orientation = LinearLayout.VERTICAL
    col.addView(Ui.body(ctx, "PDF Ридер · версия $version").apply { typeface = Tokens.FONT_MEDIUM })
    col.addView(Ui.label(ctx, "Без рекламы и без доступа к интернету").apply { setPadding(0, Ui.dp(ctx, 2), 0, 0) })
    about.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    box.addView(about)
    box.addView(Ui.label(ctx, "Сторонние библиотеки не используются — только встроенные средства Android (лицензия Apache 2.0).")
        .apply { setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 4), Ui.dp(ctx, 20), Ui.dp(ctx, 8)); typeface = Tokens.FONT_REGULAR })

    dialog = Ui.showSheet(activity, "Настройки", box)
}
