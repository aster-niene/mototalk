package dev.mototalk.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun oneUiVersionFromSemPlatformInt() {
        assertEquals("1.0", oneUiFromSemPlatformInt(100000))
        assertEquals("6.1", oneUiFromSemPlatformInt(150100))
        assertEquals("6.1", oneUiFromSemPlatformInt(150101))
        assertEquals("7.0", oneUiFromSemPlatformInt(160000))
        assertNull(oneUiFromSemPlatformInt(90000))
        assertNull(oneUiFromSemPlatformInt(0))
    }
}
