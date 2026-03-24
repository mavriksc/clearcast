package org.mavriksc.clearcast.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.Duration
import org.mavriksc.clearcast.AlertSeverity
import org.mavriksc.clearcast.AlertUrgency
import org.mavriksc.clearcast.AlertFilters
import org.mavriksc.clearcast.AlertsCard
import org.mavriksc.clearcast.ConditionTheme
import org.mavriksc.clearcast.CurrentConditionsCard
import org.mavriksc.clearcast.DailyForecastCard
import org.mavriksc.clearcast.DailyForecastPoint
import org.mavriksc.clearcast.HourlyForecastCard
import org.mavriksc.clearcast.HourlyForecastPoint
import org.mavriksc.clearcast.PrecipType
import org.mavriksc.clearcast.RadarCard
import org.mavriksc.clearcast.RadarFrame
import org.mavriksc.clearcast.RadarMode
import org.mavriksc.clearcast.WeatherAlert
import org.mavriksc.clearcast.loadEnv
import org.mavriksc.clearcast.loadAlertFilters

data class RefreshConfig(
    val currentSeconds: Long,
    val hourlySeconds: Long,
    val radarSeconds: Long,
    val dailySeconds: Long,
    val alertsSeconds: Long,
)

data class NextFetchTimes(
    val currentAt: Instant?,
    val hourlyAt: Instant?,
    val radarAt: Instant?,
    val dailyAt: Instant?,
    val alertsAt: Instant?,
) {
    companion object {
        fun empty(): NextFetchTimes = NextFetchTimes(null, null, null, null, null)
    }
}

