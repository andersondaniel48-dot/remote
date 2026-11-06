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

    // ----- per-TV app catalog (names + artwork paths), pins and usage -----

    fun loadCatalog(tvId: String): List<TvApp> {
        val raw = prefs.getString("catalog_$tvId", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                TvApp(
                    packageName = o.getString("pkg"),
                    component = o.optString("component").ifEmpty { null },
                    name = o.getString("name"),
                    banner = o.optString("banner").ifEmpty { null },
                    icon = o.optString("icon").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveCatalog(tvId: String, apps: List<TvApp>, hasArtwork: Boolean) {
        val a = JSONArray()
        apps.forEach {
            a.put(JSONObject().put("pkg", it.packageName).put("component", it.component ?: "").put("name", it.name)
                .put("banner", it.banner ?: "").put("icon", it.icon ?: ""))
        }
        prefs.edit().putString("catalog_$tvId", a.toString()).putBoolean("artwork_$tvId", hasArtwork)
            .putLong("catalogAt_$tvId", System.currentTimeMillis()).apply()
    }

    fun catalogHasArtwork(tvId: String) = prefs.getBoolean("artwork_$tvId", false)
    fun catalogAge(tvId: String) = System.currentTimeMillis() - prefs.getLong("catalogAt_$tvId", 0L)

    /** Favorite apps for a TV, in the order you added them. */
    fun pins(tvId: String): List<String> {
        prefs.getString("favs_$tvId", null)?.let { raw ->
            return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        }
        // Older versions stored an unordered set.
        return prefs.getStringSet("pins_$tvId", null)?.toList() ?: emptyList()
    }
    fun setPins(tvId: String, pins: List<String>) { prefs.edit().putString("favs_$tvId", JSONArray(pins).toString()).apply() }

    fun usage(tvId: String): Map<String, Int> {
        val raw = prefs.getString("usage_$tvId", null) ?: return emptyMap()
        return runCatching { JSONObject(raw).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } }.getOrDefault(emptyMap())
    }

    fun bumpUsage(tvId: String, pkg: String): Map<String, Int> {
        val m = usage(tvId).toMutableMap()
        m[pkg] = (m[pkg] ?: 0) + 1
        prefs.edit().putString("usage_$tvId", JSONObject(m as Map<*, *>).toString()).apply()
        return m
    }

    fun forgetTv(tvId: String) {
        prefs.edit().remove("catalog_$tvId").remove("artwork_$tvId").remove("catalogAt_$tvId")
            .remove("pins_$tvId").remove("favs_$tvId").remove("usage_$tvId").apply()
    }

    fun recentSearches(): List<String> {
        val raw = prefs.getString("searches", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun addRecentSearch(q: String) {
        val list = (listOf(q) + recentSearches().filter { !it.equals(q, ignoreCase = true) }).take(10)
        prefs.edit().putString("searches", JSONArray(list).toString()).apply()
    }

    fun clearRecentSearches() { prefs.edit().remove("searches").apply() }

    fun guide(): List<GuideItem> {
        val raw = prefs.getString("guide", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                GuideItem(
                    id = o.getString("id"), title = o.getString("title"), url = o.getString("url"),
                    image = o.optString("image").ifEmpty { null }, service = o.optString("service").ifEmpty { null },
                    addedAt = o.optLong("addedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveGuide(items: List<GuideItem>) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)
                .put("image", it.image ?: "").put("service", it.service ?: "").put("addedAt", it.addedAt))
        }
        prefs.edit().putString("guide", a.toString()).apply()
    }

    var volumeKeysControlTv: Boolean
        get() = prefs.getBoolean("volKeys", true)
        set(v) { prefs.edit().putBoolean("volKeys", v).apply() }

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
