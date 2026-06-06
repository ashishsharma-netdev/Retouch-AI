package com.aifacestudio.data.analytics

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

interface AnalyticsTracker {
    fun track(event: String, properties: Map<String, String> = emptyMap())
}

@Singleton
class LogcatAnalyticsTracker @Inject constructor() : AnalyticsTracker {
    override fun track(event: String, properties: Map<String, String>) {
        Log.d("AiFaceAnalytics", "$event $properties")
    }
}
