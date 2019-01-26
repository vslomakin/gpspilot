package com.github.gpspilot

import android.annotation.SuppressLint
import android.location.Location
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import d
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.math.*


/**
 * Request locations with parameters expected to be set in [requestScope]
 * and returns channel with [Location]s.
 * When locations became unavailable - `null` will be sent.
 */
@ExperimentalCoroutinesApi
inline fun FusedLocationProviderClient.locations(
        requestScope: LocationRequest.() -> Unit
): ReceiveChannel<Location?> {
    val request = LocationRequest().apply(requestScope)
    return locations(request)
}

/**
 * Request locations and returns channel with [Location]s.
 * When locations became unavailable - `null` will be sent.
 */
@ExperimentalCoroutinesApi
@SuppressLint("MissingPermission")
fun FusedLocationProviderClient.locations(
    request: LocationRequest
): ReceiveChannel<Location?> {
    val channel = Channel<Location?>(Channel.CONFLATED)

    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult?) {
            result?.locations?.forEach {
                channel.offer(it)
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (! availability.isLocationAvailable) {
                channel.offer(null)
            }
        }
    }

    requestLocationUpdates(request, callback, null)

    channel.invokeOnClose {
        removeLocationUpdates(callback)
        d { "Location request removed" }
    }

    return channel
}


/**
 * Returns the first nearest to [point] point position or `null` if there are no elements.
 */
fun Sequence<LatLng>.findNearestPosition(point: LatLng): Int? {
    return minPositionBy { it.distanceTo(point).absoluteValue }
}

/**
 * Returns the first nearest to [point] point position or `null` if there are no elements.
 */
fun List<LatLng>.findNearestPosition(point: Location): Int? {
    return minPositionBy { it.distanceTo(point).absoluteValue }
}

/**
 * Returns the first nearest to [point] point position or `null` if there are no elements.
 */
fun List<LatLng>.findNearestPosition(point: LatLng): Int? {
    return minPositionBy { it.distanceTo(point).absoluteValue }
}


fun Location.toLatLng(): LatLng = LatLng(latitude, longitude)



fun LatLng.distanceTo(another: LatLng): Double = distance(
    fromLatitude = latitude,
    fromLongitude = longitude,
    toLatitude = another.latitude,
    toLongitude = another.longitude
)

fun LatLng.distanceTo(another: Location): Double = distance(
    fromLatitude = latitude,
    fromLongitude = longitude,
    toLatitude = another.latitude,
    toLongitude = another.longitude
)

/**
 * Calculate distance with raw location values,
 * without additional object allocation (such [LatLng], [Location], etc.).
 */
fun distance(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Double {
    val angle = computeAngleBetween(
        fromLatitude = fromLatitude,
        fromLongitude = fromLongitude,
        toLatitude = toLatitude,
        toLongitude = toLongitude
    )
    return angle * 6371009.0
}

/**
 * Calculates path of current track in [range] in meters.
 */
fun List<LatLng>.distance(range: IntRange = indices): Double {
    require(range notExceed indices) { "Wrong range argument." }
    return if (range.count() < 2) {
        0.0
    } else {
        var length = 0.0

        val prev = first()
        var prevLat = Math.toRadians(prev.latitude)
        var prevLng = Math.toRadians(prev.longitude)
        for (i in range.skip(1)) {
            val curr = this[i]
            val lat = Math.toRadians(curr.latitude)
            val lng = Math.toRadians(curr.longitude)
            length += distanceRadians(
                lat1 = prevLat,
                lng1 = prevLng,
                lat2 = lat,
                lng2 = lng
            )
            prevLat = lat
            prevLng = lng
        }

        length * 6371009.0
    }

}


private fun computeAngleBetween(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double
): Double = distanceRadians(
    Math.toRadians(fromLatitude),
    Math.toRadians(fromLongitude),
    Math.toRadians(toLatitude),
    Math.toRadians(toLongitude)
)

private fun distanceRadians(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    return arcHav(havDistance(lat1, lat2, lng1 - lng2))
}

private fun arcHav(x: Double): Double {
    return 2.0 * asin(sqrt(x))
}

private fun havDistance(lat1: Double, lat2: Double, dLng: Double): Double {
    return hav(lat1 - lat2) + hav(dLng) * cos(lat1) * cos(lat2)
}

private fun hav(x: Double): Double {
    val sinHalf = sin(x * 0.5)
    return sinHalf * sinHalf
}


/**
 * Returns bounds of this track or `null` if track is empty.
 */
fun List<LatLng>.bounds(): LatLngBounds? {
    val maxLat = maxBy { it.latitude }?.latitude
    val maxLng = maxBy { it.longitude }?.longitude
    val minLat = minBy { it.latitude }?.latitude
    val minLng = minBy { it.longitude }?.longitude
    return if (maxLat != null && maxLng != null && minLat != null && minLng != null) {
        LatLngBounds(
            LatLng(minLat, minLng),
            LatLng(maxLat, maxLng)
        )
    } else {
        null
    }
}