package org.mavriksc.clearcast.services

import java.awt.image.RenderedImage
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.ImageOutputStream
import kotlin.math.max
import kotlin.math.min
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mavriksc.clearcast.loadEnv

data class RadarWmsConfig(
    val baseUrl: String,
    val layer: String,
    val style: String,
    val width: Int,
    val height: Int,
    val format: String,
    val transparent: Boolean,
    val frameMinutes: Long,
    val frameCount: Int,
    val zoom: Double,
    val spanDegrees: Double,
    val gifDelayCs: Int,
    val baseMapUrl: String,
    val baseMapLayer: String,
    val baseMapStyle: String,
    val baseMapFormat: String,
    val radarOpacity: Float,
)

class RadarWmsService(
    private val client: OkHttpClient,
    private val config: RadarWmsConfig,
) {
    private var cachedTimes: List<Instant> = emptyList()
    private var cachedTimesAt: Instant? = null
    private val timesCacheTtlSeconds = 300L
    companion object {
        fun fromEnv(client: OkHttpClient): RadarWmsService {
            val env = loadEnv()
            fun get(name: String): String? = env[name] ?: System.getenv(name)
            fun getInt(name: String, default: Int): Int = get(name)?.toIntOrNull() ?: default
            fun getLong(name: String, default: Long): Long = get(name)?.toLongOrNull() ?: default
            fun getDouble(name: String, default: Double): Double = get(name)?.toDoubleOrNull() ?: default
            fun getBool(name: String, default: Boolean): Boolean {
                val raw = get(name)?.trim()?.lowercase()
                return when (raw) {
                    null -> default
                    "1", "true", "yes", "y" -> true
                    "0", "false", "no", "n" -> false
                    else -> default
                }
            }

            return RadarWmsService(
                client,
                RadarWmsConfig(
                    baseUrl = get("RADAR_WMS_URL")
                        ?: "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/wms",
                    layer = get("RADAR_WMS_LAYER") ?: "conus_bref_qcd",
                    style = get("RADAR_WMS_STYLE") ?: "radar_reflectivity",
                    width = getInt("RADAR_WMS_WIDTH", 700),
                    height = getInt("RADAR_WMS_HEIGHT", 700),
                    format = get("RADAR_WMS_FORMAT") ?: "image/png",
                    transparent = getBool("RADAR_WMS_TRANSPARENT", true),
                    frameMinutes = getLong("RADAR_WMS_FRAME_MIN", 15),
                    frameCount = getInt("RADAR_WMS_FRAMES", 12),
                    zoom = getDouble("RADAR_WMS_ZOOM", 2.0),
                    spanDegrees = getDouble("RADAR_WMS_SPAN_DEG", 6.0),
                    gifDelayCs = getInt("RADAR_GIF_DELAY_CS", 100),
                    baseMapUrl = get("RADAR_BASE_WMS_URL")
                        ?: "https://basemap.nationalmap.gov/arcgis/services/USGSTopo/MapServer/WMSServer",
                    baseMapLayer = get("RADAR_BASE_WMS_LAYER") ?: "0",
                    baseMapStyle = get("RADAR_BASE_WMS_STYLE") ?: "default",
                    baseMapFormat = get("RADAR_BASE_WMS_FORMAT") ?: "image/png",
                    radarOpacity = getDouble("RADAR_WMS_OPACITY", 0.8)
                        .coerceIn(0.0, 1.0)
                        .toFloat(),
                )
            )
        }
    }

    fun frameUrls(lat: Double, lon: Double, now: Instant = Instant.now()): List<String> {
        val bbox = buildBbox(lat, lon)
        return (0 until config.frameCount).map { index ->
            val timestamp = now.minusSeconds((config.frameCount - 1L - index) * config.frameMinutes * 60)
            buildUrl(bbox, timestamp)
        }
    }

    fun frameIntervalSeconds(): Long = config.frameMinutes * 60

    fun updateAndGetGif(lat: Double, lon: Double, targetDir: Path, now: Instant = Instant.now()): Path? {
        Files.createDirectories(targetDir)
        val bbox = buildBbox(lat, lon)
        val timestamps = selectFrameTimes(now)
        val expectedNames = timestamps.map { frameFileName(it) }.toSet()

        Files.list(targetDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }
                .filter { !expectedNames.contains(it.fileName.toString()) }
                .forEach { Files.deleteIfExists(it) }
        }

        val baseImage = fetchBaseMap(bbox, targetDir)
        val frames = mutableListOf<Path>()
        timestamps.forEach { instant ->
            val name = frameFileName(instant)
            val file = targetDir.resolve(name)
            if (!Files.exists(file)) {
                val url = buildUrl(bbox, instant)
                try {
                    download(url, file)
                    if (baseImage != null) {
                        composeWithBase(baseImage, file, instant)
                    }
                } catch (ex: IOException) {
                    if (Files.exists(file)) {
                        Files.deleteIfExists(file)
                    }
                }
            }
            if (Files.exists(file) && baseImage != null) {
                composeWithBase(baseImage, file, instant)
            }
            if (Files.exists(file)) {
                frames.add(file)
            }
        }

        if (frames.isEmpty()) {
            return null
        }

        val gifPath = targetDir.resolve("radar.gif")
        if (!writeGif(frames, gifPath)) {
            return null
        }
        return if (Files.exists(gifPath)) gifPath else null
    }

    private fun buildBbox(lat: Double, lon: Double): String {
        val span = if (config.zoom <= 0.0) config.spanDegrees else config.spanDegrees / config.zoom
        val half = span / 2.0
        val minLat = clamp(lat - half, -90.0, 90.0)
        val maxLat = clamp(lat + half, -90.0, 90.0)
        val minLon = clamp(lon - half, -180.0, 180.0)
        val maxLon = clamp(lon + half, -180.0, 180.0)
        return "${minLon},${minLat},${maxLon},${maxLat}"
    }

    private fun buildUrl(bbox: String, time: Instant): String {
        val timeIso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(time)

        val params = mapOf(
            "service" to "WMS",
            "request" to "GetMap",
            "version" to "1.1.1",
            "srs" to "EPSG:4326",
            "layers" to config.layer,
            "styles" to config.style,
            "format" to config.format,
            "transparent" to config.transparent.toString(),
            "width" to config.width.toString(),
            "height" to config.height.toString(),
            "bbox" to bbox,
            "time" to timeIso,
        )

        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "${config.baseUrl}?$query"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun clamp(value: Double, minValue: Double, maxValue: Double): Double =
        max(minValue, min(maxValue, value))

    private fun selectFrameTimes(now: Instant): List<Instant> {
        val available = fetchAvailableTimes(now)
        if (available.isEmpty()) {
            return emptyList()
        }
        val sorted = available.sorted()
        val latest = sorted.lastOrNull { !it.isAfter(now) } ?: sorted.last()
        val selected = mutableListOf<Instant>()
        selected.add(latest)

        val stepSeconds = config.frameMinutes * 60
        while (selected.size < config.frameCount) {
            val target = selected.last().minusSeconds(stepSeconds)
            val next = sorted.lastOrNull { !it.isAfter(target) } ?: break
            selected.add(next)
        }
        return selected.reversed()
    }

    private fun fetchAvailableTimes(now: Instant): List<Instant> {
        val cachedAt = cachedTimesAt
        if (cachedAt != null && now.isBefore(cachedAt.plusSeconds(timesCacheTtlSeconds))) {
            return cachedTimes
        }

        val url = "${config.baseUrl}?service=WMS&request=GetCapabilities&version=1.1.1"
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string() }
            if (body.isNullOrBlank()) {
                cachedTimes = emptyList()
                cachedTimesAt = now
                return emptyList()
            }
            val times = parseTimesFromCapabilities(body)
            cachedTimes = times
            cachedTimesAt = now
            times
        } catch (ex: Exception) {
            cachedTimes = emptyList()
            cachedTimesAt = now
            emptyList()
        }
    }

    private fun parseTimesFromCapabilities(xml: String): List<Instant> {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val domTimes = runCatching {
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(xml.byteInputStream())
            val layers = document.getElementsByTagNameNS("*", "Layer")
            for (i in 0 until layers.length) {
                val layer = layers.item(i) as? org.w3c.dom.Element ?: continue
                val name = childText(layer, "Name") ?: continue
                val normalized = name.substringAfter(":")
                if (normalized != config.layer && name != config.layer) {
                    continue
                }
                val timeText = childTimeExtent(layer)
                if (!timeText.isNullOrBlank()) {
                    return@runCatching parseTimeList(timeText)
                }
            }
            emptyList()
        }.getOrElse { emptyList() }

        if (domTimes.isNotEmpty()) {
            return domTimes
        }
        return parseTimesFromRegex(xml)
    }

    private fun parseTimeList(content: String): List<Instant> {
        if (content.isBlank()) {
            return emptyList()
        }
        if (content.contains("/") && !content.contains(",")) {
            val parts = content.split("/")
            if (parts.size == 3) {
                val start = runCatching { Instant.parse(parts[0].trim()) }.getOrNull()
                val end = runCatching { Instant.parse(parts[1].trim()) }.getOrNull()
                val step = runCatching { java.time.Duration.parse(parts[2].trim()) }.getOrNull()
                if (start != null && end != null && step != null && !step.isZero) {
                    val values = mutableListOf<Instant>()
                    val startVal = start!!
                    val endVal = end!!
                    val stepVal = step!!
                    var current = startVal
                    while (!current.isAfter(endVal)) {
                        values.add(current)
                        current = current.plus(stepVal)
                    }
                    return values
                }
            }
        }
        val parts = content.split(",")
        return parts.mapNotNull { raw ->
            val value = raw.trim()
            if (value.isEmpty()) return@mapNotNull null
            runCatching { Instant.parse(value) }.getOrNull()
        }
    }

    private fun parseTimesFromRegex(xml: String): List<Instant> {
        val layerName = Regex.escape(config.layer)
        val layerPattern = Regex(
            "<Layer[^>]*>.*?<Name>\\s*(?:\\w+:)?$layerName\\s*</Name>.*?<Extent[^>]*name=\\\"time\\\"[^>]*>([^<]+)</Extent>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = layerPattern.find(xml)
        if (match != null) {
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val parsed = parseTimeList(content)
            if (parsed.isNotEmpty()) {
                return parsed
            }
        }

        val fallbackPattern = Regex("<Extent[^>]*name=\\\"time\\\"[^>]*>([^<]+)</Extent>")
        val fallback = fallbackPattern.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return parseTimeList(fallback)
    }

    private fun childText(parent: org.w3c.dom.Element, localName: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val element = node as org.w3c.dom.Element
                val name = element.localName ?: element.nodeName
                if (name.equals(localName, ignoreCase = true)) {
                    return element.textContent?.trim()
                }
            }
        }
        return null
    }

    private fun childTimeExtent(parent: org.w3c.dom.Element): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val element = node as org.w3c.dom.Element
                val name = element.localName ?: element.nodeName
                if (name.equals("Extent", ignoreCase = true) || name.equals("Dimension", ignoreCase = true)) {
                    val attr = element.getAttribute("name")
                    if (attr.equals("time", ignoreCase = true)) {
                        return element.textContent?.trim()
                    }
                }
            }
        }
        return null
    }

    private fun frameFileName(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneOffset.UTC)
        return "radar_${formatter.format(instant)}.png"
    }

    private fun download(url: String, target: Path) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("wms request failed (${it.code}) for $url")
            }
            val body = it.body?.bytes() ?: throw IOException("wms empty body for $url")
            Files.write(target, body)
        }
    }

    private fun fetchBaseMap(bbox: String, targetDir: Path): java.awt.image.BufferedImage? {
        val file = targetDir.resolve("basemap.png")
        val url = buildBaseMapUrl(bbox)
        return try {
            download(url, file)
            ImageIO.read(file.toFile())
        } catch (ex: IOException) {
            if (Files.exists(file)) {
                Files.deleteIfExists(file)
            }
            null
        }
    }

    private fun buildBaseMapUrl(bbox: String): String {
        val params = mapOf(
            "service" to "WMS",
            "request" to "GetMap",
            "version" to "1.1.1",
            "srs" to "EPSG:4326",
            "layers" to config.baseMapLayer,
            "styles" to config.baseMapStyle,
            "format" to config.baseMapFormat,
            "transparent" to "false",
            "width" to config.width.toString(),
            "height" to config.height.toString(),
            "bbox" to bbox,
        )

        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "${config.baseMapUrl}?$query"
    }

    private fun composeWithBase(base: java.awt.image.BufferedImage, overlayPath: Path, timestamp: Instant) {
        val overlay = ImageIO.read(overlayPath.toFile()) ?: return
        val combined = java.awt.image.BufferedImage(base.width, base.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = combined.createGraphics()
        g.drawImage(base, 0, 0, null)
        g.composite = java.awt.AlphaComposite.getInstance(
            java.awt.AlphaComposite.SRC_OVER,
            config.radarOpacity,
        )
        g.drawImage(overlay, 0, 0, null)
        drawTimestamp(g, combined.width, combined.height, timestamp)
        g.dispose()
        ImageIO.write(combined, "png", overlayPath.toFile())
    }

    private fun drawTimestamp(g: java.awt.Graphics2D, width: Int, height: Int, timestamp: Instant) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
        val text = formatter.format(timestamp)
        val font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 18)
        g.font = font
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val metrics = g.fontMetrics
        val padding = 6
        val textWidth = metrics.stringWidth(text)
        val textHeight = metrics.height
        val x = width - textWidth - padding * 2
        val y = height - padding

        g.color = java.awt.Color(0, 0, 0, 160)
        g.fillRoundRect(x - padding, y - textHeight, textWidth + padding * 2, textHeight + padding, 8, 8)

        g.color = java.awt.Color(255, 255, 255, 230)
        g.drawString(text, x, y - metrics.descent)
    }

    private fun writeGif(frames: List<Path>, target: Path): Boolean {
        val first = ImageIO.read(frames.first().toFile()) ?: return false
        val output = ImageIO.createImageOutputStream(target.toFile())
        output.use {
            val imageType = if (first.type == 0) java.awt.image.BufferedImage.TYPE_INT_ARGB else first.type
            val delayCs = config.gifDelayCs.coerceIn(0, 65535)
            val writer = GifSequenceWriter(
                output,
                imageType,
                delayCs,
                true,
            )
            writer.writeToSequence(first)
            frames.drop(1).forEach { frame ->
                val image = ImageIO.read(frame.toFile()) ?: return@forEach
                writer.writeToSequence(image)
            }
            writer.close()
        }
        return Files.exists(target)
    }
}

