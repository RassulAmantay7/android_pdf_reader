package com.rassul.pdfreader.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/*
 * КОНТРАКТ хранилищ (владелец реализации — агент «ui-designer», т.к. главный экран его).
 * Хранение: SharedPreferences + org.json (входит в Android), обложки — WebP в filesDir/thumbs.
 * Ключ документа (docKey) = uri.toString().
 * Любые битые данные читаются как «пусто» — приложение не падает.
 */

/** page — индекс последней открытой страницы, с 0 (на экране показывается page + 1). */
data class RecentEntry(
    val uri: String,
    val name: String,
    val lastOpened: Long,
    val page: Int,
    val pageCount: Int,
)

/** page — индекс страницы с 0. */
data class Bookmark(val page: Int, val title: String, val created: Long)

/** page — индекс страницы с 0. */
data class ReadingPosition(val page: Int, val offsetFraction: Float, val zoom: Float)

enum class ReadingMode { CONTINUOUS, PAGED }

/** Тема интерфейса (FR-18). */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

private fun prefs(context: Context, name: String): SharedPreferences =
    context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

class RecentStore(context: Context) {
    private val sp = prefs(context, "recents")

    companion object {
        const val MAX = 50
        private const val KEY = "list"
        private val lock = Any()
    }

    fun list(): List<RecentEntry> = synchronized(lock) { read() }

    fun touch(uri: Uri, name: String, pageCount: Int): Unit = synchronized(lock) {
        val key = uri.toString()
        val all = read().toMutableList()
        val old = all.firstOrNull { it.uri == key }
        all.removeAll { it.uri == key }
        all.add(0, RecentEntry(key, name.ifBlank { old?.name ?: "Документ" }, System.currentTimeMillis(),
            (old?.page ?: 0).coerceIn(0, maxOf(0, pageCount - 1)), pageCount))
        write(all)
    }

    /** page — индекс страницы с 0. */
    fun updateProgress(uri: Uri, page: Int, pageCount: Int): Unit = synchronized(lock) {
        val key = uri.toString()
        val all = read().toMutableList()
        val i = all.indexOfFirst { it.uri == key }
        if (i < 0) return
        all[i] = all[i].copy(page = page, pageCount = pageCount)
        write(all)
    }

    fun remove(uri: String): Unit = synchronized(lock) { write(read().filterNot { it.uri == uri }) }

    /** Вернуть запись (например, «Отменить» после удаления). */
    fun restore(e: RecentEntry): Unit = synchronized(lock) {
        write(read().filterNot { it.uri == e.uri } + e)
    }

    fun clear(): Unit = synchronized(lock) { sp.edit().remove(KEY).apply() }

    private fun read(): List<RecentEntry> = try {
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        val out = ArrayList<RecentEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isEmpty()) continue
            out += RecentEntry(uri, o.optString("name", "Документ"), o.optLong("t"), o.optInt("page").coerceAtLeast(0), o.optInt("count").coerceAtLeast(0))
        }
        out.sortedByDescending { it.lastOpened }.distinctBy { it.uri }.take(MAX)
    } catch (_: Exception) { emptyList() }

    private fun write(list: List<RecentEntry>) {
        val arr = JSONArray()
        list.sortedByDescending { it.lastOpened }.take(MAX).forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("t", it.lastOpened).put("page", it.page).put("count", it.pageCount))
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }
}

class ThumbnailStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "thumbs")

    companion object {
        /** Кэш в памяти (~8 МБ) — повторный показ главного экрана мгновенный. */
        private val mem = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun file(uri: String) = File(dir, sha1(uri) + ".webp")

    /** Только из памяти (без чтения диска) — для мгновенного показа. */
    fun cached(uri: String): Bitmap? = mem.get(uri)

    fun get(uri: String): Bitmap? {
        mem.get(uri)?.let { return it }
        return try {
            val f = file(uri)
            if (!f.isFile) null else BitmapFactory.decodeFile(f.path)?.also { mem.put(uri, it) }
        } catch (_: Throwable) { null }
    }

    /** Сохраняет обложку (желательно вызывать не из UI-потока). */
    fun put(uri: String, bitmap: Bitmap) {
        try {
            dir.mkdirs()
            val f = file(uri)
            val tmp = File(dir, f.name + ".tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            mem.put(uri, bitmap)
        } catch (_: Exception) { }
    }

    fun remove(uri: String) {
        mem.remove(uri)
        try { file(uri).delete() } catch (_: Exception) { }
    }

    fun clear() {
        mem.evictAll()
        try { dir.listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
    }
}

class BookmarkStore(context: Context) {
    private val sp = prefs(context, "bookmarks")

    fun list(docKey: String): List<Bookmark> = try {
        val arr = JSONArray(sp.getString(docKey, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Bookmark(it.optInt("page"), it.optString("title"), it.optLong("t")) }
        }.sortedBy { it.page }
    } catch (_: Exception) { emptyList() }

    /** Закладка на ту же страницу заменяется (переименование). */
    fun add(docKey: String, b: Bookmark) = synchronized(this) { write(docKey, list(docKey).filterNot { it.page == b.page } + b) }

    fun remove(docKey: String, page: Int) = synchronized(this) { write(docKey, list(docKey).filterNot { it.page == page }) }

    fun has(docKey: String, page: Int): Boolean = list(docKey).any { it.page == page }

    private fun write(docKey: String, list: List<Bookmark>) {
        if (list.isEmpty()) { sp.edit().remove(docKey).apply(); return }
        val arr = JSONArray()
        list.sortedBy { it.page }.forEach { arr.put(JSONObject().put("page", it.page).put("title", it.title).put("t", it.created)) }
        sp.edit().putString(docKey, arr.toString()).apply()
    }
}

class PositionStore(context: Context) {
    private val sp = prefs(context, "positions")

    fun get(docKey: String): ReadingPosition? = try {
        sp.getString(docKey, null)?.let {
            val o = JSONObject(it)
            ReadingPosition(o.optInt("page").coerceAtLeast(0), o.optDouble("off", 0.0).toFloat(), o.optDouble("zoom", 1.0).toFloat())
        }
    } catch (_: Exception) { null }

    fun put(docKey: String, p: ReadingPosition) {
        val o = JSONObject().put("page", p.page).put("off", p.offsetFraction.toDouble().takeIf { it.isFinite() } ?: 0.0)
            .put("zoom", p.zoom.toDouble().takeIf { it.isFinite() } ?: 1.0)
        sp.edit().putString(docKey, o.toString()).apply()
    }

    fun remove(docKey: String) { sp.edit().remove(docKey).apply() }
}

class SettingsStore(context: Context) {
    private val ctx = context.applicationContext
    private val sp = prefs(context, "settings")

    private fun bool(key: String, def: Boolean) = try { sp.getBoolean(key, def) } catch (_: Exception) { def }

    /** Тема: тёмная (по умолчанию) / светлая / как в системе. */
    var themeMode: ThemeMode
        get() = try { ThemeMode.valueOf(sp.getString("theme", ThemeMode.DARK.name)!!) } catch (_: Exception) { ThemeMode.DARK }
        set(v) { sp.edit().putString("theme", v.name).apply() }

    /** Тёмная тема интерфейса (по умолчанию true). Учитывает режим «как в системе». */
    var darkTheme: Boolean
        get() = when (themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
        }
        set(v) { themeMode = if (v) ThemeMode.DARK else ThemeMode.LIGHT }

    /** Ночной режим страниц — инверсия (по умолчанию false). */
    var nightPages: Boolean
        get() = bool("nightPages", false); set(v) { sp.edit().putBoolean("nightPages", v).apply() }

    var readingMode: ReadingMode
        get() = try { ReadingMode.valueOf(sp.getString("mode", ReadingMode.CONTINUOUS.name)!!) } catch (_: Exception) { ReadingMode.CONTINUOUS }
        set(v) { sp.edit().putString("mode", v.name).apply() }

    var keepScreenOn: Boolean
        get() = bool("keepOn", false); set(v) { sp.edit().putBoolean("keepOn", v).apply() }
}
