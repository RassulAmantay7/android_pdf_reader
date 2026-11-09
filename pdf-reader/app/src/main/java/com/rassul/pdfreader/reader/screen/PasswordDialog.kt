package com.rassul.pdfreader.reader.screen

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.rassul.pdfreader.ui.Ui

/**
 * Диалог ввода пароля. Свой (а не Ui.showPrompt), потому что проверка пароля —
 * асинхронное открытие документа: диалог остаётся открытым, показывает индикатор,
 * а при неверном пароле — ошибку «Неверный пароль» прямо под полем.
 */
internal class PasswordDialog(
    private val activity: Activity,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val dialog = Dialog(activity)
    private val field = EditText(activity)
    private val error = TextView(activity)
    private val progress = ProgressBar(activity)
    private val okButton: TextView
    private var busy = false

    init {
        val c = Ui.colors
        val pad = Ui.dp(activity, 24)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, Ui.dp(activity, 12))
            background = Ui.panelBackground(activity, 28f, c.surfaceHigh)
        }
        box.addView(Ui.title(activity, "Документ защищён паролем"))
        box.addView(Ui.body(activity, "Введите пароль, чтобы открыть файл.", secondary = true).apply {
            setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 12))
        })
        field.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Пароль"
            setTextColor(c.text)
            setHintTextColor(c.textSecondary)
            backgroundTintList = ColorStateList.valueOf(c.primary)
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        box.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        error.apply {
            setTextColor(c.danger)
            textSize = 13f
            visibility = View.GONE
            setPadding(Ui.dp(activity, 4), Ui.dp(activity, 4), 0, 0)
        }
        box.addView(error)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(activity, 16), 0, 0)
        }
        progress.isIndeterminate = true
        progress.indeterminateTintList = ColorStateList.valueOf(c.primary)
        progress.visibility = View.INVISIBLE
        row.addView(progress, LinearLayout.LayoutParams(Ui.dp(activity, 32), Ui.dp(activity, 32)).apply { marginEnd = Ui.dp(activity, 8) })
        row.addView(Ui.button(activity, "Отмена", primary = false) { if (!busy) dialog.cancel() })
        okButton = Ui.button(activity, "Открыть", primary = true) { submit() }
        row.addView(okButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ui.dp(activity, 8)
        })
        box.addView(row)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancel() }
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    private fun submit() {
        if (busy) return
        val pw = field.text?.toString() ?: ""
        if (pw.isEmpty()) { showError("Введите пароль"); return }
        error.visibility = View.GONE
        onSubmit(pw)
    }

    fun show() {
        if (activity.isDestroyed || activity.isFinishing) return
        dialog.show()
        val dm = activity.resources.displayMetrics
        val w = minOf(dm.widthPixels - Ui.dp(activity, 32), Ui.dp(activity, 420))
        dialog.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        field.requestFocus()
    }

    fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        field.isEnabled = !b
        okButton.isEnabled = !b
        okButton.alpha = if (b) 0.5f else 1f
    }

    fun showError(msg: String) {
        error.text = msg
        error.visibility = View.VISIBLE
        field.selectAll()
        field.requestFocus()
    }

    fun dismiss() {
        if (dialog.isShowing) try { dialog.dismiss() } catch (_: Exception) { }
    }
}
