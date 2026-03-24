package org.mavriksc.clearcast.services

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.mavriksc.clearcast.WeatherAlert
import org.mavriksc.clearcast.loadAlertFilters

data class PointsInfo(
    val locationName: String,
    val stateCode: String,
    val timeZone: ZoneId,
    val sunrise: Instant?,
    val sunset: Instant?,
)

fun parsePoints(points: JsonObject): PointsInfo {
    val props = points.obj("properties")
    val relative = props.obj("relativeLocation").obj("properties")
    val city = relative.str("city") ?: "Unknown"
    val state = relative.str("state") ?: "NA"
    val timeZone = props.str("timeZone") ?: "UTC"
    val astro = props.obj("astronomicalData")
    val sunrise = astro.str("sunrise")?.toInstant()
    val sunset = astro.str("sunset")?.toInstant()
    return PointsInfo(
        locationName = "$city, $state",
        stateCode = state,
        timeZone = ZoneId.of(timeZone),
        sunrise = sunrise,
        sunset = sunset,
    )
}

fun parseHourlyForecast(hourly: JsonObject, zone: ZoneId): HourlyForecastCard {
    val props = hourly.obj("properties")
    val generatedAt = props.str("generatedAt")?.toInstant() ?: Instant.now()
    val periods = props.array("periods")
    val rawHours = periods
        .mapNotNull { it.objOrNull() }
        .map { period ->
            val time = period.str("startTime")?.toInstant() ?: Instant.now()
            val temp = period.num("temperature") ?: 0.0
            val pop = period.obj("probabilityOfPrecipitation").num("value")?.roundToInt()
            val icon = period.str("icon") ?: ""
            val shortForecast = period.str("shortForecast") ?: ""
            HourlyForecastPoint(
                time = time,
                temperatureF = temp,
                precipChancePercent = pop,
                precipTypes = parsePrecipTypes(shortForecast),
                icon = icon,
            )
        }
    val currentHour = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
    val hours = rawHours
        .filter { !it.time.isBefore(currentHour) }
        .take(24)
        .ifEmpty { rawHours.take(24) }

    return HourlyForecastCard(
        generatedAt = generatedAt,
        hours = hours,
    )
}

fun parseDailyForecast(daily: JsonObject, zone: ZoneId): DailyForecastCard {
    val props = daily.obj("properties")
    val generatedAt = props.str("generatedAt")?.toInstant() ?: Instant.now()
    val periods = props.array("periods")
        .mapNotNull { it.objOrNull() }
        .map { period ->
            val start = period.str("startTime")?.toInstant() ?: Instant.now()
            val localDate = ZonedDateTime.ofInstant(start, zone).toLocalDate()
            val temp = period.num("temperature") ?: 0.0
            val pop = period.obj("probabilityOfPrecipitation").num("value")?.roundToInt()
            val icon = period.str("icon") ?: ""
            val shortForecast = period.str("shortForecast") ?: ""
            DailyPeriod(
                date = localDate,
                tempF = temp,
                precipChance = pop,
                icon = icon,
                forecastText = shortForecast,
            )
        }

    val days = periods
        .groupBy { it.date }
        .toSortedMap()
        .values
        .take(5)
        .map { bucket ->
            val high = bucket.maxOfOrNull { it.tempF } ?: 0.0
            val low = bucket.minOfOrNull { it.tempF } ?: 0.0
            val precip = bucket.mapNotNull { it.precipChance }.maxOrNull()
            val icons = bucket.mapNotNull { it.icon }.filter { it.isNotBlank() }
            val types = bucket.flatMap { parsePrecipTypes(it.forecastText) }.distinct()
            DailyForecastPoint(
                dateIso = bucket.first().date.toString(),
                highF = high,
                lowF = low,
                precipChancePercent = precip,
                precipTypes = if (types.isEmpty()) listOf(PrecipType.NONE) else types,
                icon = icons.firstOrNull() ?: "",
            )
        }

    return DailyForecastCard(
        generatedAt = generatedAt,
        days = days,
    )
}

