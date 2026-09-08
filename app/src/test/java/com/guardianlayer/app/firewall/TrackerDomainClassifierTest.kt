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
    fun classifiesAdvertisingVendorSubdomain() {
        val result = TrackerDomainClassifier.classify("a.example.applovin.com")

        assertNotNull(result)
        assertEquals("Advertising", result!!.category)
        assertEquals("AppLovin", result.provider)
    }

    @Test
    fun classifiesAmazonAdsObservedFromPhysicalTest() {
        val result = TrackerDomainClassifier.classify("web-video.ads.aps.amazon-adsystem.com")

        assertNotNull(result)
        assertEquals("Advertising", result!!.category)
        assertEquals("Amazon Ads", result.provider)
        assertEquals("amazon-adsystem.com", result.matchedDomain)
    }

    @Test
    fun classifiesSessionReplayVendor() {
        val result = TrackerDomainClassifier.classify("edge.fullstory.com")

        assertNotNull(result)
        assertEquals("Session replay", result!!.category)
        assertEquals("FullStory", result.provider)
    }

    @Test
    fun doesNotMatchLookalikeSuffix() {
        assertNull(TrackerDomainClassifier.classify("notdoubleclick.net"))
        assertNull(TrackerDomainClassifier.classify("exampleapp-measurement.com"))
        assertNull(TrackerDomainClassifier.classify("notamazon-adsystem.com"))
    }

    @Test
    fun doesNotFlagUnlistedDomain() {
        assertNull(TrackerDomainClassifier.classify("example.com"))
    }
}
