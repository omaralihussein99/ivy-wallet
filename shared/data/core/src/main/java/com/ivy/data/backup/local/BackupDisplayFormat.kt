package com.ivy.data.backup.local

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a backup is shown in the list: when it was taken and how big it is.
 *
 * Pure and free of Android types, so it is unit tested on the JVM. Note that this is NOT money: Ivy's
 * `FormatMoneyUseCase` formats monetary amounts, and there are none on the backup screen. Sizes are
 * rendered with integer arithmetic and a plain unit suffix, and are deliberately locale independent
 * (no `String.format`, no grouping separators), so the same bytes always read the same way.
 */
object BackupDisplayFormat {

    private const val KB = 1024L
    private const val MB = KB * 1024L
    private const val GB = MB * 1024L

    private const val PATTERN = "yyyy-MM-dd HH:mm"

    /** e.g. `0 B`, `512 B`, `1.5 KB`, `4.5 MB`, `1.2 GB`. Never negative. */
    fun size(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return when {
            bytes < KB -> "$bytes B"
            bytes < MB -> oneDecimal(bytes, KB, "KB")
            bytes < GB -> oneDecimal(bytes, MB, "MB")
            else -> oneDecimal(bytes, GB, "GB")
        }
    }

    /** e.g. `2026-10-16 15:24`, rendered in [zoneId]. */
    fun timestamp(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = formatter(locale, zoneId).format(Instant.ofEpochMilli(epochMillis))

    private fun formatter(locale: Locale, zoneId: ZoneId): DateTimeFormatter =
        DateTimeFormatter.ofPattern(PATTERN).withLocale(locale).withZone(zoneId)

    /**
     * Truncating, not rounding: `1536` bytes is `1.5 KB` and `1023` bytes is `1023 B`. Truncation
     * keeps the displayed number monotonic with the real size, which rounding would not.
     */
    private fun oneDecimal(bytes: Long, unit: Long, suffix: String): String {
        val whole = bytes / unit
        val tenth = (bytes % unit) * 10L / unit
        return "$whole.$tenth $suffix"
    }
}
