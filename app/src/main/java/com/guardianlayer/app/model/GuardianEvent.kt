package com.guardianlayer.app.model

data class GuardianEvent(
    val timestamp: Long,
    val level: String,
    val title: String,
    val detail: String
)
