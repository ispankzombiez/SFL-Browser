package com.sfl.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabsTest {
    @Test
    fun seedsOrderedEditableTabsOnlyOnFreshInstallWithoutSavedTabs() {
        val tabs = checkNotNull(HomeTabs.initialTabs(hasSavedTabs = false, freshInstall = true))
        assertEquals(
            listOf("sfl.world" to "https://sfl.world/", "Sfl Wiki" to "https://wiki.sfl.world/en/home"),
            tabs.map { it.name to it.url },
        )
        assertEquals(tabs.size, tabs.map(HomeTab::id).distinct().size)
        assertTrue(tabs.all { HomeTabs.isValidUrl(it.url) })
        assertEquals(emptyList<HomeTab>(), HomeTabs.initialTabs(hasSavedTabs = false, freshInstall = false))
        assertNull(HomeTabs.initialTabs(hasSavedTabs = true, freshInstall = true))
        assertNull(HomeTabs.initialTabs(hasSavedTabs = true, freshInstall = false))
    }

    @Test
    fun acceptsCompleteWebUrls() {
        assertTrue(HomeTabs.isValidUrl(" https://example.com/tools?q=fish#timers "))
        assertTrue(HomeTabs.isValidUrl("http://localhost:8080/"))
    }

    @Test
    fun rejectsNonWebOrMalformedUrlsAndEmbeddedCredentials() {
        listOf(
            "example.com/timers",
            "https:///timers",
            "javascript:alert(1)",
            "file:///data/example",
            "https://user:password@example.com/",
            "https://example.com:65536/",
        ).forEach { url -> assertFalse(url, HomeTabs.isValidUrl(url)) }
    }
}
