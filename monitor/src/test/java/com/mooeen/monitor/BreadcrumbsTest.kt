package com.mooeen.monitor

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BreadcrumbsTest {
    @After fun clear() { Breadcrumbs.clear() }

    @Test fun crossPlatformLabelsRejectRequestDataAndCredentials() {
        val input = requireNotNull(javaClass.getResource("/cross-sdk-breadcrumbs.json")).readText()
        val rows = JSONArray(input)
        for (i in 0 until rows.length()) {
            Breadcrumbs.clear()
            val row = rows.getJSONObject(i)
            Breadcrumbs.operation(row.getString("type"), row.getString("name"))
            assertEquals("fixture $i", if (row.getBoolean("accepted")) 1 else 0, Breadcrumbs.snapshot().length())
        }
    }
    @Test fun repeatedRequestsKeepPreviousTransitionsAndSnapshotsStayOrdered() {
        Breadcrumbs.operation("sync", "reader.identity.ok")
        repeat(1000) { Breadcrumbs.operation("sync", "reader.changes.timeout") }
        assertEquals(2, Breadcrumbs.snapshot().length())
        val frozen = Breadcrumbs.snapshot().toString()
        repeat(35) { Breadcrumbs.operation("sync", "reader.phase_$it.ok") }
        val tail = Breadcrumbs.snapshot()
        assertEquals(30, tail.length())
        assertEquals("reader.phase_5.ok", tail.getJSONObject(0).getString("name"))
        assertEquals("reader.phase_34.ok", tail.getJSONObject(29).getString("name"))
        assertEquals(2, JSONArray(frozen).length())
    }
    @Test fun existingScreenOccurrencesAreNotCoalesced() {
        Breadcrumbs.add("screen", "ReaderActivity")
        Breadcrumbs.add("screen", "ReaderActivity")
        assertEquals(2, Breadcrumbs.snapshot().length())
    }
}
