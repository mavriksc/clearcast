package org.mavriksc.clearcast

import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.thymeleaf.ThymeleafContent
import org.mavriksc.clearcast.services.WeatherService
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun Application.configureControllers(weatherService: WeatherService) {
    val env = dotenv { ignoreIfMissing = true }
    val zip = env["ZIP"] ?: System.getenv("ZIP")
        ?: env["ZIP_CODE"] ?: System.getenv("ZIP_CODE") ?: "UNKNOWN"
    val delayForRender = env["DELAY_FOR_RENDER"] ?: System.getenv("DELAY_FOR_RENDER")
        ?: env["DELAY"] ?: System.getenv("DELAY") ?: "0"
    val nwsUserAgent = env["NWS_USER_AGENT"] ?: System.getenv("NWS_USER_AGENT") ?: "MISSING"
    val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    routing {
        get("/") {
            val nextFetch = weatherService.nextFetchFlow.value
            val model = mapOf(
                "zip" to zip,
                "delayForRender" to delayForRender,
                "nwsUserAgent" to nwsUserAgent,
                "nextFetchCurrent" to nextFetch.currentAt?.let { formatter.format(it) } ?: "UNKNOWN",
                "nextFetchRadar" to nextFetch.radarAt?.let { formatter.format(it) } ?: "UNKNOWN",
                "nextFetchDaily" to nextFetch.dailyAt?.let { formatter.format(it) } ?: "UNKNOWN",
                "nextFetchAlerts" to nextFetch.alertsAt?.let { formatter.format(it) } ?: "UNKNOWN",
            )
            call.respond(ThymeleafContent("index", model))
        }
    }
}
