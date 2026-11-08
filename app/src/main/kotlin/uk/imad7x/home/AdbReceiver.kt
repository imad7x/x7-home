package uk.imad7x.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Only adb shell / system can send these (the manifest requires the DUMP permission):
 *   uk.imad7x.home.RELOAD      re-read config.json
 *   uk.imad7x.home.WALLPAPERS  apply the AMOLED wallpapers
 *   uk.imad7x.home.DUMP_APPS   write apps.json (+ users.json) next to config.json
 */
class AdbReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (i.action) {
                    "uk.imad7x.home.RELOAD" -> MainActivity.live?.let { a -> a.runOnUiThread { a.reload() } }
                    "uk.imad7x.home.WALLPAPERS" -> {
                        val cfg = Config(File(configDir(c), "config.json")).also { it.load() }
                        Wallpaper.set(c, cfg.accentInt)
                    }
                    "uk.imad7x.home.DUMP_APPS" -> dump(c)
                }
            } catch (_: Throwable) { /* nothing to report to; adb sees the result code only */ } finally { pending.finish() }
        }.start()
    }

    private fun dump(c: Context) {
        val a = Apps(c)
        val list = a.build()
        a.set(list)
        val out = JSONArray()
        for (x in list) {
            out.put(JSONObject().put("package", x.pkg).put("activity", x.cls).put("label", x.label).put("user", x.serial)
                .put("profile", when (x.kind) { ' ' -> "main"; 'c' -> "clone"; else -> "work" }).put("entry", a.entryOf(x)))
        }
        val dir = configDir(c)
        writeAtomic(File(dir, "apps.json"), out.toString(1))
        writeAtomic(File(dir, "users.json"), a.profiles.toString(1))
    }
}