private class GifSequenceWriter(
    private val outputStream: ImageOutputStream,
    imageType: Int,
    timeBetweenFramesCs: Int,
    loopContinuously: Boolean,
) {
    private val writer = ImageIO.getImageWritersBySuffix("gif").next()
    private val imageTypeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(imageType)
    private val imageMetaData = writer.getDefaultImageMetadata(imageTypeSpecifier, writer.defaultWriteParam)

    init {
        val metaFormatName = imageMetaData.nativeMetadataFormatName
        val root = imageMetaData.getAsTree(metaFormatName) as IIOMetadataNode

        val graphicsControlExtensionNode = getNode(root, "GraphicControlExtension")
        graphicsControlExtensionNode.setAttribute("disposalMethod", "none")
        graphicsControlExtensionNode.setAttribute("userInputFlag", "FALSE")
        graphicsControlExtensionNode.setAttribute("transparentColorFlag", "FALSE")
        graphicsControlExtensionNode.setAttribute("delayTime", timeBetweenFramesCs.toString())
        graphicsControlExtensionNode.setAttribute("transparentColorIndex", "0")

        val appExtensionsNode = getNode(root, "ApplicationExtensions")
        val appExtension = IIOMetadataNode("ApplicationExtension")
        appExtension.setAttribute("applicationID", "NETSCAPE")
        appExtension.setAttribute("authenticationCode", "2.0")
        val loop = if (loopContinuously) 0 else 1
        appExtension.userObject = byteArrayOf(0x1, (loop and 0xFF).toByte(), ((loop shr 8) and 0xFF).toByte())
        appExtensionsNode.appendChild(appExtension)

        imageMetaData.setFromTree(metaFormatName, root)
        writer.output = outputStream
        writer.prepareWriteSequence(null)
    }

    fun writeToSequence(img: RenderedImage) {
        writer.writeToSequence(IIOImage(img, null, imageMetaData), writer.defaultWriteParam)
    }

    fun close() {
        writer.endWriteSequence()
    }

    private fun getNode(root: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until root.length) {
            val node = root.item(i) as IIOMetadataNode
            if (node.nodeName.equals(name, ignoreCase = true)) {
                return node
            }
        }
        val node = IIOMetadataNode(name)
        root.appendChild(node)
        return node
    }
}
