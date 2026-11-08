package uk.imad7x.home

import android.content.Context
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Packages (as "pkg@userSerial") that currently have notifications; fed by [Notifs]. */
object Dots {
    @Volatile var set: Set<String> = emptySet()
    var onChange: (() -> Unit)? = null
    fun update(s: Set<String>) { if (s != set) { set = s; onChange?.invoke() } }
}

object Icons {
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }

    fun get(k: String): Bitmap? = cache.get(k)
    fun clear() = cache.evictAll()

    fun load(a: App, k: String, px: Int, dpi: Int, mono: Boolean, accent: Int, done: (Bitmap) -> Unit) = pool.execute {
        val b = try { render(a, px, dpi, mono, accent) } catch (_: Exception) { return@execute }
        cache.put(k, b)
        done(b)
    }

    private fun render(a: App, px: Int, dpi: Int, mono: Boolean, accent: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val d = a.info.getIcon(dpi) // not badged: clone/work apps get our own mark below
        val m = (d as? AdaptiveIconDrawable)?.monochrome
        if (!mono) {
            d.setBounds(0, 0, px, px); d.draw(c)
        } else if (m != null) {
            m.mutate().setTint(accent)
            AdaptiveIconDrawable(ColorDrawable(0xFF121212.toInt()), m).apply { setBounds(0, 0, px, px) }.draw(c)
        } else {
            val tmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px); d.draw(Canvas(tmp))
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            p.alpha = 217
            c.drawBitmap(tmp, 0f, 0f, p)
            tmp.recycle()
        }
        if (a.kind != ' ') {
            val r = px * 0.2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = accent
            c.drawCircle(px - r, px - r, r, p)
            p.color = Color.BLACK; p.textAlign = Paint.Align.CENTER; p.textSize = r * 1.5f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(a.kind.toString(), px - r, px - r + r * 0.5f, p)
        }
        return out
    }
}

/** App icon, loaded off the UI thread, with a notification dot. */
class AppIcon(c: Context) : ImageView(c) {
    private var want = ""
    private var dotKey: String? = null
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun bind(a: App, px: Int, cfg: Config) {
        dotKey = "${a.pkg}@${a.serial}"
        dot.color = cfg.accentInt
        val k = "${a.key}|${cfg.iconStyle}|$px|${cfg.accent}"
        want = k
        val hit = Icons.get(k)
        if (hit != null) { setImageBitmap(hit); return }
        setImageDrawable(null)
        Icons.load(a, k, px, resources.displayMetrics.densityDpi, cfg.mono, cfg.accentInt) { b -> post { if (want == k) setImageBitmap(b) } }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (dotKey in Dots.set) {
            val r = width * 0.1f
            c.drawCircle(width - r - 1, r + 1, r, dot)
        }
    }
}
