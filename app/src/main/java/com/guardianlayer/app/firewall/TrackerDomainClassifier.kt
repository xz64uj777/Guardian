package com.guardianlayer.app.firewall

import java.util.Locale

/**
 * Transparent, offline classification for domains Guardian can actually
 * observe in plaintext DNS. Matches are intentionally limited to dedicated
 * analytics, advertising, attribution, crash/telemetry, social measurement,
 * or session-replay service domains. A match is a privacy signal, not a
 * malware verdict and not proof that the app itself is malicious.
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
        // Analytics / product measurement
        Rule("google-analytics.com", "Analytics", "Google Analytics", "commonly used for usage and audience measurement"),
        Rule("app-measurement.com", "Analytics", "Google/Firebase Analytics", "commonly used for mobile analytics measurement"),
        Rule("segment.io", "Analytics", "Segment", "commonly used to route product analytics events"),
        Rule("mixpanel.com", "Analytics", "Mixpanel", "commonly used for product and behavioral analytics"),
        Rule("amplitude.com", "Analytics", "Amplitude", "commonly used for product and behavioral analytics"),
        Rule("heapanalytics.com", "Analytics", "Heap", "commonly used for product and behavioral analytics"),
        Rule("posthog.com", "Analytics", "PostHog", "commonly used for product analytics and event measurement"),
        Rule("scorecardresearch.com", "Analytics", "Comscore", "commonly used for audience and usage measurement"),
        Rule("quantserve.com", "Analytics", "Quantcast", "commonly used for audience measurement"),
        Rule("chartbeat.com", "Analytics", "Chartbeat", "commonly used for content and audience analytics"),
        Rule("parsely.com", "Analytics", "Parse.ly", "commonly used for content analytics"),

        // Advertising / ad measurement
        Rule("doubleclick.net", "Advertising", "Google DoubleClick", "commonly used for advertising delivery or measurement"),
        Rule("googlesyndication.com", "Advertising", "Google Ads", "commonly used for advertising delivery"),
        Rule("adservice.google.com", "Advertising", "Google Ads", "commonly used for advertising and conversion measurement"),
        Rule("googleadservices.com", "Advertising", "Google Ads", "commonly used for advertising and conversion measurement"),
        Rule("amazon-adsystem.com", "Advertising", "Amazon Ads", "commonly used for advertising delivery, bidding, or measurement"),
        Rule("adsrvr.org", "Advertising", "The Trade Desk", "commonly used for advertising delivery or measurement"),
        Rule("criteo.com", "Advertising", "Criteo", "commonly used for advertising and retargeting"),
        Rule("criteo.net", "Advertising", "Criteo", "commonly used for advertising and retargeting"),
        Rule("adnxs.com", "Advertising", "Microsoft/Xandr", "commonly used for programmatic advertising"),
        Rule("atdmt.com", "Advertising", "Microsoft Advertising", "historically used by Microsoft's Atlas advertising and measurement infrastructure"),
        Rule("pubmatic.com", "Advertising", "PubMatic", "commonly used for programmatic advertising"),
        Rule("rubiconproject.com", "Advertising", "Magnite", "commonly used for programmatic advertising"),
        Rule("openx.net", "Advertising", "OpenX", "commonly used for programmatic advertising"),
        Rule("casalemedia.com", "Advertising", "Index Exchange", "commonly used for programmatic advertising"),
        Rule("applovin.com", "Advertising", "AppLovin", "commonly used for mobile advertising and measurement"),
        Rule("chartboost.com", "Advertising", "Chartboost", "commonly used for mobile advertising"),
        Rule("inmobi.com", "Advertising", "InMobi", "commonly used for mobile advertising and measurement"),
        Rule("unityads.unity3d.com", "Advertising", "Unity Ads", "commonly used for mobile advertising"),
        Rule("vungle.com", "Advertising", "Vungle/Liftoff", "commonly used for mobile advertising"),
        Rule("ironsrc.com", "Advertising", "ironSource", "commonly used for mobile advertising and mediation"),
        Rule("bat.bing.com", "Advertising", "Microsoft Advertising", "commonly used for advertising conversion measurement"),

        // Attribution / campaign measurement
        Rule("adjust.com", "Attribution", "Adjust", "commonly used to measure app-install and campaign attribution"),
        Rule("appsflyer.com", "Attribution", "AppsFlyer", "commonly used to measure app-install and campaign attribution"),
        Rule("branch.io", "Attribution", "Branch", "commonly used for deep links and campaign attribution"),
        Rule("singular.net", "Attribution", "Singular", "commonly used for marketing attribution"),
        Rule("kochava.com", "Attribution", "Kochava", "commonly used for mobile attribution and campaign measurement"),
        Rule("tenjin.com", "Attribution", "Tenjin", "commonly used for mobile attribution and campaign measurement"),

        // Crash, error, and performance telemetry
        Rule("crashlytics.com", "Crash/Telemetry", "Firebase Crashlytics", "commonly used for crash diagnostics and telemetry"),
        Rule("sentry.io", "Crash/Telemetry", "Sentry", "commonly used for crash, error, and performance telemetry"),
        Rule("bugsnag.com", "Crash/Telemetry", "Bugsnag", "commonly used for crash and error telemetry"),
        Rule("instabug.com", "Crash/Telemetry", "Instabug", "commonly used for crash reports, diagnostics, and feedback telemetry"),
        Rule("mobile-collector.newrelic.com", "Crash/Telemetry", "New Relic", "commonly used for mobile performance and diagnostic telemetry"),
        Rule("datadoghq.com", "Crash/Telemetry", "Datadog", "commonly used for application logs, diagnostics, and performance telemetry"),
        Rule("rollbar.com", "Crash/Telemetry", "Rollbar", "commonly used for application error telemetry"),
        Rule("airbrake.io", "Crash/Telemetry", "Airbrake", "commonly used for application error telemetry"),
        Rule("raygun.io", "Crash/Telemetry", "Raygun", "commonly used for crash and performance telemetry"),

        // Social-network advertising / measurement endpoints
        Rule("connect.facebook.net", "Social tracking", "Meta", "commonly used to load Meta/Facebook SDK or measurement components"),
        Rule("analytics.twitter.com", "Social tracking", "X/Twitter", "commonly used for analytics and conversion measurement"),
        Rule("ads-twitter.com", "Social tracking", "X/Twitter", "commonly used for advertising and conversion measurement"),
        Rule("tr.snapchat.com", "Social tracking", "Snap", "commonly used for advertising conversion measurement"),
        Rule("ads.snapchat.com", "Social tracking", "Snap", "commonly used for advertising and campaign measurement"),
        Rule("analytics.pinterest.com", "Social tracking", "Pinterest", "commonly used for analytics and conversion measurement"),
        Rule("ct.pinterest.com", "Social tracking", "Pinterest", "commonly used for conversion tracking"),

        // Session replay / interaction recording
        Rule("fullstory.com", "Session replay", "FullStory", "commonly used for session replay and interaction analytics"),
        Rule("logrocket.com", "Session replay", "LogRocket", "commonly used for session replay and frontend telemetry"),
        Rule("hotjar.com", "Session replay", "Hotjar", "commonly used for interaction analytics and session recording"),
        Rule("mouseflow.com", "Session replay", "Mouseflow", "commonly used for interaction analytics and session recording"),
        Rule("clarity.ms", "Session replay", "Microsoft Clarity", "commonly used for interaction analytics and session recording")
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