class WeatherService(
    private val api: WeatherGovService,
    private val radar: RadarRidgeService,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true },
) {
    private val _currentFlow = MutableStateFlow<CurrentConditionsCard?>(null)
    private val _hourlyFlow = MutableStateFlow<HourlyForecastCard?>(null)
    private val _dailyFlow = MutableStateFlow<DailyForecastCard?>(null)
    private val _alertsFlow = MutableStateFlow<AlertsCard?>(null)
    private val _radarFlow = MutableStateFlow<RadarCard?>(null)
    private val _nextFetchFlow = MutableStateFlow(NextFetchTimes.empty())

    val currentFlow: StateFlow<CurrentConditionsCard?> = _currentFlow
    val hourlyFlow: StateFlow<HourlyForecastCard?> = _hourlyFlow
    val dailyFlow: StateFlow<DailyForecastCard?> = _dailyFlow
    val alertsFlow: StateFlow<AlertsCard?> = _alertsFlow
    val radarFlow: StateFlow<RadarCard?> = _radarFlow
    val nextFetchFlow: StateFlow<NextFetchTimes> = _nextFetchFlow

    fun start(config: RefreshConfig, dataDir: Path): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        loadCache(dataDir)
        loadSampleResponses(Path.of("responses"))
        refreshCurrentFromObservation()
        refreshRadar()
        scheduleFetches(scope, config, dataDir)
        return scope
    }

    private fun scheduleFetches(scope: CoroutineScope, config: RefreshConfig, dataDir: Path) {
        scope.launch {
            while (true) {
                val now = clock.instant()
                val next = NextFetchTimes(
                    currentAt = now.plusSeconds(config.currentSeconds),
                    hourlyAt = now.plusSeconds(config.hourlySeconds),
                    radarAt = now.plusSeconds(config.radarSeconds),
                    dailyAt = now.plusSeconds(config.dailySeconds),
                    alertsAt = now.plusSeconds(config.alertsSeconds),
                )
                _nextFetchFlow.value = next
                persistCache(dataDir)
                delay(Duration.ofSeconds(minOf(
                    config.currentSeconds,
                    config.hourlySeconds,
                    config.radarSeconds,
                    config.dailySeconds,
                    config.alertsSeconds
                )).toMillis())
            }
        }

        scope.launch {
            while (true) {
                delay(Duration.ofSeconds(config.currentSeconds).toMillis())
                refreshCurrentFromObservation()
            }
        }

        scope.launch {
            while (true) {
                delay(Duration.ofSeconds(config.hourlySeconds).toMillis())
                refreshHourlyForecast()
            }
        }

        scope.launch {
            while (true) {
                delay(Duration.ofSeconds(config.radarSeconds).toMillis())
                refreshRadar()
            }
        }

        scope.launch {
            while (true) {
                delay(Duration.ofSeconds(config.dailySeconds).toMillis())
                refreshDailyForecast()
            }
        }

        scope.launch {
            while (true) {
                delay(Duration.ofSeconds(config.alertsSeconds).toMillis())
                refreshAlerts()
            }
        }
    }

    private fun loadCache(dataDir: Path) {
        val cacheFile = dataDir.resolve("weather-cache.json")
        if (!Files.exists(cacheFile)) {
            return
        }

        val alertFilters = loadAlertFilters()
        val cached = json.decodeFromString(CachedWeather.serializer(), Files.readString(cacheFile))
        _currentFlow.value = cached.current?.toDomain()
        _hourlyFlow.value = cached.hourly?.toDomain()
        _dailyFlow.value = cached.daily?.toDomain()
        _alertsFlow.value = sanitizeAlerts(cached.alerts?.toDomain(), alertFilters)
        _radarFlow.value = cached.radar?.toDomain()
        _nextFetchFlow.value = cached.nextFetch?.toDomain() ?: NextFetchTimes.empty()
    }

    private fun persistCache(dataDir: Path) {
        Files.createDirectories(dataDir)
        val cacheFile = dataDir.resolve("weather-cache.json")
        val alertFilters = loadAlertFilters()
        val cached = CachedWeather(
            current = _currentFlow.value?.toCache(),
            hourly = _hourlyFlow.value?.toCache(),
            daily = _dailyFlow.value?.toCache(),
            alerts = sanitizeAlerts(_alertsFlow.value, alertFilters)?.toCache(),
            radar = _radarFlow.value?.toCache(),
            nextFetch = _nextFetchFlow.value.toCache(),
        )
        Files.writeString(cacheFile, json.encodeToString(CachedWeather.serializer(), cached))
    }

    private fun loadSampleResponses(sampleDir: Path) {
        if (!Files.exists(sampleDir)) {
            return
        }

        val points = readJson(sampleDir.resolve("points.json"))
        val hourly = readJson(sampleDir.resolve("forecast-hourly.json"))
        val daily = readJson(sampleDir.resolve("forecast.json"))
        val alerts = readJson(sampleDir.resolve("alerts.json"))

        if (points == null || hourly == null || daily == null) {
            return
        }

        val pointsInfo = parsePoints(points)
        if (_hourlyFlow.value == null) {
            _hourlyFlow.value = parseHourlyForecast(hourly, pointsInfo.timeZone)
        }
        if (_dailyFlow.value == null) {
            _dailyFlow.value = parseDailyForecast(daily, pointsInfo.timeZone)
        }
        if (_currentFlow.value == null) {
            _currentFlow.value = parseCurrentFromHourlyJson(hourly, pointsInfo.locationName)
        }
        if (_alertsFlow.value == null && alerts != null) {
            val alertFilters = loadAlertFilters()
            _alertsFlow.value = sanitizeAlerts(parseAlerts(alerts, alertFilters), alertFilters)
        }
    }

    private fun readJson(path: Path): JsonObject? {
        if (!Files.exists(path)) {
            return null
        }
        val content = Files.readString(path)
        return json.parseToJsonElement(content).jsonObject
    }

    private fun resolveRadarStation(): String {
        val env = loadEnv()
        val fromEnv = env["RADAR_STATION"] ?: System.getenv("RADAR_STATION")
        if (!fromEnv.isNullOrBlank()) {
            return fromEnv.uppercase()
        }

        val pointsPath = Path.of("responses", "points.json")
        if (Files.exists(pointsPath)) {
            val json = readJson(pointsPath)
            val station = json?.get("properties")
                ?.jsonObject
                ?.get("radarStation")
                ?.jsonPrimitive
                ?.content
            if (!station.isNullOrBlank()) {
                return station.uppercase()
            }
        }

        return "KFWS"
    }

    private fun refreshCurrentFromObservation() {
        val pointsData = fetchPointsData() ?: return
        val stationsUrl = pointsData.observationStationsUrl
        if (stationsUrl.isNullOrBlank()) {
            return
        }

        val stations = api.getObservationStations(stationsUrl)
        val station = extractStationIdentifier(stations) ?: return
        val observation = api.getLatestObservation(station)
        val current = parseCurrentFromObservationJson(observation, pointsData.info)
        _currentFlow.value = current
    }

    private fun extractStationIdentifier(stations: JsonObject): String? {
        val features = stations["features"]?.jsonArray ?: return null
        val first = features.firstOrNull()?.jsonObject ?: return null
        val id = first["id"]?.jsonPrimitive?.content
        if (!id.isNullOrBlank()) {
            return id
        }
        val stationId = first["properties"]
            ?.jsonObject
            ?.get("stationIdentifier")
            ?.jsonPrimitive
            ?.content
        return stationId
    }

    private fun refreshRadar() {
        val station = resolveRadarStation()
        val frameUrls = radar.frameUrls(station, 10)
        if (frameUrls.isEmpty()) {
            return
        }
        val now = clock.instant()
        val frames = frameUrls.mapIndexed { index, url ->
            RadarFrame(
                timestamp = now.minusSeconds((frameUrls.size - 1L - index) * 300L),
                url = url,
            )
        }
        _radarFlow.value = RadarCard(
            generatedAt = now,
            mode = RadarMode.PAST_ONLY,
            frames = frames,
        )
    }

    private fun refreshHourlyForecast() {
        val pointsData = fetchPointsData() ?: return
        val hourlyUrl = pointsData.forecastHourlyUrl ?: return
        val hourlyJson = api.getForecastHourly(hourlyUrl)
        _hourlyFlow.value = parseHourlyForecast(hourlyJson, pointsData.info.timeZone)
    }

    private fun refreshDailyForecast() {
        val pointsData = fetchPointsData() ?: return
        val forecastUrl = pointsData.forecastUrl ?: return
        val dailyJson = api.getForecast(forecastUrl)
        _dailyFlow.value = parseDailyForecast(dailyJson, pointsData.info.timeZone)
    }

    private fun refreshAlerts() {
        val pointsData = fetchPointsData() ?: return
        val stateCode = pointsData.info.stateCode
        val alertsJson = api.getAlertsByState(stateCode)
        val alertFilters = loadAlertFilters()
        _alertsFlow.value = sanitizeAlerts(parseAlerts(alertsJson, alertFilters), alertFilters)
    }

    private fun fetchPointsData(): PointsData? {
        val env = loadEnv()
        val lat = env["LAT"] ?: System.getenv("LAT")
        val lon = env["LON"] ?: System.getenv("LON")
        val latValue = lat?.toDoubleOrNull()
        val lonValue = lon?.toDoubleOrNull()
        if (latValue == null || lonValue == null) {
            return null
        }

        val points = api.getPoints(latValue, lonValue)
        val props = points["properties"]?.jsonObject ?: return null
        val forecastUrl = props["forecast"]?.jsonPrimitive?.content
        val forecastHourlyUrl = props["forecastHourly"]?.jsonPrimitive?.content
        val stationsUrl = props["observationStations"]?.jsonPrimitive?.content
        return PointsData(
            info = parsePoints(points),
            forecastUrl = forecastUrl,
            forecastHourlyUrl = forecastHourlyUrl,
            observationStationsUrl = stationsUrl,
        )
    }

    private fun sanitizeAlerts(alerts: AlertsCard?, filters: AlertFilters = loadAlertFilters()): AlertsCard? {
        if (alerts == null) {
            return null
        }
        val filtered = alerts.alerts.mapNotNull { alert ->
            val matchedAreas = filters.filterAreas(alert.areas)
            if (matchedAreas.isEmpty()) {
                null
            } else {
                alert.copy(areas = matchedAreas)
            }
        }
        return alerts.copy(alerts = filtered)
    }
}

