package org.mavriksc.clearcast.services

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mavriksc.clearcast.loadEnv

class RadarRidgeService(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://radar.weather.gov/ridge/standard",
    private val fallbackRetryAfterSeconds: Long = 5,
) {
    fun frameUrls(station: String, frameCount: Int = 10): List<String> {
        val code = station.uppercase()
        return (0 until frameCount).map { "$baseUrl/${code}_$it.gif" }
    }

    fun loopUrl(station: String): String {
        val code = station.uppercase()
        return "$baseUrl/${code}_loop.gif"
    }

    fun downloadFrames(station: String, targetDir: Path, frameCount: Int = 10): List<Path> {
        Files.createDirectories(targetDir)
        val paths = mutableListOf<Path>()
        frameUrls(station, frameCount).forEachIndexed { index, url ->
            val file = targetDir.resolve("${station.uppercase()}_$index.gif")
            download(url, file)
            paths.add(file)
        }
        return paths
    }

    fun downloadLoop(station: String, targetDir: Path): Path {
        Files.createDirectories(targetDir)
        val file = targetDir.resolve("${station.uppercase()}_loop.gif")
        download(loopUrl(station), file)
        return file
    }

    private fun download(url: String, target: Path) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (response.code == 429) {
            response.close()
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                ?: fallbackRetryAfterSeconds
            Thread.sleep(Duration.ofSeconds(retryAfterSeconds).toMillis())
            download(url, target)
            return
        }

        response.use {
            if (!it.isSuccessful) {
                throw IOException("radar request failed (${it.code}) for $url")
            }
            val body = it.body?.bytes() ?: throw IOException("radar empty body for $url")
            Files.write(target, body)
        }
    }
}

fun main() {
    val env = loadEnv()
    val station = resolveStation(env["RADAR_STATION"] ?: System.getenv("RADAR_STATION"))
    val client = buildClient(env["NWS_USER_AGENT"] ?: System.getenv("NWS_USER_AGENT"))
    val service = RadarRidgeService(client)

    val outDir = Path.of("responses", "radar")
    service.downloadFrames(station, outDir)
    service.downloadLoop(station, outDir)
}

private fun resolveStation(envValue: String?): String {
    if (!envValue.isNullOrBlank()) {
        return envValue.uppercase()
    }

    val pointsPath = Path.of("responses", "points.json")
    if (Files.exists(pointsPath)) {
        val json = Json.parseToJsonElement(Files.readString(pointsPath)).jsonObject
        val station = json["properties"]
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

private fun buildClient(userAgent: String?): OkHttpClient {
    if (userAgent.isNullOrBlank()) {
        return OkHttpClient()
    }

    return OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }
        .build()
}
