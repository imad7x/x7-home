package uk.imad7x.home

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Color
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

val ACCENTS = linkedMapOf("mint" to "#3CFFB0", "cyan" to "#36D7FF", "amber" to "#FFB547", "red" to "#FF5A5F", "white" to "#FFFFFF")
const val MINT = 0xFF3CFFB0.toInt()

/** Writes next to the target and renames, so a reader never sees half a file. */
fun writeAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
}

fun configDir(c: Context): File = (c.getExternalFilesDir(null) ?: c.filesDir).apply { mkdirs() }

class Group(var name: String, var columns: Int, val apps: MutableList<String>)

/** config.json. Unknown keys are kept, so the lab can add its own. */
class Config(val file: File) {
    private var raw = JSONObject()
    var accent = "#3CFFB0"
    var iconStyle = "color"
    var iconSize = 46
    var columns = 5
    var labels = true
    var prompt = "imad@x200:~$"
    val groups = ArrayList<Group>()
    val dock = ArrayList<String>()
    val names = LinkedHashMap<String, String>()
    var mtime = 0L

    val accentInt: Int get() = try { Color.parseColor(accent) } catch (_: IllegalArgumentException) { MINT }
    val mono: Boolean get() = iconStyle == "mono"

    /** False (and nothing changed) when the file is missing or not valid JSON. */
    fun load(): Boolean {
        val j = try { JSONObject(file.readText()) } catch (_: Exception) { return false }
        raw = j
        accent = j.optString("accent", "#3CFFB0")
        iconStyle = if (j.optString("iconStyle") == "mono") "mono" else "color"
        iconSize = j.optInt("iconSize", 46).coerceIn(24, 72)
        columns = j.optInt("columns", 5).coerceIn(3, 8)
        labels = j.optBoolean("labels", true)
        prompt = j.optString("prompt", "imad@x200:~$")
        groups.clear()
        val gs = j.optJSONArray("groups")
        for (i in 0 until (gs?.length() ?: 0)) {
            val g = gs!!.optJSONObject(i) ?: continue
            groups += Group(g.optString("name", "group"), g.optInt("columns", 0).coerceIn(0, 8), strings(g.optJSONArray("apps")))
        }
        dock.clear(); dock += strings(j.optJSONArray("dock"))
        names.clear()
        j.optJSONObject("labels_override")?.let { o -> o.keys().forEach { k -> names[k] = o.optString(k) } }
        mtime = file.lastModified()
        return true
    }

    fun save() {
        raw.put("version", 1).put("accent", accent).put("iconStyle", iconStyle).put("iconSize", iconSize)
            .put("columns", columns).put("labels", labels).put("prompt", prompt)
        val gs = JSONArray()
        groups.forEach { g ->
            gs.put(JSONObject().put("name", g.name).apply { if (g.columns > 0) put("columns", g.columns) }.put("apps", JSONArray(g.apps)))
        }
        raw.put("groups", gs).put("dock", JSONArray(dock)).put("labels_override", JSONObject(names))
        writeAtomic(file, raw.toString(2))
        mtime = file.lastModified()
    }

    private fun strings(a: JSONArray?): MutableList<String> {
        val out = ArrayList<String>()
        for (i in 0 until (a?.length() ?: 0)) a!!.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }
}

/** One launcher activity in one user profile. [kind]: ' ' main, 'c' clone, 'w' work. [ord] is the system's order. */
class App(val info: LauncherActivityInfo, val user: UserHandle, val serial: Long, val kind: Char, val ord: Int) {
    val comp: ComponentName = info.componentName
    val pkg: String = comp.packageName
    val cls: String = comp.className
    val label: String = info.label.toString()
    val lower: String = label.lowercase()
    val key = "$pkg/$cls@$serial"
    val system get() = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
}

/** Every launchable activity of every profile except the private space. */
class Apps(ctx: Context) {
    val la: LauncherApps = ctx.getSystemService(LauncherApps::class.java)
    private val um: UserManager = ctx.getSystemService(UserManager::class.java)
    private val me: UserHandle = Process.myUserHandle()
    private val self = ctx.packageName
    val mainSerial = um.getSerialNumberForUser(me)

    var all: List<App> = emptyList(); private set
    private var byKey = emptyMap<String, App>()
    private var first = emptyMap<String, App>()

    /** One entry per profile seen by the last [build]: serial, user, raw type, listed. Written to users.json by DUMP_APPS. */
    @Volatile var profiles = JSONArray(); private set

    /** Slow (loads labels): call off the UI thread, then pass the result to [set]. */
    fun build(): List<App> {
        val out = ArrayList<App>()
        val seen = JSONArray()
        for (u in um.userProfiles) {
            val type = typeOf(u)
            val serial = um.getSerialNumberForUser(u)
            // A private-space profile is never listed. A profile whose type is unknown is kept: LauncherApps
            // returns nothing for profiles this app may not access anyway.
            val listed = type != UserManager.USER_TYPE_PROFILE_PRIVATE
            seen.put(JSONObject().put("serial", serial).put("user", u.toString()).put("type", type ?: "?").put("listed", listed))
            if (!listed) continue
            val kind = if (u == me) ' ' else if (type == "legacy" || type?.endsWith("MANAGED") == true) 'w' else 'c'
            try {
                for (i in la.getActivityList(null, u)) if (i.componentName.packageName != self) out += App(i, u, serial, kind, out.size)
            } catch (_: Exception) { /* locked or vanished profile */ }
        }
        profiles = seen
        return out.sortedWith(compareBy({ it.lower }, { it.serial }))
    }

    fun set(list: List<App>) {
        all = list
        byKey = list.associateBy { it.key }
        first = HashMap<String, App>().also { m -> list.forEach { m.merge("${it.pkg}@${it.serial}", it) { o, n -> if (n.ord < o.ord) n else o } } }
    }

    /** The profile's raw user type ("" for the main user, null when LauncherApps will not say). */
    private fun typeOf(u: UserHandle): String? {
        if (u == me) return ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return "legacy"
        return try { la.getLauncherUserInfo(u)?.userType } catch (_: Exception) { null }
    }

    /** "pkg", "pkg/activity" or either followed by "@userSerial". */
    fun find(entry: String): App? {
        var e = entry.trim()
        var serial = mainSerial
        val at = e.lastIndexOf('@')
        if (at > 0) { serial = e.substring(at + 1).toLongOrNull() ?: return null; e = e.substring(0, at) }
        if ('/' !in e) return first["$e@$serial"]
        val cn = ComponentName.unflattenFromString(e) ?: return null
        return byKey["${cn.packageName}/${cn.className}@$serial"]
    }

    /** The shortest config entry that finds [a] again. */
    fun entryOf(a: App): String {
        val base = if (first["${a.pkg}@${a.serial}"]?.key == a.key) a.pkg else "${a.pkg}/${a.cls}"
        return if (a.serial == mainSerial) base else "$base@${a.serial}"
    }

    /** True when config [entry] names [a] (by key, so an App object from before a reload still matches). */
    fun same(entry: String, a: App) = find(entry)?.key == a.key
}
