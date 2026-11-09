package com.rassul.pdfreader.ui

import java.util.Calendar

/** Форматирование для интерфейса (русский язык). */
object Format {
    private val MONTHS = arrayOf("янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек.")

    /** Русское множественное число: plural(3, "день", "дня", "дней") → "дня". */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n) % 100
        val b = a % 10
        return when {
            a in 11..14 -> many
            b == 1L -> one
            b in 2..4 -> few
            else -> many
        }
    }

    /** «только что», «5 минут назад», «сегодня, 14:05», «вчера», «3 дня назад», «12 окт.», «12 окт. 2025». */
    fun relativeTime(t: Long, now: Long = System.currentTimeMillis()): String {
        if (t <= 0) return ""
        val diff = now - t
        if (diff < 60_000L) return "только что"
        if (diff < 3_600_000L) {
            val m = diff / 60_000L
            return "$m ${plural(m, "минуту", "минуты", "минут")} назад"
        }
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val days = dayIndex(today) - dayIndex(c)
        return when {
            days <= 0 -> "сегодня, %02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            days == 1L -> "вчера"
            days < 7 -> "$days ${plural(days, "день", "дня", "дней")} назад"
            c.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
            else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
        }
    }

    private fun dayIndex(c: Calendar): Long {
        val local = c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)
        return Math.floorDiv(local, 86_400_000L)
    }

    /** «стр. 12 из 240» (page — индекс с 0). */
    fun pages(page: Int, count: Int): String =
        if (count <= 0) "Ещё не прочитан" else "стр. ${(page + 1).coerceIn(1, count)} из $count"
}
