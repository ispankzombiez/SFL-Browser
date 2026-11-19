package com.sfl.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppearanceTest {
    @Test
    fun layoutPersistsMovingHidingOrderingAndResizingIndividualControls() {
        val layout = BrowserLayout.default
            .move(BrowserControl.ADDRESS, BrowserRow.BOTTOM)
            .move(BrowserControl.REFRESH, BrowserRow.BOTTOM, before = BrowserControl.ADDRESS)
            .move(BrowserControl.OPTIONS, BrowserRow.HIDDEN)
            .resize(BrowserControl.ADDRESS, ControlSize.LARGE)
            .resize(BrowserControl.REFRESH, ControlSize.MEDIUM)
        val restored = BrowserLayout.decode(layout.encode())
        assertEquals(listOf(BrowserControl.REFRESH, BrowserControl.ADDRESS), restored.rows[BrowserRow.BOTTOM])
        assertEquals(listOf(BrowserControl.BACK, BrowserControl.FORWARD, BrowserControl.TABS), restored.rows[BrowserRow.TOP])
        assertEquals(listOf(BrowserControl.OVERVIEW, BrowserControl.TIMELINE, BrowserControl.SETTINGS, BrowserControl.OPTIONS),
            restored.rows[BrowserRow.HIDDEN])
        assertEquals(ControlSize.LARGE, restored.size(BrowserControl.ADDRESS))
        assertEquals(ControlSize.MEDIUM, restored.size(BrowserControl.REFRESH))
    }

    @Test
    fun narrowWindowsWrapWithoutLosingControlsOrShrinkingAnExistingButton() {
        val layout = BrowserLayout.default.resize(BrowserControl.ADDRESS, ControlSize.LARGE)
        assertEquals(listOf(
            listOf(BrowserControl.BACK, BrowserControl.FORWARD, BrowserControl.ADDRESS),
            listOf(BrowserControl.TABS, BrowserControl.OPTIONS),
        ), layout.fittedRows(BrowserRow.TOP, 300))
        assertEquals(listOf(layout.rows.getValue(BrowserRow.TOP)), layout.fittedRows(BrowserRow.TOP, 392))
    }

    @Test
    fun hiddenTrayMakesMissingControlsRecoverableWithoutDuplicatingControls() {
        val restored = BrowserLayout.decode("""{"TOP":["BACK","BACK","NEW"],"BOTTOM":["BACK"],"HIDDEN":["TABS"]}""")
        val flattened = BrowserRow.entries.flatMap { restored.rows[it].orEmpty() }
        assertEquals(BrowserControl.entries.size, flattened.size)
        assertEquals(BrowserControl.entries.toSet(), flattened.toSet())
        assertEquals(listOf(BrowserControl.BACK), restored.rows[BrowserRow.TOP])
        assertEquals(emptyList<BrowserControl>(), restored.rows[BrowserRow.BOTTOM])
    }

    @Test
    fun customHexAcceptsOpaqueRgbAndRejectsBadOrAlphaValues() {
        assertEquals(0xffe4a672.toInt(), parseHexColor("#e4a672"))
        assertEquals(0xff002244.toInt(), parseHexColor("002244"))
        for (invalid in listOf("#123", "#99E4A672", "#12G456", "")) assertNull(parseHexColor(invalid))
    }
}
