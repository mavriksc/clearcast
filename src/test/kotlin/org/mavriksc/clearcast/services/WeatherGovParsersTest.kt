package org.mavriksc.clearcast.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.mavriksc.clearcast.AlertFilters
import org.mavriksc.clearcast.AlertSeverity
import org.mavriksc.clearcast.AlertUrgency

class WeatherGovParsersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parseAlerts preserves details and filters affected areas`() {
        val payload = json.parseToJsonElement(
            """
            {
              "updated": "2026-04-28T20:43:00+00:00",
              "features": [
                {
                  "properties": {
                    "event": "Tornado Warning",
                    "headline": "Tornado Warning issued April 28",
                    "severity": "Extreme",
                    "urgency": "Immediate",
                    "areaDesc": "Jack, TX; Wise, TX; Outside County",
                    "effective": "2026-04-28T20:43:00+00:00",
                    "expires": "2026-04-28T21:00:00+00:00",
                    "description": "At 343 PM CDT, a severe thunderstorm capable of producing a tornado was located near Cundiff.\n\nHAZARD...Tornado and quarter size hail.",
                    "instruction": "TAKE COVER NOW! Move to an interior room on the lowest floor."
                  }
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val alerts = parseAlerts(
            payload,
            AlertFilters(
                sensitive = listOf("TX"),
                insensitive = listOf("wise"),
            ),
        )

        assertEquals(1, alerts.alerts.size)
        val alert = alerts.alerts.single()
        assertEquals("Tornado Warning", alert.title)
        assertEquals("Tornado Warning issued April 28", alert.headline)
        assertEquals(AlertSeverity.EXTREME, alert.severity)
        assertEquals(AlertUrgency.IMMEDIATE, alert.urgency)
        assertEquals(listOf("Jack, TX", "Wise, TX"), alert.areas)
        assertEquals(
            "Tornado Warning issued April 28 At 343 PM CDT, a severe thunderstorm capable of producing a tornado was located near Cundiff. HAZARD. Tornado and quarter size hail.",
            alert.displayDescription,
        )
        assertEquals("TAKE COVER NOW! Move to an interior room on the lowest floor.", alert.displayInstruction)
    }

    @Test
    fun `local alert filters keep target counties and ignore unrelated Texas areas`() {
        val filters = AlertFilters(
            sensitive = emptyList(),
            insensitive = listOf("dallas", "collin", "denton", "tarrant", "farmers branch"),
        )

        val matchedAreas = filters.filterAreas(
            listOf(
                "Dallas, TX",
                "Collin, TX",
                "Denton, TX",
                "Tarrant, TX",
                "Farmers Branch",
                "Jack, TX",
                "Young, TX",
            )
        )

        assertEquals(
            listOf("Dallas, TX", "Collin, TX", "Denton, TX", "Tarrant, TX", "Farmers Branch"),
            matchedAreas,
        )
    }

    @Test
    fun `cached alerts without instruction still decode`() {
        val cached = json.decodeFromString(
            CachedWeatherAlert.serializer(),
            """
            {
              "title": "Severe Thunderstorm Warning",
              "severity": "SEVERE",
              "urgency": "IMMEDIATE",
              "areas": ["Jack, TX"],
              "effectiveAtEpochMs": 1777408380000,
              "expiresAtEpochMs": 1777409100000,
              "description": "Wind and hail expected."
            }
            """.trimIndent(),
        )

        val alert = cached.toDomain()

        assertEquals("Severe Thunderstorm Warning", alert.title)
        assertEquals("", alert.headline)
        assertEquals("Wind and hail expected.", alert.description)
        assertEquals("", alert.instruction)
        assertFalse(alert.displayDescription.isBlank())
    }
}
