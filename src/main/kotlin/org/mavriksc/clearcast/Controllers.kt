package org.mavriksc.clearcast

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.thymeleaf.ThymeleafContent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import org.mavriksc.clearcast.services.MoonPhaseService
import org.mavriksc.clearcast.services.WeatherService

fun Application.configureControllers(weatherService: WeatherService) {
    val dallasZone = ZoneId.of("America/Chicago")
    val updatedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
    val hourFormatter = DateTimeFormatter.ofPattern("h a", Locale.US).withZone(dallasZone)

    routing {
        get("/") {
            val current = weatherService.currentFlow.value
                ?: CurrentConditionsCard(
                    locationName = "Loading",
                    temperatureF = 0.0,
                    feelsLikeF = null,
                    humidityPercent = null,
                    windMph = null,
                    condition = "Fetching latest conditions",
                    icon = "",
                    isDaytime = true,
                    observedAt = Instant.now(),
                    conditionTheme = ConditionTheme.SUNNY,
                )

            val observedAt = current.observedAt
            val updatedAt = updatedFormatter.format(observedAt.atZone(dallasZone))
            val estimatedTempText = buildEstimatedTempText(
                current = current,
                hourly = weatherService.hourlyFlow.value,
                now = Instant.now(),
            )

            val model = mutableMapOf<String, Any>(
                "current" to current,
                "currentUpdatedAt" to updatedAt,
                "estimatedTempText" to (estimatedTempText ?: ""),
                "alertsCount" to (weatherService.alertsFlow.value?.alerts?.size ?: 0),
                "dailyLabels" to buildDailyLabels(weatherService),
                "radarFrames" to buildRadarFrameUrls(weatherService),
                "hourlyIconPoints" to buildHourlyIconPoints(weatherService),
                "hourlyIconStep" to buildHourlyIconStep(weatherService),
                "hourlyTimes" to buildHourlyTimesList(weatherService, hourFormatter),
                "hourlyTemps" to buildHourlyTempsList(weatherService),
                "hourlyPrecip" to buildHourlyPrecipList(weatherService),
                "hourlyYMin" to buildHourlyYMin(weatherService),
                "hourlyYMax" to buildHourlyYMax(weatherService),
            )
            weatherService.hourlyFlow.value?.let { model["hourly"] = it }
            weatherService.dailyFlow.value?.let { model["daily"] = it }
            weatherService.alertsFlow.value?.let { model["alerts"] = it }
            call.respond(ThymeleafContent("index", model))
        }

        get("/api/current-emoji") {
            val current = weatherService.currentFlow.value
            val temp = current?.temperatureF?.toInt()
            val emoji = current?.let { conditionEmoji(it) } ?: "?"
            val tempText = temp?.let { "${it}\u00B0F" } ?: "--"
            val moonEmoji = if (current != null && !current.isDaytime) {
                moonPhaseEmoji(current.observedAt)
            } else {
                ""
            }
            val parts = listOf(emoji, moonEmoji, tempText).filter { it.isNotBlank() }
            val payload = """{"value":"${parts.joinToString(" ")}"}"""
            call.respondText(payload, ContentType.Application.Json)
        }
    }
}

private fun conditionEmoji(current: CurrentConditionsCard): String {
    return if (current.isDaytime) {
        when (current.conditionTheme) {
            ConditionTheme.CLEAR, ConditionTheme.SUNNY -> "\u2600\uFE0F"
            ConditionTheme.PARTLY_CLOUDY -> "\u26C5"
            ConditionTheme.CLOUDY -> "\u2601\uFE0F"
            ConditionTheme.RAIN -> "\uD83C\uDF27\uFE0F"
            ConditionTheme.SNOW -> "\u2744\uFE0F"
            ConditionTheme.STORM -> "\u26C8\uFE0F"
            ConditionTheme.FOG -> "\uD83C\uDF2B\uFE0F"
            ConditionTheme.WINDY -> "\uD83D\uDCA8"
            ConditionTheme.HAZE -> "\uD83C\uDF2B\uFE0F"
            ConditionTheme.OTHER -> "?"
        }
    } else {
        when (current.conditionTheme) {
            ConditionTheme.CLEAR, ConditionTheme.SUNNY -> "\uD83C\uDF19"
            ConditionTheme.PARTLY_CLOUDY -> "\uD83C\uDF19\u2601\uFE0F"
            ConditionTheme.CLOUDY -> "\u2601\uFE0F\uD83C\uDF19"
            ConditionTheme.RAIN -> "\uD83C\uDF27\uFE0F\uD83C\uDF19"
            ConditionTheme.SNOW -> "\u2744\uFE0F"
            ConditionTheme.STORM -> "\u26C8\uFE0F"
            ConditionTheme.FOG -> "\uD83C\uDF2B\uFE0F"
            ConditionTheme.WINDY -> "\uD83D\uDCA8"
            ConditionTheme.HAZE -> "\uD83C\uDF2B\uFE0F"
            ConditionTheme.OTHER -> "?"
        }
    }
}

