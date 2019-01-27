package com.github.gpspilot

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.*
import java.io.File
import java.util.*
import javax.xml.parsers.DocumentBuilder


data class Gpx(
    val name: String,
    val wayPoints: List<WayPoint>,
    val track: List<LatLng>,
    val creation: Date
) {
    data class WayPoint(val name: String?, val location: LatLng)
}

/**
 * Trying to parse file. In case of error `null` will be returned.
 */
suspend fun DocumentBuilder.parseGps(file: File): Gpx? = withContext(Dispatchers.IO) {
    val document: Document? = try {
        parse(file)
    } catch (e: Exception) {
        e logE { "Error occurs during file $file parsing." }
        null
    }
    val root = document?.documentElement

    val name = root?.name()

    val wayPointNodes = root?.getElementsByTagName("wpt")?.nodes()
    val wayPoints = wayPointNodes?.mapNodes { it.wayPoint() }

    val trackNodes = root?.getElementsByTagName("trkpt")?.nodes()
    val trackPoints = trackNodes?.mapNodes { it.latLng() }

    val createdTime = trackNodes?.lastOrNull()?.childByName("time")?.textContent
    val created = createdTime?.parseDate("yyyy-MM-dd'T'HH:mm:ss")

    if (name != null && wayPoints != null && trackPoints != null && created != null && trackPoints.isNotEmpty()) {
        Gpx(name, wayPoints, trackPoints, created)
    } else {
        null
    }
}

/**
 * Retrieves name of gpx file or `null` if it unavailable.
 */
private fun Element.name(): String? {
    val metadata: Node? = getElementsByTagName("metadata").item(0)
    return metadata?.childByName("name")?.textContent
}

/**
 * Retrieves [Gpx.WayPoint] from current [Node] or `null` if it unavailable.
 */
private fun Node.wayPoint(): Gpx.WayPoint? {
    val attrs: NamedNodeMap? = attributes
    val lat = attrs?.doubleAttr("lat")
    val lon = attrs?.doubleAttr("lon")
    return if (lat != null && lon != null) {
        Gpx.WayPoint(
            name = childByName("name")?.textContent,
            location = LatLng(lat, lon)
        )
    } else null
}

/**
 * Retrieves [LatLng] from current [Node] or `null` if it unavailable.
 */
private fun Node.latLng(): LatLng? {
    val attrs: NamedNodeMap? = attributes
    val lat = attrs?.doubleAttr("lat")
    val lon = attrs?.doubleAttr("lon")
    return if (lat != null && lon != null) {
        LatLng(lat, lon)
    } else null
}

private fun <T : Any> Element.elementsByName(name: String, map: (Node) -> T?): List<T>? {
    val node = getElementsByTagName(name)
    val resList = node.nodes().mapNotNull { map(it) }.toList()

    // If sizes of source and result lists aren't equal -
    // it means some of node hasn't been properly parsed, just return null
    return resList.takeIf { it.size == node.length }
}

/**
 * Map each node with [mapper].
 * If [mapper] returns `null` - it means node is invalid, `null` will be returned.
 */
private fun <T : Any> List<Node>.mapNodes(mapper: (Node) -> T?): List<T>? {
    val resultList = mapNotNull { mapper(it) }.toList()

    // If sizes of source and result lists aren't equal -
    // it means some of node hasn't been properly parsed, just return null
    return resultList.takeIf { it.size == size }
}

private fun NodeList.nodes(): List<Node> {
    val indexes = 0 until length
    return indexes.asSequence().map { item(it) }.toList()
}

/**
 * Tries to get [Double] argument with [name] or `null` if it imposable.
 */
private fun NamedNodeMap.doubleAttr(name: String?) = getNamedItem(name)?.textContent?.toDoubleOrNull()

/**
 * Returns first [Node] of current node children or `null` if isn't found.
 */
private fun Node.childByName(name: String): Node? {
    return childNodes.nodes().firstOrNull { it.nodeName == name }
}