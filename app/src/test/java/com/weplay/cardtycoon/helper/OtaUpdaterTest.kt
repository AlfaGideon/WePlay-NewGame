package com.weplay.cardtycoon.helper

import com.weplay.cardtycoon.helper.update.OtaUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaUpdaterTest {

    @Test
    fun comparesSemanticVersionsAccurately() {
        assertTrue(OtaUpdater.compareSemver("1.1.5", "1.1.4") > 0)
        assertTrue(OtaUpdater.compareSemver("1.2.0", "1.1.99") > 0)
        assertTrue(OtaUpdater.compareSemver("2.0.0", "1.9.9") > 0)
        assertEquals(0, OtaUpdater.compareSemver("1.1.0", "1.1.0"))
        assertTrue(OtaUpdater.compareSemver("1.1.0", "1.1.1") < 0)
    }
}
