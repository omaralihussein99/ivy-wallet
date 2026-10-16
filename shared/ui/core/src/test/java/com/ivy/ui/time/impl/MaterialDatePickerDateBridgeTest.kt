package com.ivy.ui.time.impl

import com.ivy.base.time.TimeProvider
import com.ivy.base.time.impl.StandardTimeConverter
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pins the boundary between Material3's date picker and Ivy's date plumbing.
 *
 * Material3 encodes a *calendar day* as UTC midnight millis; Ivy resolves the calendar day of a real
 * [Instant] with the app's zone ([StandardTimeConverter]). Crossing that boundary with a zone-aware
 * converter is what makes a transaction land on the previous/next day.
 *
 * Every case injects its [ZoneId] explicitly - the ambient `ZoneId.systemDefault()` is never used -
 * so this suite fails on a developer machine in any time zone when the bridge is wrong. The zones
 * cover the machine's own zone (Asia/Jerusalem), a negative offset (UTC-11), and the zones of the
 * reporters in the tracker issues (Asia/Tokyo, Asia/Kolkata).
 */
class MaterialDatePickerDateBridgeTest {

    /**
     * A "now" whose local day and UTC day differ in `Asia/Jerusalem`, and a date on which Jerusalem
     * is genuinely UTC+3 (`2024-04-10` is after the 2024-03-29 DST switch; in March it would be +2).
     */
    private val now = Instant.parse("2024-04-10T21:30:00Z")

    private fun bridge(
        zone: ZoneId,
        utcNow: Instant = now,
    ): MaterialDatePickerDateBridge {
        val timeProvider = FixedZoneTimeProvider(zone = zone, now = utcNow)
        return MaterialDatePickerDateBridge(
            timeConverter = StandardTimeConverter(timeProvider),
            timeProvider = timeProvider,
        )
    }

    /** A calendar day as Material3 encodes it: the UTC midnight of that day. */
    private fun utcDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun localDayOf(instant: Instant, zone: ZoneId): LocalDate =
        LocalDateTime.ofInstant(instant, zone).toLocalDate()

    @Test
    fun `the zones used by this suite really have the offsets it claims`() {
        // Guards the test's own assumptions: if these change, DST has moved under the assertions.
        ZoneId.of("Asia/Jerusalem").rules.getOffset(now) shouldBe ZoneOffset.ofHours(3)
        ZoneId.of("UTC-11").rules.getOffset(now) shouldBe ZoneOffset.ofHours(-11)
        ZoneId.of("Asia/Kolkata").rules.getOffset(now) shouldBe ZoneOffset.ofHoursMinutes(5, 30)
        ZoneId.of("Asia/Tokyo").rules.getOffset(now) shouldBe ZoneOffset.ofHours(9)
        // ...and the instant's local day in Jerusalem is not its UTC day
        localDayOf(now, ZoneId.of("Asia/Jerusalem")) shouldBe LocalDate.of(2024, 4, 11)
        localDayOf(now, ZoneOffset.UTC) shouldBe LocalDate.of(2024, 4, 10)
    }

    @Test
    fun `initial date is the app zone day of the instant, not its UTC day`() {
        bridge(ZoneId.of("Asia/Jerusalem"))
            .initialSelectedDateMillis(now) shouldBe utcDayMillis(LocalDate.of(2024, 4, 11))
    }

    @Test
    fun `initial date defaults to the local today, not the UTC today`() {
        bridge(ZoneId.of("Asia/Jerusalem"))
            .initialSelectedDateMillis(null) shouldBe utcDayMillis(LocalDate.of(2024, 4, 11))
    }

    @Test
    fun `picked date is stable in a negative offset zone`() {
        bridge(ZoneId.of("UTC-11"))
            .pickedDate(utcDayMillis(LocalDate.of(2024, 4, 11))) shouldBe LocalDate.of(2024, 4, 11)
    }

    @Test
    fun `picked date is stable in a positive offset zone`() {
        bridge(ZoneId.of("Asia/Jerusalem"))
            .pickedDate(utcDayMillis(LocalDate.of(2024, 4, 11))) shouldBe LocalDate.of(2024, 4, 11)
    }

    @Test
    fun `the day handed to Material3 is the app zone day encoded as UTC midnight`() {
        val zones = listOf(
            ZoneId.of("Asia/Jerusalem"), // UTC+3 - the machine's own zone
            ZoneId.of("UTC-11"), // negative offset
            ZoneId.of("Asia/Kolkata"), // UTC+5:30
            ZoneId.of("Asia/Tokyo"), // UTC+9
            ZoneOffset.UTC,
        )
        val instants = listOf(
            Instant.parse("2024-04-10T21:30:00Z"),
            Instant.parse("2024-04-10T22:00:00Z"),
            Instant.parse("2024-04-11T00:00:00Z"),
            Instant.parse("2024-04-11T12:00:00Z"),
            Instant.parse("2024-04-11T23:59:00Z"),
        )

        for (zone in zones) {
            val bridge = bridge(zone)
            for (instant in instants) {
                val appZoneDay = localDayOf(instant, zone)
                // Material3 reads `initialSelectedDateMillis` as the UTC midnight of the day to select
                bridge.initialSelectedDateMillis(instant) shouldBe utcDayMillis(appZoneDay)
                // and reports `selectedDateMillis` in that same encoding
                bridge.pickedDate(utcDayMillis(appZoneDay)) shouldBe appZoneDay
            }
        }
    }

    @Test
    fun `no date picked means no date reported`() {
        bridge(ZoneId.of("Asia/Jerusalem")).pickedDate(null) shouldBe null
    }

    private class FixedZoneTimeProvider(
        private val zone: ZoneId,
        private val now: Instant,
    ) : TimeProvider {
        override fun getZoneId(): ZoneId = zone
        override fun utcNow(): Instant = now
        override fun localNow(): LocalDateTime = LocalDateTime.ofInstant(now, zone)
        override fun localDateNow(): LocalDate = localNow().toLocalDate()
        override fun localTimeNow(): LocalTime = localNow().toLocalTime()
    }
}
