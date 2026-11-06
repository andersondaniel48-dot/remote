package dev.daniel.tvhub.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves the TV list, recent links and small settings on the phone. */
class TvStore(context: Context) {
    private val prefs = context.getSharedPreferences("tvhub", Context.MODE_PRIVATE)

    fun loadTvs(): List<TvConfig> {
        val raw = prefs.getString("tvs", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TvConfig(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port", 5555),
                    kind = runCatching { TvKind.valueOf(o.getString("kind")) }.getOrDefault(TvKind.FIRE_TV),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveTvs(list: List<TvConfig>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port).put("kind", it.kind.name))
        }
        prefs.edit().putString("tvs", arr.toString()).apply()
    }

    fun recentLinks(): List<String> {
        val raw = prefs.getString("links", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun addRecentLink(url: String) {
        val list = (listOf(url) + recentLinks().filter { it != url }).take(12)
        prefs.edit().putString("links", JSONArray(list).toString()).apply()
    }

    fun removeRecentLink(url: String) {
        prefs.edit().putString("links", JSONArray(recentLinks().filter { it != url }).toString()).apply()
    }

    var selectedTv: String?
        get() = prefs.getString("selected", null)
        set(v) { prefs.edit().putString("selected", v).apply() }

    var keepScreenOnWhileCharging: Boolean
        get() = prefs.getBoolean("keepOn", true)
        set(v) { prefs.edit().putBoolean("keepOn", v).apply() }

    var autoLock: Boolean
        get() = prefs.getBoolean("autoLock", false)
        set(v) { prefs.edit().putBoolean("autoLock", v).apply() }
}
