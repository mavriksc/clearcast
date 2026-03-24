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

fun Application.configureControllers(weatherService: WeatherService) {
    val dallasZone = ZoneId.of("America/Chicago")
    val updatedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
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

            val model = mapOf(
                "current" to current,
                "currentUpdatedAt" to updatedAt,
            )
            call.respond(ThymeleafContent("index", model))
        }
    }
}
