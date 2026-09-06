package com.guardianlayer.app.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackerDomainClassifierTest {

    @Test
    fun classifiesKnownSubdomainWithoutExactHostDependency() {
        val result = TrackerDomainClassifier.classify("api2.branch.io")

        assertNotNull(result)
        assertEquals("Attribution", result!!.category)
        assertEquals("Branch", result.provider)
        assertEquals("branch.io", result.matchedDomain)
    }

    @Test
    fun normalizesCaseAndTrailingDot() {
        val result = TrackerDomainClassifier.classify("WWW.GOOGLE-ANALYTICS.COM.")

        assertNotNull(result)
        assertEquals("Analytics", result!!.category)
    }

    @Test
    fun doesNotFlagUnlistedDomain() {
        assertNull(TrackerDomainClassifier.classify("example.com"))
    }
}
