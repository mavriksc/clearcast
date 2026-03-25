package org.mavriksc.clearcast

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory

fun loadEnv(): Dotenv {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val envFile = dir.resolve(".env")
        if (Files.exists(envFile)) {
            return dotenv {
                directory = dir.toString()
                filename = ".env"
                ignoreIfMissing = true
            }
        }

        val gradleFile = dir.resolve("build.gradle.kts")
        if (Files.exists(gradleFile)) {
            return dotenv {
                directory = dir.toString()
                filename = ".env"
                ignoreIfMissing = true
            }
        }

        dir = dir.parent
    }

    return dotenv { ignoreIfMissing = true }
}

fun clearCacheOnStartupIfRequested() {
    val logger = LoggerFactory.getLogger("CacheCleanup")
    val env = loadEnv()
    val raw = env["CLEAR_CACHE"] ?: System.getenv("CLEAR_CACHE")
    val shouldClear = raw?.trim()?.lowercase() in setOf("1", "true", "yes", "y")
    if (!shouldClear) {
        logger.info("CLEAR_CACHE not set; skipping cache cleanup")
        return
    }
    logger.info("CLEAR_CACHE set; deleting data/ and responses/")
    deleteDirectoryIfExists(Path.of("data"))
    deleteDirectoryIfExists(Path.of("responses"))
}

fun findEnvFileOrRoot(): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val envFile = dir.resolve(".env")
        if (Files.exists(envFile)) {
            return envFile
        }

        val gradleFile = dir.resolve("build.gradle.kts")
        if (Files.exists(gradleFile)) {
            return envFile
        }

        dir = dir.parent
    }

    return Paths.get(".env").toAbsolutePath()
}

fun ensureLatLonFromZip() {
    val env = loadEnv()
    val lat = env["LAT"] ?: System.getenv("LAT")
    val lon = env["LON"] ?: System.getenv("LON")
    if (!lat.isNullOrBlank() && !lon.isNullOrBlank()) {
        return
    }

    val zip = env["ZIP_CODE"] ?: System.getenv("ZIP_CODE")
    if (zip.isNullOrBlank()) {
        return
    }

    val latLon = resolveLatLonFromZip(zip)
    updateEnvLatLon(latLon.first, latLon.second)
}

private fun resolveLatLonFromZip(zip: String): Pair<Double, Double> {
    val normalizedZip = zip.trim().padStart(5, '0')
    val gazetteerZip = ensureGazetteerZip()
    val (latIndex, lonIndex, zipIndex) = findGazetteerColumns(gazetteerZip)
    val match = findZipInGazetteer(gazetteerZip, normalizedZip, zipIndex, latIndex, lonIndex)
    return match ?: throw IllegalArgumentException("zip lookup failed for $normalizedZip in gazetteer data")
}

private fun ensureGazetteerZip(): Path {
    val dataDir = Path.of("data")
    Files.createDirectories(dataDir)
    val target = dataDir.resolve("2023_Gaz_zcta_national.zip")
    if (Files.exists(target)) {
        return target
    }

    val url = "https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2023_Gazetteer/2023_Gaz_zcta_national.zip"
    val request = okhttp3.Request.Builder().url(url).build()
    val response = okhttp3.OkHttpClient().newCall(request).execute()
    response.use {
        if (!it.isSuccessful) {
            throw IllegalArgumentException("gazetteer download failed (${it.code})")
        }
        val bytes = it.body?.bytes() ?: throw IllegalArgumentException("gazetteer download empty")
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    }
    return target
}

private fun findGazetteerColumns(zipFile: Path): Triple<Int, Int, Int> {
    java.util.zip.ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name.endsWith(".txt")) {
                val reader = zis.bufferedReader()
                val header = reader.readLine() ?: throw IllegalArgumentException("gazetteer header missing")
                val columns = header.split('\t').map { it.trim() }
                val zipIndex = columns.indexOfFirst { it.equals("GEOID", true) || it.equals("ZCTA5", true) || it.equals("NAME", true) }
                val latIndex = columns.indexOfFirst { it.equals("INTPTLAT", true) || it.equals("INTPTLAT10", true) }
                val lonIndex = columns.indexOfFirst { it.equals("INTPTLONG", true) || it.equals("INTPTLON", true) || it.equals("INTPTLON10", true) }
                if (zipIndex < 0 || latIndex < 0 || lonIndex < 0) {
                    throw IllegalArgumentException("gazetteer columns not found")
                }
                return Triple(latIndex, lonIndex, zipIndex)
            }
            entry = zis.nextEntry
        }
    }
    throw IllegalArgumentException("gazetteer txt not found")
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
    Files.writeString(envFile, lines.joinToString("\n", postfix = "\n"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
}

private fun deleteDirectoryIfExists(path: Path) {
    if (!Files.exists(path)) {
        return
    }
    Files.walk(path)
        .sorted(Comparator.reverseOrder())
        .forEach { Files.deleteIfExists(it) }
}
