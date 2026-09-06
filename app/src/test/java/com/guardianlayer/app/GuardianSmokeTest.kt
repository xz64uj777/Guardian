package com.guardianlayer.app

import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianSmokeTest {
    @Test
    fun productNameIsStable() {
        assertTrue("Guardian".isNotBlank())
    }
}
