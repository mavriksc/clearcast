package org.mavriksc.clearcast

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.thymeleaf.Thymeleaf
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.mavriksc.clearcast.services.RefreshConfig
import org.mavriksc.clearcast.services.WeatherGovService
import org.mavriksc.clearcast.services.WeatherService
import java.nio.file.Paths
import kotlinx.coroutines.cancel

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(Thymeleaf) {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            characterEncoding = "UTF-8"
        })
    }

    val config = loadRefreshConfig()
    val weatherService = WeatherService(WeatherGovService.fromEnv())
    val scope = weatherService.start(config, Paths.get("data"))
    environment.monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        scope.cancel()
    }

    configureControllers(weatherService)
}

private fun loadRefreshConfig(): RefreshConfig {
    val env = loadEnv()
    fun getSeconds(name: String, defaultSeconds: Long): Long {
        val raw = env[name] ?: System.getenv(name)
        return raw?.toLongOrNull() ?: defaultSeconds
    }

    return RefreshConfig(
        currentSeconds = getSeconds("REFRESH_CURRENT_SEC", 300),
        radarSeconds = getSeconds("REFRESH_RADAR_SEC", 3600),
        dailySeconds = getSeconds("REFRESH_DAILY_SEC", 86400),
        alertsSeconds = getSeconds("REFRESH_ALERTS_SEC", 86400),
    )
}