fun parseCurrentFromHourlyJson(
    hourly: JsonObject,
    locationName: String,
): CurrentConditionsCard {
    val period = hourly.obj("properties").array("periods")
        .firstOrNull()
        ?.objOrNull()
    val temp = period?.num("temperature") ?: 0.0
    val humidity = period?.obj("relativeHumidity")?.num("value")?.roundToInt()
    val windMph = period?.str("windSpeed")?.let { parseWindMph(it) }
    val shortForecast = period?.str("shortForecast") ?: "Unknown"
    val icon = period?.str("icon") ?: ""
    val isDaytime = period?.jsonPrimitive("isDaytime")?.content?.toBooleanStrictOrNull() ?: true
    val observedAt = period?.str("startTime")?.toInstant() ?: Instant.now()

    return CurrentConditionsCard(
        locationName = locationName,
        temperatureF = temp,
        feelsLikeF = null,
        humidityPercent = humidity,
        windMph = windMph,
        condition = shortForecast,
        icon = icon,
        isDaytime = isDaytime,
        observedAt = observedAt,
        conditionTheme = inferConditionTheme(shortForecast),
    )
}

fun parseCurrentFromObservationJson(
    observation: JsonObject,
    pointsInfo: PointsInfo,
): CurrentConditionsCard {
    val props = observation.obj("properties")
    val observedAt = props.str("timestamp")?.toInstant() ?: Instant.now()
    val tempValue = props.obj("temperature").num("value")
    val tempUnit = props.obj("temperature").str("unitCode")
    val tempF = tempValue?.let { toFahrenheit(it, tempUnit) } ?: 0.0

    val heatIndexValue = props.obj("heatIndex").num("value")
    val heatIndexUnit = props.obj("heatIndex").str("unitCode")
    val windChillValue = props.obj("windChill").num("value")
    val windChillUnit = props.obj("windChill").str("unitCode")
    val feelsLikeF = when {
        heatIndexValue != null -> toFahrenheit(heatIndexValue, heatIndexUnit)
        windChillValue != null -> toFahrenheit(windChillValue, windChillUnit)
        else -> null
    }

    val humidity = props.obj("relativeHumidity").num("value")?.roundToInt()
    val windSpeedValue = props.obj("windSpeed").num("value")
    val windSpeedUnit = props.obj("windSpeed").str("unitCode")
    val windMph = windSpeedValue?.let { toMph(it, windSpeedUnit) }
    val condition = props.str("textDescription") ?: "Unknown"
    val icon = props.str("icon") ?: ""

    val isDaytime = if (pointsInfo.sunrise != null && pointsInfo.sunset != null) {
        !observedAt.isBefore(pointsInfo.sunrise) && observedAt.isBefore(pointsInfo.sunset)
    } else {
        val localHour = observedAt.atZone(pointsInfo.timeZone).hour
        localHour in 6..18
    }

    return CurrentConditionsCard(
        locationName = pointsInfo.locationName,
        temperatureF = tempF,
        feelsLikeF = feelsLikeF,
        humidityPercent = humidity,
        windMph = windMph,
        condition = condition,
        icon = icon,
        isDaytime = isDaytime,
        observedAt = observedAt,
        conditionTheme = inferConditionTheme(condition),
    )
}

fun parseAlerts(alerts: JsonObject, filters: AlertFilters = loadAlertFilters()): AlertsCard {
    val updatedAt = alerts.str("updated")?.toInstant() ?: Instant.now()
    val features = alerts.array("features")
    val items = features
        .mapNotNull { it.objOrNull() }
        .mapNotNull { feature ->
            val props = feature.objOrNull("properties") ?: return@mapNotNull null
            val title = props.str("event") ?: props.str("headline") ?: "Alert"
            val severity = props.str("severity")?.toSeverity() ?: AlertSeverity.UNKNOWN
            val urgency = props.str("urgency")?.toUrgency() ?: AlertUrgency.UNKNOWN
            val areas = props.str("areaDesc")
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val matchedAreas = filters.filterAreas(areas)
            if (matchedAreas.isEmpty()) {
                return@mapNotNull null
            }
            val effective = props.str("effective")?.toInstant()
            val expires = props.str("expires")?.toInstant()
            val description = props.str("description") ?: ""
            WeatherAlert(
                title = title,
                severity = severity,
                urgency = urgency,
                areas = matchedAreas,
                effectiveAt = effective,
                expiresAt = expires,
                description = description,
            )
        }

    return AlertsCard(
        generatedAt = updatedAt,
        alerts = items,
    )
}

