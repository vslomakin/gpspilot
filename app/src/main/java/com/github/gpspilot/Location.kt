package com.github.gpspilot

import android.annotation.SuppressLint
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import d
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.math.*


@ExperimentalCoroutinesApi
inline fun FusedLocationProviderClient.locations(
        requestScope: LocationRequest.() -> Unit
): ReceiveChannel<Location> {
    val request = LocationRequest().apply(requestScope)
    return locations(request)
}

@ExperimentalCoroutinesApi
@SuppressLint("MissingPermission")
fun FusedLocationProviderClient.locations(
    request: LocationRequest
): ReceiveChannel<Location> {
    val channel = Channel<Location>(Channel.CONFLATED)

    val callback = locationResultCallback { result ->
        result?.locations?.forEach { channel.offer(it) }
    }

    requestLocationUpdates(request, callback, null)

    channel.invokeOnClose {
        removeLocationUpdates(callback)
        d { "Location request removed" }
    }

    return channel
}

/**
 * Just inline helper to remove noise.
 */
private inline fun locationResultCallback(
        crossinline onLocationResult: (LocationResult?) -> Unit
): LocationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult?) {
        onLocationResult(result)
    }
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




fun LatLng.distanceTo(another: LatLng): Double = SphericalUtil.computeDistanceBetween(this, another)

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