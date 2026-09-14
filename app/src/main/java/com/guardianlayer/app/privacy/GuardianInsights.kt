package com.guardianlayer.app.privacy

/** Conservative explanations of exact-attribution historical DNS counters. */
object GuardianInsights {
    fun summarize(observed: Long, blocked: Long): List<String> {
        if (observed < 0 || blocked < 0 || blocked > observed) {
            return listOf("History needs review. These counters are inconsistent, so Guardian will not calculate a block rate. Open Privacy Profiles for evidence.")
        }
        if (observed == 0L) return listOf(
            "Not enough evidence yet. Shield one app at a time and use it normally to build an exact-attribution profile. No history does not mean no tracking."
        )
        val result = mutableListOf<String>()
        if (observed < 50L) {
            result += "Early sample: $blocked of $observed observed DNS requests were blocked. Use the app longer before drawing conclusions."
        } else {
            val percent = (blocked.toDouble() / observed.toDouble() * 100.0).toInt().coerceIn(0, 100)
            result += "$percent% of observed profile DNS requests were blocked ($blocked of $observed). This is a historical DNS request share, not a share of all traffic or a danger score."
        }
        result += if (blocked > 0L) {
            "Guardian has blocked classified tracker DNS requests. If your apps work normally, keeping Shield on is reasonable. Frequent requests can include retries; they are not proof your phone is hacked."
        } else {
            "No classified tracker requests were blocked in this history. This does not prove an app is tracker-free; cached addresses, encrypted DNS and direct-IP traffic may be invisible."
        }
        return result
    }
}
