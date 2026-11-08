package uk.imad7x.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

const val TEXT = 0xFFE6E6E6.toInt()
const val DIM = 0xFF7A7A7A.toInt()
const val HAIR = 0xFF1C1C1C.toInt()

object Fonts {
    lateinit var reg: Typeface; lateinit var med: Typeface; lateinit var bold: Typeface; lateinit var light: Typeface
    fun init(c: Context) {
        if (::reg.isInitialized) return
        reg = c.resources.getFont(R.font.jbmono_regular); med = c.resources.getFont(R.font.jbmono_medium)
        bold = c.resources.getFont(R.font.jbmono_bold); light = c.resources.getFont(R.font.jbmono_light)
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int) = context.dp(v)
fun fade(c: Int, a: Float) = (c and 0xFFFFFF) or ((a * 255).toInt() shl 24)

fun tv(c: Context, s: CharSequence = "", sp: Float = 14f, color: Int = TEXT, tf: Typeface = Fonts.reg) = TextView(c).apply {
    text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); typeface = tf; includeFontPadding = false
}

fun frame(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply { setColor(fill); setStroke(w, stroke) }

fun pressed(accent: Int) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(fade(accent, .18f)))
    addState(intArrayOf(), ColorDrawable(0))
}

/** `// name ────────` (clipped to the view's width by a one-line TextView). */
fun section(name: CharSequence, accent: Int) = SpannableStringBuilder("// $name ").apply {
    setSpan(ForegroundColorSpan(accent), 0, length, 0)
    val n = length
    append("─".repeat(80))
    setSpan(ForegroundColorSpan(fade(accent, .35f)), n, length, 0)
}

fun sectionView(c: Context, name: CharSequence, accent: Int) = tv(c, section(name, accent), 11f, accent, Fonts.med).apply {
    letterSpacing = .06f; maxLines = 1; setHorizontallyScrolling(true)
}

/** `imad@x200:~$ query▌` in a thin box. The real (invisible) EditText sits on top and takes the typing. */
class Prompt(private val h: MainActivity) : FrameLayout(h) {
    val edit = EditText(h)
    private val shown = tv(h, "", 15f)
    private val accent = h.cfg.accentInt
    private var on = true
    var onQuery: (String) -> Unit = {}
    var onGo: () -> Unit = {}
    val query get() = edit.text.toString()

    private val blink = object : Runnable {
        override fun run() { on = !on; paint(); postDelayed(this, 530) }
    }

    init {
        background = frame(Color.BLACK, fade(accent, .35f), dp(1))
        setPadding(dp(12), 0, dp(12), 0)
        shown.gravity = Gravity.CENTER_VERTICAL
        shown.maxLines = 1; shown.ellipsize = TextUtils.TruncateAt.START
        with(edit) {
            background = null; setPadding(0, 0, 0, 0)
            setTextColor(Color.TRANSPARENT); isCursorVisible = false; isLongClickable = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Fonts.reg
            setSingleLine(); importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ -> onGo(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { on = true; paint(); onQuery(s.toString()) }
            })
            setOnFocusChangeListener { _, f ->
                this@Prompt.background = frame(Color.BLACK, if (f) accent else fade(accent, .35f), dp(1))
                if (f) post { h.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) }
            }
        }
        addView(shown, LayoutParams(-1, -1))
        addView(edit, LayoutParams(-1, -1))
    }

    private fun paint() {
        val s = SpannableStringBuilder("${h.cfg.prompt} ")
        s.setSpan(ForegroundColorSpan(accent), 0, s.length, 0)
        val q = query
        if (q.isNotEmpty()) { val a = s.length; s.append(q); s.setSpan(ForegroundColorSpan(TEXT), a, s.length, 0) }
        val c = s.length
        s.append("▌"); s.setSpan(ForegroundColorSpan(if (on) accent else Color.TRANSPARENT), c, s.length, 0)
        shown.text = s
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); paint(); postDelayed(blink, 530) }
    override fun onDetachedFromWindow() { removeCallbacks(blink); super.onDetachedFromWindow() }
}

/** A row in [AppList]: kind 0 = section letter, 1 = app, 2 = action (web search, command). */
class Item(val kind: Int, val text: CharSequence = "", val app: App? = null, val run: () -> Unit = {})

/** Icon + label rows, used by search results and the all-apps screen. */
class AppList(private val h: MainActivity) : ListView(h) {
    var items: List<Item> = emptyList(); private set
    private val accent = h.cfg.accentInt

    private class Row(c: MainActivity) : LinearLayout(c) {
        val icon = AppIcon(c); val name = tv(c, "", 15f); val tag = tv(c, "", 10f, DIM)
        init {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(0, dp(6), dp(4), dp(6))
            addView(icon, LayoutParams(dp(38), dp(38)))
            addView(name, LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            addView(tag)
            name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.END
        }
    }

    private val ad = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(p: Int) = items[p].kind
        override fun isEnabled(p: Int) = items[p].kind != 0
        override fun areAllItemsEnabled() = false
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val m = items[p]
            return when (m.kind) {
                0 -> (v as? TextView ?: sectionView(h, "", accent).apply { setPadding(0, dp(16), 0, dp(4)) }).apply { text = section(m.text, accent) }
                1 -> (v as? Row ?: Row(h).apply { background = pressed(accent) }).apply {
                    val a = m.app!!
                    icon.bind(a, dp(38), h.cfg)
                    name.text = h.labelOf(a)
                    tag.text = when (a.kind) { 'c' -> "clone"; 'w' -> "work"; else -> "" }
                }
                else -> (v as? TextView ?: tv(h, "", 14f).apply {
                    minimumHeight = dp(52); gravity = Gravity.CENTER_VERTICAL; background = pressed(accent)
                }).apply { text = m.text }
            }
        }
    }

    init {
        divider = null; dividerHeight = 0; selector = ColorDrawable(0); cacheColorHint = 0
        overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        adapter = ad
        setOnItemClickListener { _, v, p, _ -> h.activate(items[p], v) }
        setOnItemLongClickListener { _, v, p, _ -> items[p].app?.let { h.appMenu(it, v, null); true } ?: false }
    }

    /** [keep]: refresh in place without jumping back to the top. */
    fun show(list: List<Item>, keep: Boolean = false) { items = list; ad.notifyDataSetChanged(); if (!keep) setSelection(0) }
}
