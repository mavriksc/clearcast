package org.mavriksc.clearcast

import java.time.Instant

data class CurrentConditionsCard(
    val locationName: String,
    val temperatureF: Double,
    val feelsLikeF: Double?,
    val humidityPercent: Int?,
    val windMph: Double?,
    val condition: String,
    val icon: String,
    val isDaytime: Boolean,
    val observedAt: Instant,
    val conditionTheme: ConditionTheme,
)

data class HourlyForecastCard(
    val generatedAt: Instant,
    val hours: List<HourlyForecastPoint>,
)

data class HourlyForecastPoint(
    val time: Instant,
    val temperatureF: Double,
    val precipChancePercent: Int?,
    val precipTypes: List<PrecipType>,
    val icon: String,
)

data class DailyForecastCard(
    val generatedAt: Instant,
    val days: List<DailyForecastPoint>,
)

data class DailyForecastPoint(
    val dateIso: String,
    val highF: Double,
    val lowF: Double,
    val precipChancePercent: Int?,
    val precipTypes: List<PrecipType>,
    val icon: String,
)

data class AlertsCard(
    val generatedAt: Instant,
    val alerts: List<WeatherAlert>,
)

data class WeatherAlert(
    val title: String,
    val severity: AlertSeverity,
    val urgency: AlertUrgency,
    val areas: List<String>,
    val effectiveAt: Instant?,
    val expiresAt: Instant?,
    val description: String,
)

data class RadarCard(
    val generatedAt: Instant,
    val mode: RadarMode,
    val frames: List<RadarFrame>,
)

data class RadarFrame(
    val timestamp: Instant,
    val url: String,
)

enum class RadarMode {
    PAST_ONLY,
    PAST_AND_NOWCAST,
}

enum class PrecipType {
    RAIN,
    SNOW,
    SLEET,
    HAIL,
    MIX,
    NONE,
}

enum class AlertSeverity {
    UNKNOWN,
    MINOR,
    MODERATE,
    SEVERE,
    EXTREME,
}

enum class AlertUrgency {
    UNKNOWN,
    IMMEDIATE,
    EXPECTED,
    FUTURE,
    PAST,
}

enum class ConditionTheme {
    CLEAR,
    SUNNY,
    PARTLY_CLOUDY,
    CLOUDY,
    RAIN,
    SNOW,
    STORM,
    FOG,
    WINDY,
    HAZE,
    OTHER,
}

enum class MoonPhase {
    NEW,
    WAXING_CRESCENT,
    FIRST_QUARTER,
    WAXING_GIBBOUS,
    FULL,
    WANING_GIBBOUS,
    LAST_QUARTER,
    WANING_CRESCENT,
}