private data class PointsData(
    val info: PointsInfo,
    val forecastUrl: String?,
    val forecastHourlyUrl: String?,
    val observationStationsUrl: String?,
)

@Serializable
data class CachedWeather(
    val current: CachedCurrentConditionsCard? = null,
    val hourly: CachedHourlyForecastCard? = null,
    val daily: CachedDailyForecastCard? = null,
    val alerts: CachedAlertsCard? = null,
    val radar: CachedRadarCard? = null,
    val nextFetch: CachedNextFetchTimes? = null,
)

@Serializable
data class CachedNextFetchTimes(
    val currentAtEpochMs: Long? = null,
    val hourlyAtEpochMs: Long? = null,
    val radarAtEpochMs: Long? = null,
    val dailyAtEpochMs: Long? = null,
    val alertsAtEpochMs: Long? = null,
) {
    fun toDomain(): NextFetchTimes = NextFetchTimes(
        currentAt = currentAtEpochMs?.let { Instant.ofEpochMilli(it) },
        hourlyAt = hourlyAtEpochMs?.let { Instant.ofEpochMilli(it) },
        radarAt = radarAtEpochMs?.let { Instant.ofEpochMilli(it) },
        dailyAt = dailyAtEpochMs?.let { Instant.ofEpochMilli(it) },
        alertsAt = alertsAtEpochMs?.let { Instant.ofEpochMilli(it) },
    )
}

