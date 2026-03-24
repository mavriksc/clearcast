package org.mavriksc.clearcast

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.thymeleaf.Thymeleaf
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.mavriksc.clearcast.services.RefreshConfig
import org.mavriksc.clearcast.services.RadarRidgeService
import org.mavriksc.clearcast.services.WeatherGovService
import org.mavriksc.clearcast.services.WeatherService
import java.nio.file.Paths
import kotlinx.coroutines.cancel

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    ensureLatLonFromZip()
    install(Thymeleaf) {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            characterEncoding = "UTF-8"
            setCacheable(false)
        })
    }

    configureStatic()
    val config = loadRefreshConfig()
    val weatherService = WeatherService(
        WeatherGovService.fromEnv(),
        RadarRidgeService(buildRadarClient()),
    )
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
        currentSeconds = getSeconds("REFRESH_CURRENT_SEC", 1200),
        hourlySeconds = getSeconds("REFRESH_HOURLY_SEC", 3600),
        radarSeconds = getSeconds("REFRESH_RADAR_SEC", 3600),
        dailySeconds = getSeconds("REFRESH_DAILY_SEC", 86400),
        alertsSeconds = getSeconds("REFRESH_ALERTS_SEC", 3600),
    )
}

private fun buildRadarClient(): okhttp3.OkHttpClient {
    val env = loadEnv()
    val userAgent = env["NWS_USER_AGENT"] ?: System.getenv("NWS_USER_AGENT")
    if (userAgent.isNullOrBlank()) {
        return okhttp3.OkHttpClient()
    }
    return okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }
        .build()
}
