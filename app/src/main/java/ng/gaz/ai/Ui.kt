package ng.gaz.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** Small helpers to build the dark Gaz-styled screens in code. */
object Ui {
    val BG = 0xFF0B0D10.toInt()
    val CARD = 0xFF14171C.toInt()
    val CARD2 = 0xFF1B1F26.toInt()
    val ORANGE = 0xFFFF7A1A.toInt()
    val GREEN = 0xFF22C55E.toInt()
    val RED = 0xFFFF3B30.toInt()
    val DIM = 0xFF8B94A3.toInt()

    fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Activity.round(color: Int, r: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    fun Activity.lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun Activity.col(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun Activity.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun Activity.card() = col(14).apply { background = round(CARD, 14) }

    fun Activity.heading(t: String, size: Float = 22f) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = size; typeface = Typeface.DEFAULT_BOLD
    }

    fun Activity.label(t: String, color: Int = DIM, size: Float = 13f) = TextView(this).apply {
        text = t; setTextColor(color); textSize = size; setPadding(0, dp(6), 0, dp(4))
    }

    fun Activity.section(t: String) = TextView(this).apply {
        text = t.uppercase(); setTextColor(ORANGE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f; setPadding(0, dp(22), 0, dp(8))
    }

    fun Activity.button(t: String, color: Int = CARD2, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        background = round(color, 12); stateListAnimator = null
        setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener(onClick)
    }

    fun Activity.input(hint: String, value: String, multiLine: Boolean = false, secret: Boolean = false, number: Boolean = false,
                       onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(Color.WHITE); setHintTextColor(0xFF5A6270.toInt())
        background = round(CARD2, 10); setPadding(dp(12), dp(10), dp(12), dp(10)); textSize = 15f
        inputType = when {
            number -> InputType.TYPE_CLASS_NUMBER
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (multiLine) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    fun Activity.spinner(items: List<String>, selected: String, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@spinner, android.R.layout.simple_spinner_dropdown_item, items)
        background = round(CARD2, 10)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setSelection(items.indexOf(selected).coerceAtLeast(0))
        var first = true
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                (v as? TextView)?.setTextColor(Color.WHITE)
                if (first) { first = false; return }
                onPick(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    fun Activity.switch(t: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = on; setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }
}
