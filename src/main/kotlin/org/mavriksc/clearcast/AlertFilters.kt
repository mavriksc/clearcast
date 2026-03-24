package org.mavriksc.clearcast

data class AlertFilters(
    val sensitive: List<String>,
    val insensitive: List<String>,
) {
    fun filterAreas(areas: List<String>): List<String> {
        return areas.filter { area -> matches(area) }
    }

    private fun matches(area: String): Boolean {
        if (sensitive.any { it.isNotBlank() && area.contains(it) }) {
            return true
        }
        return insensitive.any { it.isNotBlank() && area.contains(it, ignoreCase = true) }
    }
}

fun loadAlertFilters(): AlertFilters {
    val env = loadEnv()
    val sensitive = parseFilterList(
        env["ALERT_FILTER"] ?: System.getenv("ALERT_FILTER") ?: "TX"
    )
    val insensitive = parseFilterList(
        env["ALERT_FILTER_INSENSITIVE"]
            ?: System.getenv("ALERT_FILTER_INSENSITIVE")
            ?: "dallas,farmers branch,addison,tyler,tornado,t-storm,thunderstorm"
    )
    return AlertFilters(
        sensitive = sensitive,
        insensitive = insensitive,
    )
}

private fun parseFilterList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) {
        return emptyList()
    }
    return raw.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
