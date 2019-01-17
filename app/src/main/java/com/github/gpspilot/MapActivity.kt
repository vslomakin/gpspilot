package com.github.gpspilot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.gpspilot.UiRequest.Toast.Length
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.LocationSource.OnLocationChangedListener
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import d
import i
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import org.koin.android.viewmodel.ext.android.viewModel
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.CoroutineContext


private const val EXTRA_FNAME = "extra_fname"

@UseExperimental(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class MapActivity : AppCompatActivity(), CoroutineScope {

    companion object {
        fun data(route: File): Bundle = Bundle().apply {
            putString(EXTRA_FNAME, route.absolutePath)
        }
    }


    override val coroutineContext: CoroutineContext by lifecycleCoroutineContext()

    private val vm by viewModel<MapVM>()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val routePath: String = extra(EXTRA_FNAME) ?: run {
            longToast(R.string.error_occurred)
            finish()
            return
        }
        setContentView(R.layout.activity_map)

        handleUiRequests(vm.uiRequests())
        vm.run(routePath)

        // TODO: try to setup map with route before initialization
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        launch {
            mapFragment.awaitMap().apply {
                isMyLocationEnabled = true // TODO: hide 'my location' button
                setLocationSource(locationSource(vm.locations()))
                handleTracks()
                handleCameraBounds()
                handleWayPoints()
                handleWPProjections()

                setOnMapLongClickListener(vm::onMapLongClick)
            }
        }
    }

    private fun GoogleMap.handleTracks() {
        handlePolylines(vm.passedTracks(), Color.GREEN)
        handlePolylines(vm.remainingTracks(), Color.YELLOW)
        handlePolylines(vm.unusedTracks(), Color.WHITE)
    }

    private fun GoogleMap.handlePolylines(polylines: ReceiveChannel<List<LatLng>>, color: Int) {
        launch {
            var addedPolyline: Polyline? = null
            polylines.consumeEach { track ->
                addedPolyline?.remove()
                addedPolyline = addPolyline {
                    addAll(track)
                    color(color)
                }
            }
        }
    }

    private fun GoogleMap.handleCameraBounds() = launch {
        vm.cameraBounds().consumeEach { bounds ->
            moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50)) // TODO: fix padding
        }
    }

    private fun GoogleMap.handleWayPoints() = launch {
        vm.wayPoints().consumeEach { wayPoints ->
            wayPoints.forEach { point ->
                addMarker {
                    position(point.location)
                    // TODO: add a name
                }
            }
        }
    }

    private fun  GoogleMap.handleWPProjections() = launch {
        val icDescriptor = BitmapDescriptorFactory.fromResource(R.drawable.ic_wp_projection)
        vm.wpProjections().consumeEach { projections ->
            projections.forEach { projection ->
                addMarker {
                    position(projection)
                    anchor(0.5f, 0.5f)
                    icon(icDescriptor)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        vm.onPermissionResult(permissionResults(permissions, grantResults))
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}


private const val LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

private const val MIN_DISTANCE = 100

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class MapVM(
    private val ctx: Application,
    private val documentBuilderFactory: DocumentBuilderFactory,
    private val locationProviderClient: FusedLocationProviderClient
) : CoroutineViewModel() {

    private val uiReq = BroadcastChannel<UiRequest>(1)
    fun uiRequests() = uiReq.openSubscription()

    private val passedTracks = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun passedTracks() = passedTracks.openSubscription()

    private val remainingTracks = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun remainingTracks() = remainingTracks.openSubscription()

    private val unusedTracks = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun unusedTracks() = unusedTracks.openSubscription()

    private val wayPoints = BroadcastChannel<List<Gpx.WayPoint>>(Channel.CONFLATED)
    fun wayPoints() = wayPoints.openSubscription()

    private val wpProjections = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun wpProjections() = wpProjections.openSubscription()

    private val cameraBounds = BroadcastChannel<LatLngBounds>(Channel.CONFLATED)
    fun cameraBounds() = cameraBounds.openSubscription()

    private val routeFile = CompletableDeferred<File>()

    private val route: Deferred<Gpx?> = async {
        val file = routeFile.await()
        documentBuilderFactory.parseGps(file)
    }

    init {
        launch {
            route.await()?.let { gpx ->
                val (projection, projectionTime) = measureTimeMillis {
                    gpx.getWayPointsProjections()
                }
                i { "Projection calculated for $projectionTime ms." }
                remainingTracks.send(gpx.track)
                wayPoints.send(gpx.wayPoints)
                wpProjections.send(projection)
                cameraBounds.send(gpx.track.bounds())
            } ?: run {
                uiReq.send(UiRequest.Toast(R.string.can_not_parse_route, Length.LONG))
                uiReq.send(UiRequest.FinishActivity)
            }
        }
    }

    fun run(routePath: String) {
        // TODO: do not run by second invocation
        d { "Route path: $routePath" }
        routeFile.complete(File(routePath))
        handleLocations()
    }


    private val locationPermissionGranted = CompletableDeferred<Unit>()

    private fun handleLocations() {
        if (ctx.isPermissionGranted(LOCATION_PERMISSION)) {
            locationPermissionGranted.complete()
        } else {
            uiReq.offer(UiRequest.Permission(LOCATION_PERMISSION))
        }
    }

    fun onPermissionResult(results: List<PermissionResult>) {
        val locationGranted = results.any { it.permission == LOCATION_PERMISSION && it.granted }
        if (locationGranted) {
            handleLocations()
        } else {
            uiReq.offer(UiRequest.Toast(R.string.location_permission_denied, Length.SHORT))
        }
    }

    private val _locations = BroadcastChannel<Location>(Channel.CONFLATED)
    fun locations() = _locations.openSubscription()

    private val currentTrackPositions = BroadcastChannel<Int>(Channel.CONFLATED)

    init {
        // Handle device location
        launch(Dispatchers.Main) {
            locationPermissionGranted.join()
            d { "Location permission granted, requesting location update..." }
            val channel = locationProviderClient.locations {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 500
                fastestInterval = 50
            }
            channel.consumeEach { _locations.offer(it) }
        }

        // Handle track progress
        launch {
            val track = route.awaitNotEmptyTrack().track
            var previousPosition: Int? = null
            locations().consumeEach { location ->
                // Track is never empty, so result can't be null, we can safely cast
                val position = track.findNearestPosition(location)!!
                if (position != previousPosition) {
                    val distance = track[position].distanceTo(location)
                    i { "Current point: $position, distance: $distance" }
                    if (distance <= MIN_DISTANCE) {
                        currentTrackPositions.send(position)
                    }
                    previousPosition = position
                }
            }
        }

        // Handle passed track
        launch {
            val track = route.awaitNotEmptyTrack().track
            currentTrackPositions.consumeEach { position ->
                val passed = track.take(position + 1)
                passedTracks.send(passed)
            }
        }

        // Handle remaining track
        launch {
            val track = route.awaitNotEmptyTrack().track
            consumeLatest(
                channel1 = currentTrackPositions.openSubscription(),
                // Start with null for initial setup
                channel2 = longClicks.openSubscription().startWith(coroutineContext, null)
            ) { currentTrackPosition, clicked ->
                val targetPosition = track.indexOrNull(clicked) ?: track.lastPosition
                d { "Target position: $targetPosition (of ${track.size})" }

                val remaining = track.slice(currentTrackPosition..targetPosition)
                remainingTracks.offer(remaining)

                val unused = track.slice(targetPosition..track.lastPosition)
                unusedTracks.offer(unused)
            }
        }
    }


    private val longClicks = BroadcastChannel<LatLng>(Channel.CONFLATED)

    fun onMapLongClick(point: LatLng) {
        longClicks.offer(point)
    }
}



private inline fun GoogleMap.addPolyline(setup: PolylineOptions.() -> Unit): Polyline {
    val options = PolylineOptions().apply(setup)
    return addPolyline(options)
}

private inline fun GoogleMap.addMarker(setup: MarkerOptions.() -> Unit): Marker {
    val options = MarkerOptions().apply(setup)
    return addMarker(options)
}



private fun List<LatLng>.bounds() = LatLngBounds(first(), last())


@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
private fun CoroutineScope.locationSource(locations: ReceiveChannel<Location>): LocationSource {
    val listeners = BroadcastChannel<OnLocationChangedListener?>(Channel.CONFLATED)
    val locationSource = locationSource { listeners.offer(it) }
    // TODO: deactivate by stop

    launch(Dispatchers.Main) {
        listeners.openSubscription().consumeSeparately {
            it?.let { listener ->
                locations.consumeEach { location ->
                    listener.onLocationChanged(location)
                }
            }
        }
    }

    return locationSource
}


/**
 * Inline helper to reduce noise.
 */
private inline fun locationSource(
    crossinline onListenerChanged: (OnLocationChangedListener?) -> Unit
): LocationSource = object : LocationSource {
    override fun activate(listener: OnLocationChangedListener?) {
        onListenerChanged(listener)
    }

    override fun deactivate() {
        onListenerChanged(null)
    }
}


/**
 * Calculates projections of [Gpx.wayPoints] to [Gpx.track].
 */
private suspend fun Gpx.getWayPointsProjections(): List<LatLng> = withContext(Dispatchers.Default) {
    var startPosition = 0 // Position from which traverse track list
    val trackSeq = track.asSequence()
    wayPoints.mapNotNull { wp ->
        // Firstly we don't need to traverse points before projection of previous waypoint
        val seq = trackSeq.drop(startPosition)
        // Then find nearest point from remaining sequence
        val nearest = seq.findNearestPosition(wp.location)?.let { it + startPosition }
        nearest?.let {
            startPosition = it // Assign found position to start traverse from here for next waypoint
            track[it]
        }
    }
}


private suspend fun Deferred<Gpx?>.awaitNotEmptyTrack(): Gpx {
    return await()?.takeIf { it.track.isNotEmpty() } ?: infiniteDeferred.await()
}