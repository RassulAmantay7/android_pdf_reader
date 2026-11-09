package com.rassul.pdfreader.reader.screen

import android.content.Context
import com.rassul.pdfreader.core.annot.Annotation
import com.rassul.pdfreader.core.annot.HighlightAnnotation
import com.rassul.pdfreader.core.annot.InkAnnotation
import com.rassul.pdfreader.core.annot.NoteAnnotation
import com.rassul.pdfreader.core.annot.PtPoint
import com.rassul.pdfreader.core.annot.PtRect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Черновик несохранённых пометок (FR-39): JSON во внутреннем хранилище
 * (filesDir/drafts/<sha1(docKey)>.json). Пишется в onPause, удаляется после сохранения
 * или «Не сохранять». Вызывать с фонового потока.
 */
internal class DraftStore(context: Context) {
    private val dir = File(context.filesDir, "drafts")

    private fun file(docKey: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(docKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }

    fun save(docKey: String, items: List<Annotation>) {
        if (items.isEmpty()) { delete(docKey); return }
        dir.mkdirs()
        val arr = JSONArray()
        for (a in items) arr.put(encode(a) ?: continue)
        val root = JSONObject().put("v", 1).put("key", docKey).put("items", arr)
        val f = file(docKey)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun load(docKey: String): List<Annotation> {
        val f = file(docKey)
        if (!f.exists()) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            if (root.optString("key") != docKey) return emptyList()
            val arr = root.getJSONArray("items")
            (0 until arr.length()).mapNotNull { decode(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun delete(docKey: String) { file(docKey).delete() }

    private fun pts(list: List<PtPoint>): JSONArray {
        val a = JSONArray()
        for (p in list) { a.put(p.x.toDouble()); a.put(p.y.toDouble()) }
        return a
    }

    private fun encode(a: Annotation): JSONObject? = when (a) {
        is InkAnnotation -> JSONObject().put("t", "ink").put("p", a.pageIndex).put("c", a.color)
            .put("w", a.widthPt.toDouble()).put("o", a.opacity.toDouble())
            .put("s", JSONArray().also { s -> a.strokes.forEach { s.put(pts(it)) } })
        is HighlightAnnotation -> JSONObject().put("t", "hl").put("p", a.pageIndex).put("c", a.color).put("text", a.text)
            .put("r", JSONArray().also { r ->
                a.rects.forEach { r.put(JSONArray().put(it.left.toDouble()).put(it.top.toDouble()).put(it.right.toDouble()).put(it.bottom.toDouble())) }
            })
        is NoteAnnotation -> JSONObject().put("t", "note").put("p", a.pageIndex).put("c", a.color).put("text", a.text)
            .put("x", a.at.x.toDouble()).put("y", a.at.y.toDouble())
        else -> null
    }

    private fun decode(o: JSONObject): Annotation? = try {
        val page = o.getInt("p")
        val color = o.getInt("c")
        when (o.getString("t")) {
            "ink" -> {
                val s = o.getJSONArray("s")
                val strokes = (0 until s.length()).map { i ->
                    val arr = s.getJSONArray(i)
                    (0 until arr.length() / 2).map { k -> PtPoint(arr.getDouble(2 * k).toFloat(), arr.getDouble(2 * k + 1).toFloat()) }
                }
                InkAnnotation(page, strokes, color, o.getDouble("w").toFloat(), o.optDouble("o", 1.0).toFloat())
            }
            "hl" -> {
                val r = o.getJSONArray("r")
                val rects = (0 until r.length()).map { i ->
                    val q = r.getJSONArray(i)
                    PtRect(q.getDouble(0).toFloat(), q.getDouble(1).toFloat(), q.getDouble(2).toFloat(), q.getDouble(3).toFloat())
                }
                HighlightAnnotation(page, rects, color, o.optString("text", ""))
            }
            "note" -> NoteAnnotation(page, PtPoint(o.getDouble("x").toFloat(), o.getDouble("y").toFloat()), o.optString("text", ""), color)
            else -> null
        }
    } catch (_: Exception) { null }
}
