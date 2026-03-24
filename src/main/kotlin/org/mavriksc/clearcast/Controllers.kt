package org.mavriksc.clearcast

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.thymeleaf.ThymeleafContent
import org.mavriksc.clearcast.services.WeatherService
import org.mavriksc.clearcast.ConditionTheme
import org.mavriksc.clearcast.CurrentConditionsCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                    observedAt = java.time.Instant.now(),
                    conditionTheme = ConditionTheme.SUNNY,
                )

            val observedAt = current.observedAt ?: Instant.now()
            val updatedAt = updatedFormatter.format(observedAt.atZone(dallasZone))

            val model: Map<String, Any> = mapOf(
                "current" to current,
                "currentUpdatedAt" to updatedAt,
                "hourly" to (weatherService.hourlyFlow.value ?: ""),
                "hourlyTimes" to buildHourlyTimesList(weatherService, hourFormatter),
                "hourlyTemps" to buildHourlyTempsList(weatherService),
                "hourlyPrecip" to buildHourlyPrecipList(weatherService),
                "hourlyYMin" to buildHourlyYMin(weatherService),
                "hourlyYMax" to buildHourlyYMax(weatherService),
            )
            call.respond(ThymeleafContent("index", model))
        }
    }
}

private fun buildHourlyTimesList(
    weatherService: WeatherService,
    formatter: DateTimeFormatter,
): List<String> {
    val hours = weatherService.hourlyFlow.value?.hours ?: emptyList()
    return hours.map { formatter.format(it.time).replace(" ", "") }
}

private fun buildHourlyTempsList(weatherService: WeatherService): List<Int> {
    val hours = weatherService.hourlyFlow.value?.hours ?: emptyList()
    return hours.map { it.temperatureF.toInt() }
}

private fun buildHourlyPrecipList(weatherService: WeatherService): List<Int> {
    val hours = weatherService.hourlyFlow.value?.hours ?: emptyList()
    return hours.map { it.precipChancePercent ?: 0 }
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
