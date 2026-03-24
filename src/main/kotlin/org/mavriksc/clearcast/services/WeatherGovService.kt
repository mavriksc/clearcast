package org.mavriksc.clearcast.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.mavriksc.clearcast.loadEnv
import org.mavriksc.clearcast.findEnvFileOrRoot

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

    fun getObservationStations(observationStationsUrl: String): JsonObject = fetchJson(observationStationsUrl)

    fun getLatestObservation(stationOrUrl: String): JsonObject {
        val url = when {
            stationOrUrl.contains("/observations/latest") -> stationOrUrl
            stationOrUrl.contains("/stations/") -> "${stationOrUrl.trimEnd('/')}/observations/latest"
            else -> "https://api.weather.gov/stations/${stationOrUrl.trim()}/observations/latest"
        }
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
            val env = loadEnv()
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

fun main() {
    val env = loadEnv()
    val lat = env["LAT"] ?: System.getenv("LAT")
    val lon = env["LON"] ?: System.getenv("LON")
    val zip = env["ZIP_CODE"] ?: System.getenv("ZIP_CODE")

    val latLon = parseLatLon(lat, lon)
    val (resolvedLat, resolvedLon) = when {
        latLon != null -> latLon
        !zip.isNullOrBlank() -> resolveLatLonFromZip(zip)
        else -> throw IllegalArgumentException(
            "Set ZIP_CODE or LAT/LON in .env (example: ZIP_CODE=75234 or LAT=32.9618, LON=-96.8292)"
        )
    }

    if (latLon == null) {
        updateEnvLatLon(resolvedLat, resolvedLon)
    }

    val service = WeatherGovService.fromEnv()
    val responsesDir = Path.of("responses")
    Files.createDirectories(responsesDir)

    val points = service.getPoints(resolvedLat, resolvedLon)
    writeJson(responsesDir.resolve("points.json"), points)

    val props = points["properties"]?.jsonObject
    val forecastUrl = props?.get("forecast")?.jsonPrimitive?.content
    val forecastHourlyUrl = props?.get("forecastHourly")?.jsonPrimitive?.content

    if (!forecastUrl.isNullOrBlank()) {
        val forecast = service.getForecast(forecastUrl)
        writeJson(responsesDir.resolve("forecast.json"), forecast)
    }

    if (!forecastHourlyUrl.isNullOrBlank()) {
        val hourly = service.getForecastHourly(forecastHourlyUrl)
        writeJson(responsesDir.resolve("forecast-hourly.json"), hourly)
    }

    val stateFromEnv = env["STATE"] ?: System.getenv("STATE")
    val stateFromPoints = props
        ?.get("relativeLocation")
        ?.jsonObject
        ?.get("properties")
        ?.jsonObject
        ?.get("state")
        ?.jsonPrimitive
        ?.content
    val state = stateFromEnv ?: stateFromPoints

    if (!state.isNullOrBlank()) {
        val alerts = service.getAlertsByState(state)
        writeJson(responsesDir.resolve("alerts.json"), alerts)
    }
}

private fun writeJson(path: Path, json: JsonElement) {
    Files.writeString(path, json.toString())
}

private fun parseLatLon(lat: String?, lon: String?): Pair<Double, Double>? {
    val latValue = lat?.toDoubleOrNull()
    val lonValue = lon?.toDoubleOrNull()
    return if (latValue != null && lonValue != null) latValue to lonValue else null
}

private fun resolveLatLonFromZip(zip: String): Pair<Double, Double> {
    val normalizedZip = zip.trim().padStart(5, '0')
    val gazetteerZip = ensureGazetteerZip()
    val (latIndex, lonIndex, zipIndex) = findGazetteerColumns(gazetteerZip)
    val match = findZipInGazetteer(gazetteerZip, normalizedZip, zipIndex, latIndex, lonIndex)
    return match ?: throw IOException("zip lookup failed for $normalizedZip in gazetteer data")
}

private fun ensureGazetteerZip(): Path {
    val dataDir = Path.of("data")
    Files.createDirectories(dataDir)
    val target = dataDir.resolve("2023_Gaz_zcta_national.zip")
    if (Files.exists(target)) {
        return target
    }

    val url = "https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2023_Gazetteer/2023_Gaz_zcta_national.zip"
    val request = Request.Builder().url(url).build()
    val response = OkHttpClient().newCall(request).execute()
    response.use {
        if (!it.isSuccessful) {
            throw IOException("gazetteer download failed (${it.code})")
        }
        val bytes = it.body?.bytes() ?: throw IOException("gazetteer download empty")
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
    return target
}

private fun findGazetteerColumns(zipFile: Path): Triple<Int, Int, Int> {
    java.util.zip.ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name.endsWith(".txt")) {
                val reader = zis.bufferedReader()
                val header = reader.readLine() ?: throw IOException("gazetteer header missing")
                val columns = header.split('\t').map { it.trim() }
                val zipIndex = columns.indexOfFirst { it.equals("GEOID", true) || it.equals("ZCTA5", true) || it.equals("NAME", true) }
                val latIndex = columns.indexOfFirst { it.equals("INTPTLAT", true) || it.equals("INTPTLAT10", true) }
                val lonIndex = columns.indexOfFirst { it.equals("INTPTLONG", true) || it.equals("INTPTLON", true) || it.equals("INTPTLON10", true) }
                if (zipIndex < 0 || latIndex < 0 || lonIndex < 0) {
                    throw IOException("gazetteer columns not found")
                }
                return Triple(latIndex, lonIndex, zipIndex)
            }
            entry = zis.nextEntry
        }
    }
    throw IOException("gazetteer txt not found")
}

private fun findZipInGazetteer(
    zipFile: Path,
    zip: String,
    zipIndex: Int,
    latIndex: Int,
    lonIndex: Int,
): Pair<Double, Double>? {
    java.util.zip.ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name.endsWith(".txt")) {
                val reader = zis.bufferedReader()
                reader.readLine()
                var line = reader.readLine()
                while (line != null) {
                    val parts = line.split('\t')
                    if (parts.size > lonIndex) {
                        val code = parts[zipIndex].trim()
                        if (code == zip) {
                            val lat = parts[latIndex].trim().toDouble()
                            val lon = parts[lonIndex].trim().toDouble()
                            return lat to lon
                        }
                    }
                    line = reader.readLine()
                }
            }
            entry = zis.nextEntry
        }
    }
    return null
}

private fun updateEnvLatLon(lat: Double, lon: Double) {
    val envFile = findEnvFileOrRoot()
    val lines = if (Files.exists(envFile)) Files.readAllLines(envFile).toMutableList() else mutableListOf()
    var latSet = false
    var lonSet = false
    for (i in lines.indices) {
        if (lines[i].trimStart().startsWith("LAT=")) {
            lines[i] = "LAT=$lat"
            latSet = true
        }
        if (lines[i].trimStart().startsWith("LON=")) {
            lines[i] = "LON=$lon"
            lonSet = true
        }
    }
    if (!latSet) lines.add("LAT=$lat")
    if (!lonSet) lines.add("LON=$lon")
    Files.writeString(envFile, lines.joinToString("\n", postfix = "\n"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
}
