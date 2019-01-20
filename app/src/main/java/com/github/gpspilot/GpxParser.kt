package com.github.gpspilot

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory


data class Gpx(
    val name: String,
    val wayPoints: List<WayPoint>,
    val track: List<LatLng>
) {
    data class WayPoint(val name: String?, val location: LatLng)
}

suspend fun DocumentBuilderFactory.parseGps(file: File): Gpx? = withContext(Dispatchers.IO) {
    // TODO: handle IO errors
    val builder = newDocumentBuilder() // TODO: use shared instance?
    val document = builder.parse(file)
    val root = document.documentElement

    val name = root.name()
    val wayPoints = root.elementsByName("wpt") { it.wayPoint() }
    val trackPoints = root.elementsByName("trkpt") { it.latLng() }

    if (name != null && wayPoints != null && trackPoints != null && trackPoints.isNotEmpty()) {
        Gpx(name, wayPoints, trackPoints)
    } else null
}

private fun Element.name(): String? {
    val metadata: Node? = getElementsByTagName("metadata").item(0)
    return metadata?.childByName("name")?.textContent
}

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

private fun NodeList.nodes(): Sequence<Node> {
    val indexes = 0 until length
    return indexes.asSequence().map { item(it) }
}

private fun NamedNodeMap.doubleAttr(name: String?) = getNamedItem(name)?.textContent?.toDoubleOrNull()

private fun Node.childByName(name: String): Node? {
    return childNodes.nodes().firstOrNull { it.nodeName == name }
}