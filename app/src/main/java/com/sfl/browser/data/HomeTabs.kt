package com.sfl.browser.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class HomeTab(val id: String, val name: String, val url: String)

/** The built-in Home tab is separate; these are the user's additional tabs, in display order. */
object HomeTabs {
    private const val PREFS_FILE = "home_tabs"
    private const val TABS_KEY = "tabs"
    private const val STARTER_TABS_KEY = "calculatorWikiStarterTabsApplied"
    private val legacyDefaults = setOf(
        HomeTab("default-sfl-world", "sfl.world", "https://sfl.world/"),
        HomeTab("default-sfl-wiki", "Sfl Wiki", "https://wiki.sfl.world/en/home"),
    )
    private val starterTabs = listOf(
        HomeTab("starter-sfl-calculator", "SFL Calculator", "https://www.sfl-calculator.com/"),
        HomeTab("starter-sfl-wiki", "SFL Wiki", "https://wiki.sfl.world/"),
    )

    fun initialize(context: Context) {
        val storage = prefs(context)
        if (storage.getBoolean(STARTER_TABS_KEY, false)) return
        val saved = readTabs(context) ?: return
        Prefs.initializeStarterHomeUrl(context)
        save(context, withStarterTabs(saved), markStarterTabsApplied = true)
    }

    internal fun withStarterTabs(saved: List<HomeTab>): List<HomeTab> {
        val remaining = saved.filterNot { it in legacyDefaults }.toMutableList()
        val starters = starterTabs.map { starter ->
            val index = remaining.indexOfFirst { it.name == starter.name && it.url == starter.url }
            if (index >= 0) remaining.removeAt(index) else starter
        }
        return starters + remaining
    }

    fun list(context: Context): List<HomeTab> = readTabs(context).orEmpty()

    private fun readTabs(context: Context): List<HomeTab>? {
        val saved = prefs(context).getString(TABS_KEY, null) ?: return emptyList()
        val array = try {
            JSONArray(saved)
        } catch (_: JSONException) {
            Log.w("HomeTabs", "Saved tab data is malformed")
            return null
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val name = item.optString("name")
            val url = item.optString("url")
            if (id.isBlank() || name.isBlank() || !isValidUrl(url)) null else HomeTab(id, name, url)
        }.distinctBy(HomeTab::id)
    }

    fun isValidUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme
        return (scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }

    fun add(context: Context, name: String, url: String) {
        require(name.isNotBlank() && isValidUrl(url))
        save(context, list(context) + HomeTab(UUID.randomUUID().toString(), name.trim(), url.trim()))
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    fun edit(context: Context, id: String, name: String, url: String) {
        require(name.isNotBlank() && isValidUrl(url))
        save(context, list(context).map {
            if (it.id == id) it.copy(name = name.trim(), url = url.trim()) else it
        })
    }

    private fun save(context: Context, tabs: List<HomeTab>, markStarterTabsApplied: Boolean = false) {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(JSONObject().put("id", tab.id).put("name", tab.name).put("url", tab.url))
        }
        val edit = prefs(context).edit().putString(TABS_KEY, array.toString())
        if (markStarterTabsApplied) edit.putBoolean(STARTER_TABS_KEY, true)
        edit.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