private fun moonPhaseEmoji(observedAt: Instant): String {
    return when (MoonPhaseService.phaseFor(observedAt)) {
        MoonPhase.NEW -> "\uD83C\uDF11"
        MoonPhase.WAXING_CRESCENT -> "\uD83C\uDF12"
        MoonPhase.FIRST_QUARTER -> "\uD83C\uDF13"
        MoonPhase.WAXING_GIBBOUS -> "\uD83C\uDF14"
        MoonPhase.FULL -> "\uD83C\uDF15"
        MoonPhase.WANING_GIBBOUS -> "\uD83C\uDF16"
        MoonPhase.LAST_QUARTER -> "\uD83C\uDF17"
        MoonPhase.WANING_CRESCENT -> "\uD83C\uDF18"
    }
}

private fun buildHourlyTimesList(
    weatherService: WeatherService,
    formatter: DateTimeFormatter,
): List<String> {
    val hours = weatherService.hourlyFlow.value?.hours ?: emptyList()
    return hours.map { formatter.format(it.time).replace(" ", "") }
}

private fun buildHourlyIconPoints(weatherService: WeatherService): List<HourlyForecastPoint> {
    val hours = weatherService.hourlyFlow.value?.hours ?: emptyList()
    val step = buildHourlyIconStep(weatherService)
    return hours.filterIndexed { index, _ -> index % step == 0 }
}

private fun buildHourlyIconStep(weatherService: WeatherService): Int {
    val hourCount = weatherService.hourlyFlow.value?.hours?.size ?: 0
    return hourlyDisplayStep(hourCount)
}

private fun hourlyDisplayStep(hourCount: Int): Int {
    return maxOf(1, kotlin.math.ceil(hourCount / 8.0).toInt())
}

private fun buildHourlyTempsList(weatherService: WeatherService): List<Int> {
    val hours = weatherService.hourlyFlow.value?.hours ?: emptyList()
    return hours.map { it.temperatureF.toInt() }
}

private fun buildHourlyPrecipList(weatherService: WeatherService): List<Int> {
    val hours = weatherService.hourlyFlow.value?.hours ?: emptyList()
    return hours.map { it.precipChancePercent ?: 0 }
}

private fun buildDailyLabels(weatherService: WeatherService): List<String> {
    val zone = ZoneId.of("America/Chicago")
    val formatter = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.US).withZone(zone)
    val daily = weatherService.dailyFlow.value?.days ?: emptyList()
    return daily.mapNotNull { day ->
        runCatching {
            val instant = java.time.LocalDate.parse(day.dateIso).atStartOfDay(zone).toInstant()
            formatter.format(instant)
        }.getOrNull()
    }
}

private fun buildRadarFrameUrls(weatherService: WeatherService): List<String> {
    val frames = weatherService.radarFlow.value?.frames ?: emptyList()
    return frames.map { it.url }
}

private fun buildEstimatedTempText(
    current: CurrentConditionsCard,
    hourly: HourlyForecastCard?,
    now: Instant,
): String? {
    if (Duration.between(current.observedAt, now).toMinutes() <= 60) {
        return null
    }
    val estimate = interpolateTemp(hourly, now) ?: return null
    return "(${estimate.roundToInt()}\u00B0F)"
}

private fun interpolateTemp(hourly: HourlyForecastCard?, now: Instant): Double? {
    val hours = hourly?.hours ?: return null
    if (hours.size < 2) return null
    for (i in 0 until hours.size - 1) {
        val a = hours[i]
        val b = hours[i + 1]
        if (now.isBefore(a.time) || now.isAfter(b.time)) {
            continue
        }
        val total = Duration.between(a.time, b.time).toMillis()
        if (total <= 0) return a.temperatureF
        val elapsed = Duration.between(a.time, now).toMillis()
        val fraction = elapsed.toDouble() / total.toDouble()
        return a.temperatureF + (b.temperatureF - a.temperatureF) * fraction
    }
    return null
}

private fun buildHourlyYMin(weatherService: WeatherService): Int {
    val temps = weatherService.hourlyFlow.value?.hours?.map { it.temperatureF } ?: emptyList()
    if (temps.isEmpty()) return 0
    return temps.minOrNull()!!.toInt() - 5
}

private fun buildHourlyYMax(weatherService: WeatherService): Int {
    val temps = weatherService.hourlyFlow.value?.hours?.map { it.temperatureF } ?: emptyList()
    if (temps.isEmpty()) return 100
    return temps.maxOrNull()!!.toInt() + 5
}
