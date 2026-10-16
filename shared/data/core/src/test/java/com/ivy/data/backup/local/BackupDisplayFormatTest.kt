package com.ivy.data.backup.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class BackupDisplayFormatTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `size of an empty or impossible file reads as zero bytes`() {
        assertEquals("0 B", BackupDisplayFormat.size(0L))
        assertEquals("0 B", BackupDisplayFormat.size(-1L))
        assertEquals("0 B", BackupDisplayFormat.size(Long.MIN_VALUE))
    }

    @Test
    fun `size below one kilobyte is shown in bytes`() {
        assertEquals("1 B", BackupDisplayFormat.size(1L))
        assertEquals("999 B", BackupDisplayFormat.size(999L))
        assertEquals("1023 B", BackupDisplayFormat.size(1023L))
    }

    @Test
    fun `size is truncated to one decimal, never rounded up`() {
        assertEquals("1.0 KB", BackupDisplayFormat.size(1024L))
        assertEquals("1.5 KB", BackupDisplayFormat.size(1536L))
        // 1023.9 KB must not be rounded up into a wrong "1.0 MB".
        assertEquals("1023.9 KB", BackupDisplayFormat.size(1024L * 1024L - 1L))
    }

    @Test
    fun `size walks up the units`() {
        assertEquals("1.0 MB", BackupDisplayFormat.size(1024L * 1024L))
        assertEquals("4.5 MB", BackupDisplayFormat.size(4L * 1024L * 1024L + 512L * 1024L))
        assertEquals("1.0 GB", BackupDisplayFormat.size(1024L * 1024L * 1024L))
        assertEquals("1.2 GB", BackupDisplayFormat.size(1024L * 1024L * 1024L + 256L * 1024L * 1024L))
    }

    @Test
    fun `size is locale independent, because it never formats a number with a locale`() {
        val bytes = 1536L
        val english = BackupDisplayFormat.size(bytes)
        // A locale that uses a comma as the decimal separator must not change the output.
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals(english, BackupDisplayFormat.size(bytes))
            assertEquals("1.5 KB", BackupDisplayFormat.size(bytes))
        } finally {
            Locale.setDefault(Locale.US)
        }
    }

    @Test
    fun `timestamp is rendered in the given zone`() {
        assertEquals("1970-01-01 00:00", BackupDisplayFormat.timestamp(0L, utc, Locale.US))
        assertEquals(
            "1970-01-01 02:00",
            BackupDisplayFormat.timestamp(0L, ZoneId.of("Asia/Jerusalem"), Locale.US),
        )
    }

    @Test
    fun `timestamp is minutes precise and zero padded`() {
        // 1700000000000 ms is 2023-11-14T22:13:20Z.
        assertEquals(
            "2023-11-14 22:13",
            BackupDisplayFormat.timestamp(1_700_000_000_000L, utc, Locale.US),
        )
    }

    @Test
    fun `timestamp keeps the order of the instants it describes`() {
        val instants = listOf(0L, 1_000L, 1_700_000_000_000L, 1_800_000_000_000L)
        val rendered = instants.map { BackupDisplayFormat.timestamp(it, utc, Locale.US) }
        assertEquals(rendered.sorted(), rendered)
        assertTrue(rendered.first() < rendered.last())
    }
}
