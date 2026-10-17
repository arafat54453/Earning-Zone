package com.arafat.tun2socks.ui

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

fun formatCount(count: Long): String = when {
    count < 1000 -> count.toString()
    count < 1_000_000 -> String.format(Locale.US, "%.1fK", count / 1000.0)
    else -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
}
