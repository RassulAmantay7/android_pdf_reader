package com.rassul.pdfreader.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.animation.PathInterpolator

/*
 * Дизайн-токены. Источник истины — docs/design.md (там же расчёт контраста).
 * Меняете значение здесь — обновите таблицу в design.md.
 */
object Tokens {
    val DARK = Palette(
        bg = 0xFF0F1115.toInt(),
        surface = 0xFF171A21.toInt(),
        surfaceHigh = 0xFF20242D.toInt(),
        primary = 0xFF8B97FF.toInt(),
        onPrimary = 0xFF10132B.toInt(),
        text = 0xFFECEEF4.toInt(),
        textSecondary = 0xFFA4AABA.toInt(),
        outline = 0xFF343A47.toInt(),
        danger = 0xFFFF7A7A.toInt(),
        pageBg = 0xFF0A0B0E.toInt(),
        scrim = 0x99000000.toInt(),
        isDark = true,
    )

    val LIGHT = Palette(
        bg = 0xFFF4F5F9.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceHigh = 0xFFFFFFFF.toInt(),
        primary = 0xFF4A55D4.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        text = 0xFF161922.toInt(),
        textSecondary = 0xFF565C6E.toInt(),
        outline = 0xFFD9DCE5.toInt(),
        danger = 0xFFC42B2B.toInt(),
        pageBg = 0xFFE4E6EC.toInt(),
        scrim = 0x66000000,
        isDark = false,
    )

    // Отступы (dp)
    const val SPACE_XS = 4
    const val SPACE_S = 8
    const val SPACE_M = 12
    const val SPACE_L = 16
    const val SPACE_XL = 24
    const val SPACE_XXL = 32

    // Радиусы (dp)
    const val RADIUS_S = 8f
    const val RADIUS_M = 12f      // поля ввода, чипы
    const val RADIUS_L = 20f      // карточки, панели
    const val RADIUS_XL = 28f     // нижние листы, диалоги
    const val RADIUS_FULL = 999f  // «пилюли»

    // Высоты (тени, dp)
    const val ELEV_CARD = 2f
    const val ELEV_FAB = 6f
    const val ELEV_PANEL = 8f
    const val ELEV_SHEET = 16f

    // Типографика (sp)
    const val TEXT_DISPLAY = 28f
    const val TEXT_TITLE = 20f
    const val TEXT_SUBTITLE = 16f
    const val TEXT_BODY = 15f
    const val TEXT_LABEL = 13f
    const val TEXT_CAPTION = 12f

    // Длительности (мс). Системная настройка «Убрать анимации» обнуляет их автоматически (ValueAnimator).
    const val DUR_FAST = 120L
    const val DUR_STANDARD = 200L
    const val DUR_ENTER = 280L
    const val DUR_EXIT = 200L
    const val DUR_SNACK_SHOW = 2800L
    const val DUR_SNACK_ACTION = 5000L
    const val STAGGER = 35L

    /** Стандартная кривая (M3 standard). */
    val EASE_STANDARD get() = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Появление (M3 emphasized decelerate). */
    val EASE_ENTER get() = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Уход (M3 emphasized accelerate). */
    val EASE_EXIT get() = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    val FONT_REGULAR: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val FONT_MEDIUM: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    val FONT_BOLD: Typeface = Typeface.create(Typeface.DEFAULT, 650, false)

    /** Предустановленные цвета пометок (перо/маркер). */
    val INK_COLORS = listOf(
        0xFFFFD43B.toInt(), // жёлтый
        0xFF69DB7C.toInt(), // зелёный
        0xFF4DABF7.toInt(), // голубой
        0xFFFF6B6B.toInt(), // красный
        0xFFDA77F2.toInt(), // сиреневый
        0xFF212529.toInt(), // чёрный
    )

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Смешивание fg поверх bg с непрозрачностью a. */
    fun blend(fg: Int, bg: Int, a: Float): Int = Color.rgb(
        (Color.red(fg) * a + Color.red(bg) * (1 - a)).toInt(),
        (Color.green(fg) * a + Color.green(bg) * (1 - a)).toInt(),
        (Color.blue(fg) * a + Color.blue(bg) * (1 - a)).toInt(),
    )

    /** «Контейнер» акцента: подложка выбранных элементов. */
    fun primaryContainer(p: Palette): Int = blend(p.primary, p.surfaceHigh, 0.14f)

    /** Цвет ряби. */
    fun ripple(p: Palette): Int = if (p.isDark) 0x33FFFFFF else 0x1F000000
}
