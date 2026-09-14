package com.guardianlayer.app.privacy

import org.junit.Assert.*
import org.junit.Test

class GuardianInsightsTest {
    @Test fun emptyHistoryDoesNotPromiseSafety() {
        val result = GuardianInsights.summarize(0, 0).joinToString()
        assertTrue(result.contains("Not enough evidence"))
        assertTrue(result.contains("does not mean no tracking"))
    }
    @Test fun smallSampleDoesNotReportPercentage() {
        val result = GuardianInsights.summarize(49, 20).joinToString()
        assertTrue(result.contains("Early sample"))
        assertFalse(result.contains("%"))
    }
    @Test fun thresholdReportsDnsShareNotDanger() {
        val result = GuardianInsights.summarize(50, 25).joinToString()
        assertTrue(result.contains("50%"))
        assertTrue(result.contains("not a share of all traffic"))
        assertTrue(result.contains("not proof"))
    }
    @Test fun zeroBlocksIsNotProofOfSafety() {
        assertTrue(GuardianInsights.summarize(100, 0).joinToString().contains("does not prove"))
    }
    @Test fun invalidCountsSuppressPercentage() {
        listOf(-1L to 0L, 10L to -1L, 10L to 11L, 0L to 1L).forEach { (total, blocked) ->
            val result = GuardianInsights.summarize(total, blocked).joinToString()
            assertTrue(result.contains("inconsistent"))
            assertFalse(result.contains("%"))
        }
    }
    @Test fun largeCountersDoNotOverflow() {
        assertTrue(GuardianInsights.summarize(Long.MAX_VALUE, Long.MAX_VALUE).first().startsWith("100%"))
    }
}
