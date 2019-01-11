package com.github.gpspilot

import android.annotation.SuppressLint
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import d
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel


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