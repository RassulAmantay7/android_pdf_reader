package com.rassul.pdfreader.reader.view

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache

/** Базовый растр страницы. stamp — «поколение» содержимого (меняется после правки формы/сброса). */
internal class BaseBitmap(val bitmap: Bitmap, val stamp: Long)

/**
 * LRU-кэш базовых растров страниц с ограничением по памяти (байты Bitmap.allocationByteCount).
 * Вытесненные растры освобождаются (recycle) — но не сразу, а следующим сообщением UI-потока:
 * к этому моменту текущий кадр уже записан, а аппаратный рендерер держит собственную ссылку
 * на пиксели (android.graphics.Bitmap: «recycle… frees the native object» — Java-сторона;
 * отрисованный ранее кадр не затрагивается). Все методы — только из UI-потока.
 */
internal class PageBitmapCache(maxBytes: Int) {
    private val main = Handler(Looper.getMainLooper())

    private val lru = object : LruCache<Int, BaseBitmap>(maxOf(1, maxBytes)) {
        override fun sizeOf(key: Int, value: BaseBitmap): Int = value.bitmap.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: BaseBitmap, newValue: BaseBitmap?) {
            if (newValue == null || newValue.bitmap !== oldValue.bitmap) retire(oldValue.bitmap)
        }
    }

    val maxBytes: Int get() = lru.maxSize()

    operator fun get(page: Int): BaseBitmap? = lru.get(page)

    fun put(page: Int, b: BaseBitmap) { lru.put(page, b) }

    fun remove(page: Int) { lru.remove(page) }

    fun clear() { lru.evictAll() }

    /** Освободить растр, который больше не будет рисоваться. */
    fun retire(b: Bitmap) {
        main.post { if (!b.isRecycled) b.recycle() }
    }
}
