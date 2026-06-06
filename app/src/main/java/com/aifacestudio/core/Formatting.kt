package com.aifacestudio.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

fun Long.formatBytes(): String {
    if (this <= 0L) return "Unknown size"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = this.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else "%.1f %s".format(value, units[unit])
}

fun Long.formatDuration(): String {
    if (this <= 0L) return "Unknown duration"
    val totalSeconds = max(0L, this / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

fun Long.formatDateTime(): String {
    val formatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    return formatter.format(Date(this))
}

fun Int?.dimensionLabel(other: Int?): String {
    if (this == null || other == null || this <= 0 || other <= 0) return "Unknown resolution"
    return "${this}x${other}"
}
