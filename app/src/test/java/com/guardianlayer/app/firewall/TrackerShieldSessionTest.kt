package com.guardianlayer.app.firewall

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerShieldSessionTest {

    @Test
    fun exactWatchRoutesOnlyTheWatchedApp() {
        val session = TrackerShieldSession.vpnPackages(
            saved = setOf("com.chat", "com.mail", "com.maps"),
            exactWatchPackage = "com.mail",
            selfPackage = "com.guardianlayer.app"
        )
        assertEquals(setOf("com.mail"), session)
    }

    @Test
    fun exactWatchCanTargetAnAppThatIsNotSaved() {
        val session = TrackerShieldSession.vpnPackages(
            saved = setOf("com.chat"),
            exactWatchPackage = "com.browser",
            selfPackage = "com.guardianlayer.app"
        )
        assertEquals(setOf("com.browser"), session)
    }

    @Test
    fun withoutAWatchTheSavedSetIsTheSession() {
        val session = TrackerShieldSession.vpnPackages(
            saved = setOf("com.chat", "com.mail", " ", "com.guardianlayer.app"),
            exactWatchPackage = null,
            selfPackage = "com.guardianlayer.app"
        )
        assertEquals(setOf("com.chat", "com.mail"), session)
    }

    @Test
    fun blankWatchDoesNotNarrowTheSession() {
        val session = TrackerShieldSession.vpnPackages(
            saved = setOf("com.chat", "com.mail"),
            exactWatchPackage = "  ",
            selfPackage = "com.guardianlayer.app"
        )
        assertEquals(setOf("com.chat", "com.mail"), session)
    }

    @Test
    fun guardianItselfIsNeverTheWatchTarget() {
        val session = TrackerShieldSession.vpnPackages(
            saved = setOf("com.chat"),
            exactWatchPackage = "com.guardianlayer.app",
            selfPackage = "com.guardianlayer.app"
        )
        assertEquals(setOf("com.chat"), session)
    }
}
