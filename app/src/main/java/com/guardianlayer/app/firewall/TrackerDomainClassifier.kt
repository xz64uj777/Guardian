package com.guardianlayer.app.firewall

import java.util.Locale

/**
 * Small, transparent, offline classification layer for domains Guardian can
 * actually observe in plaintext DNS. A match means the hostname belongs to a
 * known analytics/advertising/attribution/telemetry service family; it is not a
 * malware verdict and does not imply the app using it is malicious.
 */
object TrackerDomainClassifier {

    data class Classification(
        val category: String,
        val provider: String,
        val matchedDomain: String,
        val explanation: String
    )

    private data class Rule(
        val domain: String,
        val category: String,
        val provider: String,
        val explanation: String
    )

    private val rules = listOf(
        Rule("google-analytics.com", "Analytics", "Google Analytics", "commonly used for usage and audience measurement"),
        Rule("app-measurement.com", "Analytics", "Google/Firebase Analytics", "commonly used for mobile analytics measurement"),
        Rule("segment.io", "Analytics", "Segment", "commonly used to route product analytics events"),
        Rule("mixpanel.com", "Analytics", "Mixpanel", "commonly used for product and behavioral analytics"),
        Rule("amplitude.com", "Analytics", "Amplitude", "commonly used for product and behavioral analytics"),

        Rule("doubleclick.net", "Advertising", "Google DoubleClick", "commonly used for advertising delivery or measurement"),
        Rule("googlesyndication.com", "Advertising", "Google Ads", "commonly used for advertising delivery"),
        Rule("adservice.google.com", "Advertising", "Google Ads", "commonly used for advertising and conversion measurement"),
        Rule("adsrvr.org", "Advertising", "The Trade Desk", "commonly used for advertising delivery or measurement"),

        Rule("adjust.com", "Attribution", "Adjust", "commonly used to measure app-install and campaign attribution"),
        Rule("appsflyer.com", "Attribution", "AppsFlyer", "commonly used to measure app-install and campaign attribution"),
        Rule("branch.io", "Attribution", "Branch", "commonly used for deep links and campaign attribution"),
        Rule("singular.net", "Attribution", "Singular", "commonly used for marketing attribution"),

        Rule("crashlytics.com", "Crash/Telemetry", "Firebase Crashlytics", "commonly used for crash diagnostics and telemetry"),
        Rule("sentry.io", "Crash/Telemetry", "Sentry", "commonly used for crash, error, and performance telemetry"),
        Rule("bugsnag.com", "Crash/Telemetry", "Bugsnag", "commonly used for crash and error telemetry"),
        Rule("instabug.com", "Crash/Telemetry", "Instabug", "commonly used for crash reports, diagnostics, and feedback telemetry"),

        Rule("connect.facebook.net", "Social tracking", "Meta", "commonly used to load Meta/Facebook web SDK or measurement components")
    )

    fun classify(rawDomain: String): Classification? {
        val domain = rawDomain.trim().trimEnd('.').lowercase(Locale.US)
        if (domain.isBlank()) return null

        val rule = rules.firstOrNull { rule ->
            domain == rule.domain || domain.endsWith(".${rule.domain}")
        } ?: return null

        return Classification(
            category = rule.category,
            provider = rule.provider,
            matchedDomain = rule.domain,
            explanation = rule.explanation
        )
    }
}
