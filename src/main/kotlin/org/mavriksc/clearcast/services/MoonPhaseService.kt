package org.mavriksc.clearcast.services

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.floor
import org.mavriksc.clearcast.MoonPhase

object MoonPhaseService {
    fun phaseFor(instant: Instant): MoonPhase {
        val date = instant.atZone(ZoneOffset.UTC).toLocalDate()
        var year = date.year
        var month = date.monthValue
        val day = date.dayOfMonth

        if (month < 3) {
            year -= 1
            month += 12
        }
        month += 1

        val c = floor(365.25 * year)
        val e = floor(30.6 * month)
        var jd = c + e + day - 694039.09
        jd /= 29.5305882
        val phase = floor((jd - floor(jd)) * 8 + 0.5).toInt() and 7

        return when (phase) {
            0 -> MoonPhase.NEW
            1 -> MoonPhase.WAXING_CRESCENT
            2 -> MoonPhase.FIRST_QUARTER
            3 -> MoonPhase.WAXING_GIBBOUS
            4 -> MoonPhase.FULL
            5 -> MoonPhase.WANING_GIBBOUS
            6 -> MoonPhase.LAST_QUARTER
            else -> MoonPhase.WANING_CRESCENT
        }
    }
}
