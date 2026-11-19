package com.sfl.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class HomeTab(val id: String, val name: String, val url: String)

/** The built-in Home tab is separate; these are the user's additional tabs, in display order. */
object HomeTabs {
    private const val PREFS_FILE = "home_tabs"
    private const val TABS_KEY = "tabs"

    @Suppress("DEPRECATION")
    fun initialize(context: Context) {
        val storage = prefs(context)
        if (storage.contains(TABS_KEY) && Prefs.hasHomeUrl(context)) return
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val freshInstall = info.firstInstallTime == info.lastUpdateTime
        Prefs.initializeHomeUrl(context, freshInstall)
        initialTabs(storage.contains(TABS_KEY), freshInstall)?.let { save(context, it) }
    }

    internal fun initialTabs(hasSavedTabs: Boolean, freshInstall: Boolean): List<HomeTab>? = when {
        hasSavedTabs -> null
        !freshInstall -> emptyList()
        else -> listOf(
            HomeTab("default-sfl-world", "sfl.world", "https://sfl.world/"),
            HomeTab("default-sfl-wiki", "Sfl Wiki", "https://wiki.sfl.world/en/home"),
        )
    }

    fun list(context: Context): List<HomeTab> {
        val saved = prefs(context).getString(TABS_KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(saved) }.getOrNull() ?: return emptyList()
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

    private fun save(context: Context, tabs: List<HomeTab>) {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(JSONObject().put("id", tab.id).put("name", tab.name).put("url", tab.url))
        }
        prefs(context).edit().putString(TABS_KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
