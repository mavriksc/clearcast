package org.mavriksc.clearcast.services

import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration

class WeatherGovService(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxRetries: Int = 3,
    private val fallbackRetryAfterSeconds: Long = 5,
) {
    fun getPoints(lat: Double, lon: Double): JsonObject {
        val url = "https://api.weather.gov/points/$lat,$lon"
        return fetchJson(url)
    }

    fun getForecast(forecastUrl: String): JsonObject = fetchJson(forecastUrl)

    fun getForecastHourly(forecastHourlyUrl: String): JsonObject = fetchJson(forecastHourlyUrl)

    fun getAlertsByState(stateCode: String): JsonObject {
        val url = "https://api.weather.gov/alerts/active?area=$stateCode"
        return fetchJson(url)
    }

    private fun fetchJson(url: String): JsonObject {
        val responseBody = executeWithRetry(url)
        return json.parseToJsonElement(responseBody).jsonObject
    }

    private fun executeWithRetry(url: String): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/geo+json")
                .build()

            val response = client.newCall(request).execute()
            if (response.code != 429) {
                response.use {
                    if (!it.isSuccessful) {
                        throw IOException("weather.gov request failed (${it.code}) for $url")
                    }
                    return it.body?.string() ?: throw IOException("weather.gov empty body for $url")
                }
            }

            response.close()
            if (attempt >= maxRetries) {
                throw IOException("weather.gov rate-limited after $maxRetries retries for $url")
            }

            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                ?: fallbackRetryAfterSeconds
            Thread.sleep(Duration.ofSeconds(retryAfterSeconds).toMillis())
            attempt += 1
        }
    }

    companion object {
        fun fromEnv(): WeatherGovService {
            val env = dotenv { ignoreIfMissing = true }
            val userAgent = env["NWS_USER_AGENT"] ?: System.getenv("NWS_USER_AGENT")
            require(!userAgent.isNullOrBlank()) {
                "NWS_USER_AGENT must be set (example: \"Clearcast (contact: you@example.com)\")"
            }

            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                    chain.proceed(request)
                }
                .build()

            return WeatherGovService(client)
        }
    }
}