@Serializable
data class CachedCurrentConditionsCard(
    val locationName: String,
    val temperatureF: Double,
    val feelsLikeF: Double?,
    val humidityPercent: Int?,
    val windMph: Double?,
    val condition: String,
    val icon: String,
    val isDaytime: Boolean,
    val observedAtEpochMs: Long,
    val conditionTheme: ConditionTheme = ConditionTheme.OTHER,
) {
    fun toDomain(): CurrentConditionsCard = CurrentConditionsCard(
        locationName = locationName,
        temperatureF = temperatureF,
        feelsLikeF = feelsLikeF,
        humidityPercent = humidityPercent,
        windMph = windMph,
        condition = condition,
        icon = icon,
        isDaytime = isDaytime,
        observedAt = Instant.ofEpochMilli(observedAtEpochMs),
        conditionTheme = conditionTheme,
    )
}

@Serializable
data class CachedHourlyForecastCard(
    val generatedAtEpochMs: Long,
    val hours: List<CachedHourlyForecastPoint>,
) {
    fun toDomain(): HourlyForecastCard = HourlyForecastCard(
        generatedAt = Instant.ofEpochMilli(generatedAtEpochMs),
        hours = hours.map { it.toDomain() },
    )
}

@Serializable
data class CachedHourlyForecastPoint(
    val timeEpochMs: Long,
    val temperatureF: Double,
    val precipChancePercent: Int?,
    val precipTypes: List<PrecipType>,
    val icon: String,
) {
    fun toDomain(): HourlyForecastPoint = HourlyForecastPoint(
        time = Instant.ofEpochMilli(timeEpochMs),
        temperatureF = temperatureF,
        precipChancePercent = precipChancePercent,
        precipTypes = precipTypes,
        icon = icon,
    )
}

@Serializable
data class CachedDailyForecastCard(
    val generatedAtEpochMs: Long,
    val days: List<CachedDailyForecastPoint>,
) {
    fun toDomain(): DailyForecastCard = DailyForecastCard(
        generatedAt = Instant.ofEpochMilli(generatedAtEpochMs),
        days = days.map { it.toDomain() },
    )
}

@Serializable
data class CachedDailyForecastPoint(
    val dateIso: String,
    val highF: Double,
    val lowF: Double,
    val precipChancePercent: Int?,
    val precipTypes: List<PrecipType>,
    val icon: String,
) {
    fun toDomain(): DailyForecastPoint = DailyForecastPoint(
        dateIso = dateIso,
        highF = highF,
        lowF = lowF,
        precipChancePercent = precipChancePercent,
        precipTypes = precipTypes,
        icon = icon,
    )
}

@Serializable
data class CachedAlertsCard(
    val generatedAtEpochMs: Long,
    val alerts: List<CachedWeatherAlert>,
) {
    fun toDomain(): AlertsCard = AlertsCard(
        generatedAt = Instant.ofEpochMilli(generatedAtEpochMs),
        alerts = alerts.map { it.toDomain() },
    )
}

