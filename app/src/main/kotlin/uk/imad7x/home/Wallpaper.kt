package uk.imad7x.home

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.WindowManager

/** AMOLED wallpapers drawn at the real display size: black home, black lock with a faint dot grid and one dim line. */
object Wallpaper {
    private const val PITCH = 56f
    private const val TAG = "x200 // imad7x"

    fun set(c: Context, accent: Int): Boolean {
        val b = c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = minOf(b.width(), b.height()); val h = maxOf(b.width(), b.height())
        val wm = WallpaperManager.getInstance(c)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return false

        val home = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val a = wm.setBitmap(home, null, true, WallpaperManager.FLAG_SYSTEM)
        home.recycle()

        val lock = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val cv = Canvas(lock)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF101010.toInt()
        var y = h * 0.4f
        while (y < h) {
            var x = PITCH / 2
            while (x < w) { cv.drawCircle(x, y, 2f, p); x += PITCH }
            y += PITCH
        }
        p.color = fade(accent, .35f); p.typeface = c.resources.getFont(R.font.jbmono_regular)
        p.textSize = 30f; p.letterSpacing = .08f; p.textAlign = Paint.Align.CENTER
        cv.drawText(TAG, w / 2f, h * 0.915f, p)
        val b2 = wm.setBitmap(lock, null, true, WallpaperManager.FLAG_LOCK)
        lock.recycle()
        return a != 0 && b2 != 0
    }
}
