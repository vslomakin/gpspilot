package com.github.gpspilot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory


data class Gpx(val wayPoints: List<WayPoint>, val track: List<TrackPoint>) {
    data class WayPoint(val name: String?, val lat: Double, val lon: Double)
    data class TrackPoint(val lat: Double, val lon: Double)
}

suspend fun DocumentBuilderFactory.parseGps(file: File): Gpx? = withContext(Dispatchers.IO) {
    val builder = newDocumentBuilder()
    val document = builder.parse(file)
    val root = document.documentElement

    val wayPoints = root.elementsByName("wpt") { it.wayPoint() }
    val trackPoints = root.elementsByName("trkpt") { it.trackPoint() }

    if (wayPoints != null && trackPoints != null && trackPoints.isNotEmpty()) {
        Gpx(wayPoints, trackPoints)
    } else null
}

private fun Node.wayPoint(): Gpx.WayPoint? {
    val attrs: NamedNodeMap? = attributes
    val lat = attrs?.doubleAttr("lat")
    val lon = attrs?.doubleAttr("lon")
    return if (lat != null && lon != null) {
        Gpx.WayPoint(
            name = childByName("name")?.textContent,
            lat = lat,
            lon = lon
        )
    } else null
}

private fun Node.trackPoint(): Gpx.TrackPoint? {
    val attrs: NamedNodeMap? = attributes
    val lat = attrs?.doubleAttr("lat")
    val lon = attrs?.doubleAttr("lon")
    return if (lat != null && lon != null) {
        Gpx.TrackPoint(lat = lat, lon = lon)
    } else null
}

private fun <T : Any> Element.elementsByName(name: String, map: (Node) -> T?): List<T>? {
    val node = getElementsByTagName(name)
    val resList = node.nodes().mapNotNull { map(it) }.toList()

    // If sizes of source and result lists aren't equal -
    // it means some of node hasn't been properly parsed, just return null
    return resList.takeIf { it.size == node.length }
}

private fun NodeList.nodes(): Sequence<Node> {
    val indexes = 0 until length
    return indexes.asSequence().map { item(it) }
}

private fun NamedNodeMap.doubleAttr(name: String?) = getNamedItem(name)?.textContent?.toDoubleOrNull()

private fun Node.childByName(name: String): Node? {
    return childNodes.nodes().firstOrNull { it.nodeName == name }
}