@Serializable
data class CachedWeatherAlert(
    val title: String,
    val severity: AlertSeverity,
    val urgency: AlertUrgency,
    val areas: List<String>,
    val effectiveAtEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val description: String,
) {
    fun toDomain(): WeatherAlert = WeatherAlert(
        title = title,
        severity = severity,
        urgency = urgency,
        areas = areas,
        effectiveAt = effectiveAtEpochMs?.let { Instant.ofEpochMilli(it) },
        expiresAt = expiresAtEpochMs?.let { Instant.ofEpochMilli(it) },
        description = description,
    )
}

@Serializable
data class CachedRadarCard(
    val generatedAtEpochMs: Long,
    val mode: RadarMode,
    val frames: List<CachedRadarFrame>,
) {
    fun toDomain(): RadarCard = RadarCard(
        generatedAt = Instant.ofEpochMilli(generatedAtEpochMs),
        mode = mode,
        frames = frames.map { it.toDomain() },
    )
}

@Serializable
data class CachedRadarFrame(
    val timestampEpochMs: Long,
    val url: String,
) {
    fun toDomain(): RadarFrame = RadarFrame(
        timestamp = Instant.ofEpochMilli(timestampEpochMs),
        url = url,
    )
}

private fun CurrentConditionsCard.toCache(): CachedCurrentConditionsCard = CachedCurrentConditionsCard(
    locationName = locationName,
    temperatureF = temperatureF,
    feelsLikeF = feelsLikeF,
    humidityPercent = humidityPercent,
    windMph = windMph,
    condition = condition,
    icon = icon,
    isDaytime = isDaytime,
    observedAtEpochMs = observedAt.toEpochMilli(),
    conditionTheme = conditionTheme,
)

private fun HourlyForecastCard.toCache(): CachedHourlyForecastCard = CachedHourlyForecastCard(
    generatedAtEpochMs = generatedAt.toEpochMilli(),
    hours = hours.map { it.toCache() },
)

private fun HourlyForecastPoint.toCache(): CachedHourlyForecastPoint = CachedHourlyForecastPoint(
    timeEpochMs = time.toEpochMilli(),
    temperatureF = temperatureF,
    precipChancePercent = precipChancePercent,
    precipTypes = precipTypes,
    icon = icon,
)

private fun DailyForecastCard.toCache(): CachedDailyForecastCard = CachedDailyForecastCard(
    generatedAtEpochMs = generatedAt.toEpochMilli(),
    days = days.map { it.toCache() },
)

private fun DailyForecastPoint.toCache(): CachedDailyForecastPoint = CachedDailyForecastPoint(
    dateIso = dateIso,
    highF = highF,
    lowF = lowF,
    precipChancePercent = precipChancePercent,
    precipTypes = precipTypes,
    icon = icon,
)

private fun AlertsCard.toCache(): CachedAlertsCard = CachedAlertsCard(
    generatedAtEpochMs = generatedAt.toEpochMilli(),
    alerts = alerts.map { it.toCache() },
)

private fun WeatherAlert.toCache(): CachedWeatherAlert = CachedWeatherAlert(
    title = title,
    severity = severity,
    urgency = urgency,
    areas = areas,
    effectiveAtEpochMs = effectiveAt?.toEpochMilli(),
    expiresAtEpochMs = expiresAt?.toEpochMilli(),
    description = description,
)

private fun RadarCard.toCache(): CachedRadarCard = CachedRadarCard(
    generatedAtEpochMs = generatedAt.toEpochMilli(),
    mode = mode,
    frames = frames.map { it.toCache() },
)

private fun RadarFrame.toCache(): CachedRadarFrame = CachedRadarFrame(
    timestampEpochMs = timestamp.toEpochMilli(),
    url = url,
)

private fun NextFetchTimes.toCache(): CachedNextFetchTimes = CachedNextFetchTimes(
    currentAtEpochMs = currentAt?.toEpochMilli(),
    hourlyAtEpochMs = hourlyAt?.toEpochMilli(),
    radarAtEpochMs = radarAt?.toEpochMilli(),
    dailyAtEpochMs = dailyAt?.toEpochMilli(),
    alertsAtEpochMs = alertsAt?.toEpochMilli(),
)