private data class DailyPeriod(
    val date: LocalDate,
    val tempF: Double,
    val precipChance: Int?,
    val icon: String,
    val forecastText: String,
)

private fun parsePrecipTypes(text: String): List<PrecipType> {
    val lower = text.lowercase()
    val types = mutableListOf<PrecipType>()
    if ("rain" in lower) types.add(PrecipType.RAIN)
    if ("snow" in lower) types.add(PrecipType.SNOW)
    if ("sleet" in lower || "ice" in lower) types.add(PrecipType.SLEET)
    if ("hail" in lower) types.add(PrecipType.HAIL)
    if ("mix" in lower || ("rain" in lower && "snow" in lower)) types.add(PrecipType.MIX)
    return types.ifEmpty { listOf(PrecipType.NONE) }
}

private fun inferConditionTheme(text: String): ConditionTheme {
    val lower = text.lowercase()
    return when {
        "snow" in lower -> ConditionTheme.SNOW
        "thunder" in lower || "storm" in lower -> ConditionTheme.STORM
        "rain" in lower || "shower" in lower -> ConditionTheme.RAIN
        "fog" in lower || "mist" in lower -> ConditionTheme.FOG
        "haze" in lower || "smoke" in lower -> ConditionTheme.HAZE
        "wind" in lower || "breezy" in lower -> ConditionTheme.WINDY
        "mostly clear" in lower || "clear" in lower -> ConditionTheme.CLEAR
        "mostly sunny" in lower || "sunny" in lower -> ConditionTheme.SUNNY
        "partly" in lower -> ConditionTheme.PARTLY_CLOUDY
        "cloud" in lower || "overcast" in lower -> ConditionTheme.CLOUDY
        else -> ConditionTheme.OTHER
    }
}

private fun toFahrenheit(value: Double, unitCode: String?): Double {
    if (unitCode == null) {
        return value
    }
    return when {
        unitCode.contains("degF") -> value
        unitCode.contains("degC") -> value * 9.0 / 5.0 + 32.0
        else -> value
    }
}

private fun toMph(value: Double, unitCode: String?): Double {
    if (unitCode == null) {
        return value
    }
    return when {
        unitCode.contains("m_s-1") -> value * 2.23694
        unitCode.contains("km_h-1") -> value * 0.621371
        unitCode.contains("kn") -> value * 1.15078
        else -> value
    }
}

private fun parseWindMph(text: String): Double? {
    val match = Regex("""\d+""").find(text) ?: return null
    return match.value.toDoubleOrNull()
}

private fun String.toInstant(): Instant = OffsetDateTime.parse(this).toInstant()

private fun String.toSeverity(): AlertSeverity = when (this.uppercase()) {
    "MINOR" -> AlertSeverity.MINOR
    "MODERATE" -> AlertSeverity.MODERATE
    "SEVERE" -> AlertSeverity.SEVERE
    "EXTREME" -> AlertSeverity.EXTREME
    else -> AlertSeverity.UNKNOWN
}

private fun String.toUrgency(): AlertUrgency = when (this.uppercase()) {
    "IMMEDIATE" -> AlertUrgency.IMMEDIATE
    "EXPECTED" -> AlertUrgency.EXPECTED
    "FUTURE" -> AlertUrgency.FUTURE
    "PAST" -> AlertUrgency.PAST
    else -> AlertUrgency.UNKNOWN
}

private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: JsonObject(emptyMap())

private fun JsonObject.objOrNull(key: String): JsonObject? = this[key]?.jsonObject

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.jsonPrimitive(key: String): kotlinx.serialization.json.JsonPrimitive? =
    this[key]?.jsonPrimitive

private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement.objOrNull(): JsonObject? = (this as? JsonObject)
