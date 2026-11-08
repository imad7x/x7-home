package uk.imad7x.home

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

/** Where a long-pressed app sits: a group (index, position in the config list) or the dock (group = -1). */
class Where(val group: Int, val idx: Int)

/** One entry of a long-press menu. */
class MI(val text: String, val dim: Boolean = false, val act: () -> Unit)

private class Ov(val view: View, val onClose: () -> Unit)

class MainActivity : Activity() {
    companion object { @Volatile var live: MainActivity? = null }

    lateinit var cfg: Config
    lateinit var apps: Apps
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var groupsBox: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var results: AppList
    private lateinit var prompt: Prompt
    private lateinit var clock: android.widget.TextView
    private lateinit var date: android.widget.TextView
    private lateinit var info: android.widget.TextView
    private val overlays = ArrayList<Ov>()
    private var allFill: ((Boolean) -> Unit)? = null
    private var batt: Intent? = null
    private var tx = 0f; private var ty = 0f
    private var atTop = true; private var atEnd = true
    private var loading = false; private var again = false

    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val dm = SimpleDateFormat("EEE d MMM", Locale.ENGLISH)
    private val dh = SimpleDateFormat("EEE HH:mm", Locale.ENGLISH)

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_BATTERY_CHANGED) batt = i; status() }
    }
    private val reloadApps = Runnable { Icons.clear(); loadApps() }
    private val callback = object : LauncherApps.Callback() {
        private fun go() { ui.removeCallbacks(reloadApps); ui.postDelayed(reloadApps, 400) }
        override fun onPackageRemoved(p: String?, u: UserHandle?) = go()
        override fun onPackageAdded(p: String?, u: UserHandle?) = go()
        override fun onPackageChanged(p: String?, u: UserHandle?) = go()
        override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = go()
        override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = go()
    }
    private val fling by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.rawY - e1.rawY; val dx = e2.rawX - e1.rawX
                if (abs(dy) < dp(90) || abs(dy) < abs(dx) * 1.5f || abs(vy) < 700) return false
                if (dy < 0 && atEnd) openAllApps() else if (dy > 0 && atTop) expandShade()
                return false
            }
        }).apply { setIsLongpressEnabled(false) }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fonts.init(this)
        @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
        cfg = Config(File(configDir(this), "config.json"))
        apps = Apps(this)
        loadConfig()
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); isFocusableInTouchMode = true; defaultFocusHighlightEnabled = false
            setOnApplyWindowInsetsListener { v, w ->
                val bars = w.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = w.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            }
        }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        rebuild()
        root.requestFocus()
        apps.la.registerCallback(callback, ui)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        Dots.onChange = { runOnUiThread { dots() } }
        live = this
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            listOf(Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BATTERY_CHANGED,
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED).forEach { addAction(it) }
        }
        batt = registerReceiver(tick, f, Context.RECEIVER_NOT_EXPORTED)
        status()
    }

    override fun onResume() {
        super.onResume()
        if (cfg.file.lastModified() != cfg.mtime) { loadConfig(); rebuild() }
        if (apps.all.isEmpty() && !loading) loadApps()
    }

    override fun onStop() {
        unregisterReceiver(tick)
        resetHome(false)
        super.onStop()
    }

    override fun onDestroy() {
        apps.la.unregisterCallback(callback)
        if (live === this) { live = null; Dots.onChange = null }
        bg.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.action == Intent.ACTION_MAIN && Intent.CATEGORY_HOME in (i.categories ?: emptySet())) resetHome(true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            tx = ev.rawX; ty = ev.rawY
            val l = IntArray(2); scroll.getLocationOnScreen(l)
            val inside = ty >= l[1] && ty <= l[1] + scroll.height
            // inside the groups a swipe scrolls; it only opens the shade at the very top and
            // all apps when there is nothing to scroll (header and dock always work)
            atTop = !inside || scroll.scrollY == 0
            atEnd = !inside || (scroll.getChildAt(0)?.height ?: 0) <= scroll.height
        }
        if (overlays.isEmpty() && results.visibility != View.VISIBLE && !prompt.edit.hasFocus()) fling.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun back() {
        if (overlays.isNotEmpty()) popTop()
        else if (prompt.query.isNotEmpty() || prompt.edit.hasFocus()) clearSearch()
    }

    private fun resetHome(top: Boolean) {
        while (overlays.isNotEmpty()) popTop()
        clearSearch()
        if (top) scroll.scrollTo(0, 0)
    }

    private fun clearSearch() {
        prompt.edit.setText("")
        root.requestFocus()
        hideKeyboard()
    }

    private fun hideKeyboard() { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0) }

    // ---- config and apps ----------------------------------------------------------------------

    /** Reads config.json; writes a default one on first run. A broken file keeps the old settings. */
    private fun loadConfig() {
        if (!cfg.file.exists()) {
            if (cfg.mtime == 0L) { cfg.groups.clear(); cfg.groups += Group("apps", 0, ArrayList()); cfg.dock.clear() }
            commitQuiet() // first run: a default file; deleted while running: write the current state back
        } else if (!cfg.load()) {
            cfg.mtime = cfg.file.lastModified()
            try { cfg.file.copyTo(File(cfg.file.parentFile, "config.json.bad"), true) } catch (_: Exception) {}
            toast("config.json is not valid JSON (copy kept as config.json.bad), keeping the old settings")
        }
    }

    private fun commitQuiet() { try { cfg.save() } catch (_: Exception) { toast("can't write config.json") } }

    /** Called by the adb RELOAD broadcast and by the settings sheet. */
    fun reload() { loadConfig(); rebuild(); loadApps() }

    fun loadApps() {
        if (loading) { again = true; return }
        loading = true
        bg.execute {
            val l = try { apps.build() } catch (_: Exception) { null }
            runOnUiThread {
                loading = false
                if (l != null) { apps.set(l); renderGroups(); renderDock(); refreshLists() }
                if (again) { again = false; loadApps() }
            }
        }
    }

    private fun commit() { commitQuiet(); renderGroups(); renderDock(); refreshLists() }

    private fun refreshLists() {
        if (results.visibility == View.VISIBLE) onQuery(prompt.query, true)
        allFill?.invoke(true)
    }

    fun labelOf(a: App): String =
        if (cfg.names.isEmpty()) a.label else (cfg.names[apps.entryOf(a)] ?: cfg.names[a.pkg] ?: a.label)

    // ---- home screen --------------------------------------------------------------------------

    private fun rebuild() {
        val q = if (::prompt.isInitialized) prompt.query else ""
        val acc = cfg.accentInt
        col.removeAllViews()
        col.setPadding(dp(16), dp(8), dp(16), 0)

        clock = tv(this, "", 56f, TEXT, Fonts.light).apply {
            letterSpacing = -.03f
            setOnClickListener { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        }
        date = tv(this, "", 14f, TEXT, Fonts.med).apply {
            letterSpacing = .08f; setPadding(0, dp(2), 0, dp(4))
            setOnClickListener {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")))
                    || start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            }
        }
        info = tv(this, "", 12f, DIM)
        val gear = tv(this, "⚙\uFE0E", 19f, DIM).apply {
            gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(24), dp(10)); setOnClickListener { openSettings() }
        }
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(info); addView(gear) }
        col.addView(clock); col.addView(date); col.addView(statusRow)

        prompt = Prompt(this).also { p -> p.onQuery = { onQuery(it, false) }; p.onGo = { goTop() } }
        col.addView(prompt, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })

        val body = FrameLayout(this)
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        groupsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        scroll.addView(groupsBox)
        results = AppList(this).apply { visibility = View.GONE }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        body.addView(results, FrameLayout.LayoutParams(-1, -1))
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        col.addView(View(this).apply { setBackgroundColor(HAIR) }, LinearLayout.LayoutParams(-1, 1))
        dock = LinearLayout(this).apply { weightSum = 5f }
        val all = tv(this, "≡", 28f, acc).apply {
            gravity = Gravity.CENTER; background = pressed(acc); setOnClickListener { openAllApps() }
        }
        val dockRow = LinearLayout(this).apply {
            addView(dock, LinearLayout.LayoutParams(0, -1, 1f))
            addView(all, LinearLayout.LayoutParams(dp(56), -1))
        }
        col.addView(dockRow, LinearLayout.LayoutParams(-1, dp(68)))

        renderGroups(); renderDock(); status()
        if (q.isNotEmpty()) prompt.edit.setText(q)
    }

    private fun renderGroups() {
        groupsBox.removeAllViews()
        var n = 0
        cfg.groups.forEachIndexed { gi, g ->
            groupsBox.addView(sectionView(this, g.name, cfg.accentInt).apply {
                setPadding(0, dp(16), 0, dp(8)); setOnLongClickListener { groupMenu(gi); true }
            })
            val cols = if (g.columns > 0) g.columns else cfg.columns
            val list = g.apps.mapIndexedNotNull { i, e -> apps.find(e)?.let { i to it } }
            n += list.size
            for (chunk in list.chunked(cols)) {
                val row = LinearLayout(this)
                chunk.forEach { (i, a) -> row.addView(cell(a, Where(gi, i), cols), LinearLayout.LayoutParams(0, -2, 1f)) }
                repeat(cols - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                groupsBox.addView(row)
            }
        }
        if (n == 0 && cfg.dock.isEmpty() && apps.all.isNotEmpty()) {
            groupsBox.addView(tv(this, "// nothing here yet. tap ≡, long-press an app, add it to a group. or push config.json (see README)", 12f, DIM)
                .apply { setPadding(0, dp(14), 0, 0) })
        }
    }

    private fun cell(a: App, w: Where, cols: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(7), dp(2), dp(7)); background = pressed(cfg.accentInt)
        val px = fit(cfg.iconSize, cols)
        addView(AppIcon(context).also { it.bind(a, px, cfg) }, LinearLayout.LayoutParams(px, px))
        if (cfg.labels) addView(tv(context, labelOf(a), 10f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { launch(a, it) }
        setOnLongClickListener { appMenu(a, it, w); true }
    }

    /** Icon size in px, never wider than one of [slots] equal cells across the screen. */
    private fun fit(sizeDp: Int, slots: Int, taken: Int = 0) =
        minOf(dp(sizeDp), (resources.displayMetrics.widthPixels - dp(32 + taken)) / slots - dp(6))

    private fun renderDock() {
        dock.removeAllViews()
        val px = minOf(fit(cfg.iconSize, 5, 56), dp(56))
        cfg.dock.mapIndexedNotNull { i, e -> apps.find(e)?.let { i to it } }.take(5).forEach { (i, a) ->
            val c = FrameLayout(this).apply {
                background = pressed(cfg.accentInt)
                addView(AppIcon(context).also { it.bind(a, px, cfg) }, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
                setOnClickListener { launch(a, it) }
                setOnLongClickListener { appMenu(a, it, Where(-1, i)); true }
            }
            dock.addView(c, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun status() {
        if (!::clock.isInitialized) return
        val now = Date()
        val zone = TimeZone.getDefault()
        listOf(hm, dm, dh).forEach { it.timeZone = zone }
        clock.text = hm.format(now); date.text = dm.format(now).uppercase()
        val parts = ArrayList<String>()
        batt?.let { b ->
            val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (pct >= 0) parts += "bat $pct%" + if (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) " ⚡" else ""
        }
        getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
            val soon = it.triggerTime - now.time < 20 * 3600_000L
            parts += "alarm " + (if (soon) hm else dh).format(Date(it.triggerTime))
        }
        info.text = parts.joinToString("") { "$it · " }
    }

    private fun dots() {
        fun walk(v: View) { if (v is AppIcon) v.invalidate() else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
    }

    // ---- search and all apps ------------------------------------------------------------------

    private fun onQuery(q: String, keep: Boolean) {
        val on = q.isNotBlank()
        scroll.visibility = if (on) View.GONE else View.VISIBLE
        results.visibility = if (on) View.VISIBLE else View.GONE
        if (on) results.show(searchItems(q), keep)
    }

    private fun goTop() { results.items.firstOrNull { it.kind != 0 }?.let { activate(it, null) } }

    fun activate(i: Item, v: View?) { if (i.app != null) launch(i.app, v) else i.run() }

    private fun initials(s: String) = buildString {
        var prev = ' '
        for (c in s) {
            if (c.isLetterOrDigit() && (!prev.isLetterOrDigit() || (c.isUpperCase() && prev.isLowerCase()))) append(c.lowercaseChar())
            prev = c
        }
    }

    /** 0 prefix, 1 word start, 2 substring, 3 initials ("yt" finds YouTube); -1 no match. */
    private fun rank(label: String, q: String): Int {
        val l = label.lowercase()
        return when {
            l.startsWith(q) -> 0
            l.contains(" $q") -> 1
            l.contains(q) -> 2
            initials(label).startsWith(q) -> 3
            else -> -1
        }
    }

    private fun searchItems(raw: String): List<Item> {
        val s = raw.trim(); val q = s.lowercase()
        val out = ArrayList<Item>()
        if (q.startsWith(":")) {
            listOf<Pair<String, () -> Unit>>(":settings" to { openSettings() }, ":reload" to { reload() }, ":apps" to { openAllApps() })
                .filter { it.first.startsWith(q) }.forEach { (k, f) -> out += Item(2, k) { f() } }
        }
        apps.all.mapNotNull { a -> rank(labelOf(a), q).takeIf { it >= 0 }?.let { it to a } }
            .sortedWith(compareBy({ it.first }, { labelOf(it.second).lowercase() }))
            .forEach { out += Item(1, app = it.second) }
        val web = SpannableStringBuilder("search web: ").apply {
            setSpan(ForegroundColorSpan(DIM), 0, length, 0)
            val n = length; append(s); setSpan(ForegroundColorSpan(cfg.accentInt), n, length, 0)
        }
        out += Item(2, web) { webSearch(s) }
        return out
    }

    private fun webSearch(q: String) {
        if (!start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)))
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }

    private fun az(): List<Item> {
        val out = ArrayList<Item>(); var last = ' '
        for (a in apps.all.sortedBy { labelOf(it).lowercase() }) {
            val c = labelOf(a).firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
            if (c != last) { out += Item(0, c.toString()); last = c }
            out += Item(1, app = a)
        }
        return out
    }

    fun openAllApps() {
        if (allFill != null) return
        val p = Prompt(this); val list = AppList(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(p, LinearLayout.LayoutParams(-1, dp(46)))
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val v = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true; addView(c, FrameLayout.LayoutParams(-1, -1)) }
        val fill = { keep: Boolean -> val q = p.query; list.show(if (q.isBlank()) az() else searchItems(q), keep) }
        p.onQuery = { fill(false) }
        p.onGo = { list.items.firstOrNull { it.kind != 0 }?.let { activate(it, null) } }
        allFill = fill
        push(v) { allFill = null; hideKeyboard(); root.requestFocus() }
        fill(false)
    }

    // ---- launching ----------------------------------------------------------------------------

    fun launch(a: App, v: View?) {
        val r = v?.let { val l = IntArray(2); it.getLocationOnScreen(l); Rect(l[0], l[1], l[0] + it.width, l[1] + it.height) }
        try { apps.la.startMainActivity(a.comp, a.user, r, null) } catch (_: Exception) { toast("can't open ${a.label}"); loadApps() }
    }

    private fun start(i: Intent): Boolean = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }

    private fun expandShade() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Throwable) { toast("the OS refused to open the shade") }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    // ---- overlays: menus, text prompts, settings ---------------------------------------------

    private fun push(v: View, onClose: () -> Unit = {}): Ov {
        val o = Ov(v, onClose); overlays += o
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.alpha = 0f; v.animate().alpha(1f).setDuration(120)
        return o
    }

    private fun pop(o: Ov) { if (overlays.remove(o)) { root.removeView(o.view); o.onClose() } }
    private fun popTop() { overlays.lastOrNull()?.let(::pop) }

    private inner class Menu(private val title: String?) {
        private val ax = tx; private val ay = ty // where the finger was when the menu opened
        private val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val card = ScrollView(this@MainActivity).apply {
            addView(list); isVerticalScrollBarEnabled = false; isClickable = true
            background = frame(0xFF0B0B0B.toInt(), fade(cfg.accentInt, .45f), 1)
        }
        private val shade = FrameLayout(this@MainActivity).apply { setBackgroundColor(0x99000000.toInt()); addView(card) }
        private val ov = push(shade)
        val open get() = ov in overlays

        init { shade.setOnClickListener { pop(ov) } }

        fun set(items: List<MI>) {
            list.removeAllViews()
            title?.let { list.addView(tv(this@MainActivity, "// $it", 10f, DIM).apply { setPadding(dp(14), dp(10), dp(14), dp(2)); maxLines = 1 }) }
            for (m in items) list.addView(tv(this@MainActivity, m.text, 14f, if (m.dim) DIM else TEXT).apply {
                setPadding(dp(14), dp(11), dp(14), dp(11)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                background = pressed(cfg.accentInt); setOnClickListener { pop(ov); m.act() }
            })
            place()
        }

        /** Near the finger, kept on screen. */
        private fun place() {
            val w = root.width - root.paddingLeft - root.paddingRight
            val h = root.height - root.paddingTop - root.paddingBottom
            val cw = minOf(dp(240), w - dp(24))
            card.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((h * .75f).toInt(), MeasureSpec.AT_MOST))
            val ch = card.measuredHeight
            val at = IntArray(2); root.getLocationOnScreen(at)
            val y0 = ay - at[1] - root.paddingTop
            val x = (ax - at[0] - root.paddingLeft - cw / 2).toInt().coerceIn(dp(12), maxOf(dp(12), w - cw - dp(12)))
            val y = (if (y0 > h / 2) y0 - ch - dp(28) else y0 + dp(28)).toInt().coerceIn(dp(12), maxOf(dp(12), h - ch - dp(12)))
            card.layoutParams = FrameLayout.LayoutParams(cw, ch).apply { leftMargin = x; topMargin = y }
        }
    }

    private fun menu(title: String?, items: List<MI>): Menu = Menu(title).also { it.set(items) }

    /** A one-line text input on top of everything. [ok] gets the trimmed text (maybe empty). */
    private fun ask(title: String, init: String, ok: (String) -> Unit) {
        val acc = cfg.accentInt
        val ed = EditText(this).apply {
            setText(init); setSelection(text.length); setSingleLine(); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextColor(TEXT); typeface = Fonts.reg; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            background = frame(Color.BLACK, HAIR, 1); setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        lateinit var o: Ov
        val done = { val s = ed.text.toString().trim(); pop(o); ok(s) }
        fun btn(s: String, color: Int, f: () -> Unit) = tv(this, s, 14f, color).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10)); background = pressed(acc); setOnClickListener { f() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(dp(16), dp(14), dp(16), dp(6))
            background = frame(0xFF0B0B0B.toInt(), fade(acc, .45f), 1)
            addView(tv(this@MainActivity, "// $title", 11f, acc).apply { setPadding(0, 0, 0, dp(10)) })
            addView(ed)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.END
                addView(btn("cancel", DIM) { pop(o) }); addView(btn("ok", acc) { done() })
            })
        }
        val shade = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt()); isClickable = true
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) })
        }
        ed.setOnEditorActionListener { _, _, _ -> done(); true }
        o = push(shade) { hideKeyboard(); root.requestFocus() }
        ed.requestFocus()
        ui.post { getSystemService(InputMethodManager::class.java).showSoftInput(ed, 0) }
    }

    // ---- long-press menus ---------------------------------------------------------------------

    private fun swap(list: MutableList<*>, i: Int, j: Int) { Collections.swap(list, i, j); commit() }

    /** Index of the next entry in [d] direction that still resolves to an installed app, or -1. */
    private fun neighbor(list: List<String>, i: Int, d: Int): Int {
        var j = i + d
        while (j in list.indices) { if (apps.find(list[j]) != null) return j; j += d }
        return -1
    }

    private fun shortcuts(a: App): List<ShortcutInfo> = try {
        val q = LauncherApps.ShortcutQuery().setPackage(a.pkg).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        apps.la.getShortcuts(q, a.user)?.filter { it.isEnabled }?.distinctBy { it.id }?.take(5) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun appMenu(a: App, v: View?, w: Where?) {
        val items = ArrayList<MI>()
        val inGroups = cfg.groups.filter { g -> g.apps.any { apps.same(it, a) } }
        val inDock = cfg.dock.any { apps.same(it, a) }
        items += MI("Add to group ▸") { groupPicker(a) }
        if (inGroups.isNotEmpty()) items += MI("Remove from group") {
            val only = if (w != null && w.group >= 0) listOf(cfg.groups[w.group]) else inGroups
            if (only.size == 1) removeFrom(only[0], a)
            else menu("remove from", only.map { g -> MI(g.name) { removeFrom(g, a) } })
        }
        if (w != null) {
            val list = if (w.group >= 0) cfg.groups[w.group].apps else cfg.dock
            if (w.idx < list.size) {
                if (neighbor(list, w.idx, -1) >= 0) items += MI("Move left") { swap(list, w.idx, neighbor(list, w.idx, -1)) }
                if (neighbor(list, w.idx, 1) >= 0) items += MI("Move right") { swap(list, w.idx, neighbor(list, w.idx, 1)) }
            }
        }
        if (inDock) items += MI("Unpin from dock") { cfg.dock.removeAll { apps.same(it, a) }; commit() }
        else items += MI("Pin to dock") { if (cfg.dock.size >= 5) toast("dock is full (5)") else { cfg.dock += apps.entryOf(a); commit() } }
        items += MI("Rename label") {
            ask("rename ${a.label}", labelOf(a)) { s ->
                val k = apps.entryOf(a)
                if (s.isEmpty() || s == a.label) cfg.names.remove(k) else cfg.names[k] = s
                commit()
            }
        }
        items += MI("App info") { try { apps.la.startAppDetailsActivity(a.comp, a.user, null, null) } catch (_: Exception) { toast("no app info") } }
        if (!a.system) items += MI("Uninstall") {
            start(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).putExtra("android.intent.extra.USER", a.user))
        }
        val m = menu(labelOf(a), items)
        bg.execute {
            val sc = shortcuts(a)
            if (sc.isNotEmpty()) runOnUiThread {
                if (m.open) m.set(sc.map { s ->
                    MI("› " + (s.shortLabel ?: s.longLabel ?: s.id)) {
                        try { apps.la.startShortcut(s, null, null) } catch (_: Exception) { toast("can't start shortcut") }
                    }
                } + items)
            }
        }
    }

    private fun removeFrom(g: Group, a: App) { g.apps.removeAll { apps.same(it, a) }; commit() }

    private fun groupPicker(a: App) {
        val entry = apps.entryOf(a)
        val items = cfg.groups.filter { g -> g.apps.none { apps.same(it, a) } }.map { g -> MI(g.name) { g.apps += entry; commit() } }
        menu("add to group", items + MI("new group…", true) {
            ask("new group", "") { s -> if (s.isNotEmpty()) { cfg.groups += Group(s, 0, arrayListOf(entry)); commit() } }
        })
    }

    private fun groupMenu(gi: Int) {
        val g = cfg.groups.getOrNull(gi) ?: return
        val items = ArrayList<MI>()
        items += MI("Rename") { ask("rename group", g.name) { s -> if (s.isNotEmpty()) { g.name = s; commit() } } }
        if (gi > 0) items += MI("Move up") { swap(cfg.groups, gi, gi - 1) }
        if (gi < cfg.groups.size - 1) items += MI("Move down") { swap(cfg.groups, gi, gi + 1) }
        items += MI("Columns… (${if (g.columns > 0) g.columns else "default"})") {
            menu("columns", (listOf(0) + (3..7)).map { n -> MI(if (n == 0) "default (${cfg.columns})" else "$n") { g.columns = n; commit() } })
        }
        items += MI("Delete group") {
            menu("delete ${g.name}?", listOf(MI("yes, delete (apps stay in all apps)") { cfg.groups.remove(g); commit() }, MI("cancel", true) {}))
        }
        menu(g.name, items)
    }

    // ---- settings sheet -----------------------------------------------------------------------

    private fun dotsOn() = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, Notifs::class.java))

    fun openSettings() {
        if (overlays.any { it.view.tag == "settings" }) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(12)) }
        val sheet = ScrollView(this).apply { addView(box); isClickable = true }
        val shade = FrameLayout(this).apply {
            tag = "settings"; setBackgroundColor(0x99000000.toInt())
            addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        lateinit var o: Ov

        fun fill() {
            val acc = cfg.accentInt
            box.removeAllViews()
            sheet.background = frame(Color.BLACK, fade(acc, .45f), 1)
            fun edit(f: () -> Unit) { f(); commitQuiet(); rebuild(); fill() }
            fun choice(label: String, opts: List<String>, sel: Int, colors: List<Int>? = null, pick: (Int) -> Unit) {
                box.addView(tv(this, label, 10f, DIM).apply { setPadding(0, dp(10), 0, 0) })
                box.addView(LinearLayout(this).apply {
                    opts.forEachIndexed { i, s ->
                        addView(tv(this@MainActivity, if (i == sel) "[$s]" else " $s ", 13f, if (i == sel) acc else (colors?.get(i) ?: TEXT)).apply {
                            setPadding(0, dp(9), dp(8), dp(9)); setOnClickListener { pick(i) }
                        })
                    }
                })
            }
            fun act(label: String, f: () -> Unit) = box.addView(tv(this, "> $label", 14f).apply {
                setPadding(0, dp(12), 0, dp(12)); background = pressed(acc); setOnClickListener { pop(o); f() }
            })

            box.addView(tv(this, "// x7 settings", 11f, acc, Fonts.med))
            choice("accent", ACCENTS.keys.toList(), ACCENTS.values.indexOfFirst { it.equals(cfg.accent, true) }, ACCENTS.values.map { Color.parseColor(it) }) { i ->
                edit { cfg.accent = ACCENTS.values.elementAt(i) }
            }
            choice("icon style", listOf("color", "mono"), if (cfg.mono) 1 else 0) { i -> edit { cfg.iconStyle = if (i == 1) "mono" else "color" } }
            val sizes = listOf(40, 46, 54)
            choice("icon size", listOf("S", "M", "L"), sizes.indexOf(cfg.iconSize)) { i -> edit { cfg.iconSize = sizes[i] } }
            val colsOpt = listOf(4, 5, 6)
            choice("columns", colsOpt.map { "$it" }, colsOpt.indexOf(cfg.columns)) { i -> edit { cfg.columns = colsOpt[i] } }
            choice("labels", listOf("on", "off"), if (cfg.labels) 0 else 1) { i -> edit { cfg.labels = i == 0 } }
            box.addView(View(this).apply { setBackgroundColor(HAIR) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(8) })
            act("notification dots  " + if (dotsOn()) "[on]" else "[off]") { start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            act("apply AMOLED wallpapers") {
                bg.execute {
                    val ok = try { Wallpaper.set(this, cfg.accentInt) } catch (_: Throwable) { false }
                    runOnUiThread { toast(if (ok) "wallpapers set" else "wallpaper failed") }
                }
            }
            act("set as default home") {
                start(Intent(Settings.ACTION_HOME_SETTINGS)) || start(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            act("reload config") { reload(); toast("config reloaded") }
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
            box.addView(tv(this, "x7 $ver  ${cfg.file.path.removePrefix("/storage/emulated/0")}", 10f, DIM).apply { setPadding(0, dp(8), 0, 0) })
        }

        shade.setOnClickListener { pop(o) }
        o = push(shade)
        fill()
    }
}
