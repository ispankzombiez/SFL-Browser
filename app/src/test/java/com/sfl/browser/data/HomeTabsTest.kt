package com.sfl.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabsTest {
    @Test
    fun installsStartWithTheThreeRequestedDestinationsInOrder() {
        val tabs = HomeTabs.withStarterTabs(emptyList())
        assertEquals(
            listOf(
                "Home" to "https://sunflower-land.com/play/?ref=iSPANK",
                "SFL Calculator" to "https://www.sfl-calculator.com/",
                "SFL Wiki" to "https://wiki.sfl.world/",
            ),
            listOf("Home" to Prefs.starterHomeUrl(null)) + tabs.map { it.name to it.url },
        )
    }

    @Test
    fun upgradeReplacesUntouchedDefaultsAndKeepsCustomTabsAfterTheStarters() {
        val custom = HomeTab("custom", "Timers", "https://example.com/timers")
        val legacyWorld = HomeTab("default-sfl-world", "sfl.world", "https://sfl.world/")
        val legacyWiki = HomeTab("default-sfl-wiki", "Sfl Wiki", "https://wiki.sfl.world/en/home")
        assertEquals(
            HomeTabs.withStarterTabs(emptyList()) + custom,
            HomeTabs.withStarterTabs(listOf(custom, legacyWorld, legacyWiki)),
        )
        assertEquals("https://sunflower-land.com/play/?ref=iSPANK",
            Prefs.starterHomeUrl("https://sunflower-land.com/play"))
    }

    @Test
    fun upgradePreservesEditedDefaultsAndReusesAnExactStarterWithoutDuplicatingIt() {
        val customHome = "https://sunflower-land.com/play/?ref=another"
        val editedWiki = HomeTab("default-sfl-wiki", "My Wiki", "https://wiki.sfl.world/en/home")
        val calculator = HomeTab("my-calculator", "SFL Calculator", "https://www.sfl-calculator.com/")
        val migrated = HomeTabs.withStarterTabs(listOf(editedWiki, calculator))
        assertEquals(listOf(calculator, HomeTabs.withStarterTabs(emptyList())[1], editedWiki), migrated)
        assertEquals(migrated, HomeTabs.withStarterTabs(migrated))
        assertEquals(customHome, Prefs.starterHomeUrl(customHome))
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
