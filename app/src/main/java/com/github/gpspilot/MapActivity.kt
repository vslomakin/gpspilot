package com.github.gpspilot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Color
import android.graphics.Point
import android.location.Location
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.github.gpspilot.UiRequest.Toast.Length
import com.github.gpspilot.databinding.ActivityMapBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.maps.*
import com.google.android.gms.maps.LocationSource.OnLocationChangedListener
import com.google.android.gms.maps.model.*
import d
import e
import i
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.koin.android.viewmodel.ext.android.viewModel
import w
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis


private const val EXTRA_ROUTE_ID = "extra_route_id"

@UseExperimental(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class MapActivity : AppCompatActivity(), CoroutineScope {

    companion object {
        fun data(routeId: Id): Bundle = Bundle().apply {
            putLong(EXTRA_ROUTE_ID, routeId)
        }
    }


    override val coroutineContext: CoroutineContext by lifecycleCoroutineContext()

    private val vm by viewModel<MapVM>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val routePath: Id = extra(EXTRA_ROUTE_ID) ?: run {
            longToast(R.string.error_occurred)
            finish()
            return
        }

        DataBindingUtil
            .setContentView<ActivityMapBinding>(this, R.layout.activity_map)
            .also { it.vm = vm }

        handleUiRequests(vm.uiRequests())
        vm.run(routePath)

        // TODO: try to setup map with route before initialization
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        launch {
            mapFragment.awaitMap().apply {
                val styled = setMapStyle(MapStyleOptions.loadRawResourceStyle(this@MapActivity, R.raw.map_style))
                if (! styled) e { "Failed to parse map style json!" }

                setLocationSource(locationSource(vm.locations()))
                handleMyLocation()

                handleTracks()
                handleCameraBounds()
                handleWayPoints()

                setOnMapLongClickListener { vm.onMapLongClick(it, projection) }
                setOnMarkerClickListener(::onMarkerClick)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun GoogleMap.handleMyLocation() = launch {
        vm.locationPermissionGranted.join()
        isMyLocationEnabled = true // TODO: hide 'my location' button
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

    private fun GoogleMap.handleWayPoints() {
        // Waypoint projections
        val projectionIcon = BitmapDescriptorFactory.fromResource(R.drawable.ic_wp_projection)
        handleMarkers(
            markerLists = vm.wpProjections(),
            setupOptions = { projection ->
                position(projection)
                anchor(0.5f, 0.5f)
                icon(projectionIcon)
            }
        )

        // Waypoints
        handleMarkers(
            markerLists = vm.wayPoints(),
            setupOptions = { point ->
                position(point.location)
                tint(point.color)
                // TODO: add a name
            },
            setupMarker = { index, marker ->
                marker.wayPointNumber = index
            }
        )
    }

    private fun <T> GoogleMap.handleMarkers(
        markerLists: ReceiveChannel<Collection<T>>,
        setupOptions: MarkerOptions.(marker: T) -> Unit,
        setupMarker: ((index: Int, marker: Marker) -> Unit)? = null
    ) {
        launch {
            var addedMarkers: List<Marker>? = null
            markerLists.consumeEach { markers ->
                // Remove already added markers
                addedMarkers?.forEach { it.remove() }

                // Add new markers
                addedMarkers = markers.mapIndexed { index, marker ->
                    val newMarker = addMarker {
                        setupOptions(marker)
                    }
                    setupMarker?.invoke(index, newMarker)
                    newMarker
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

    private fun onMarkerClick(marker: Marker): Boolean {
        marker.wayPointNumber?.let(vm::onClickMarker)
        return true
    }
}


private const val LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

private const val MIN_DISTANCE = 100

private const val UNKNOWN_SYMBOL = "-"

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class MapVM(
    private val ctx: Application,
    private val documentBuilderFactory: DocumentBuilderFactory,
    private val locationProviderClient: FusedLocationProviderClient,
    private val repo: Repository
) : CoroutineViewModel() {

    data class WayPoint(val location: LatLng, val type: Type) {
        enum class Type { PASSED, TARGET, REMAINING }
    }


    val remainingTime = ObservableString(UNKNOWN_SYMBOL)
    val arrivingTime = ObservableString(UNKNOWN_SYMBOL)

    val averageSpeed = ObservableString(UNKNOWN_SYMBOL)
    val currentSpeed = ObservableString(UNKNOWN_SYMBOL)


    private val uiReq = BroadcastChannel<UiRequest>(1)
    fun uiRequests() = uiReq.openSubscription()


    private val passedTracks = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun passedTracks() = passedTracks.openSubscription()

    private val remainingTracks = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun remainingTracks() = remainingTracks.openSubscription()

    private val unusedTracks = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun unusedTracks() = unusedTracks.openSubscription()


    private val currentTrackPositions = BroadcastChannel<Int>(Channel.CONFLATED)
    private val targetWayPoints = BroadcastChannel<Int?>(Channel.CONFLATED)
    private val targetTrackPosition = BroadcastChannel<Int>(Channel.CONFLATED)

    private val wayPoints = BroadcastChannel<List<WayPoint>>(Channel.CONFLATED)
    fun wayPoints() = wayPoints.openSubscription()

    private val wpProjections = BroadcastChannel<Collection<LatLng>>(Channel.CONFLATED)
    fun wpProjections() = wpProjections.openSubscription()


    private val cameraBounds = BroadcastChannel<LatLngBounds>(Channel.CONFLATED)
    fun cameraBounds() = cameraBounds.openSubscription()


    private var launched = false

    fun run(routeId: Id) {
        if (launched) {
            w { "Map view model already launched." }
            return
        }
        launched = true

        launch {
            val route = repo.openRoute(routeId)?.run {
                documentBuilderFactory.parseGps(file)
            }

            route ?: run {
                uiReq.send(UiRequest.Toast(R.string.can_not_parse_route, Length.LONG))
                uiReq.send(UiRequest.FinishActivity)
                // TODO: send Crashlytics error
                return@launch
            }

            handleLocations()

            if (route.track.isEmpty()) {
                e { "Track is empty." }
                return@launch
            }

            handleTrackPosition(route)
            handleTrack(route)
            handleLongClicks(route)
            handleRemainingTime(route)

            // Track is never empty, so we safely cast to not null
            cameraBounds.send(route.track.bounds()!!)

            if (route.wayPoints.isNotEmpty()) {
                lateinit var projectionPositions: List<Int>
                val projectionTime = measureTimeMillis {
                    projectionPositions = route.getWayPointsProjections()
                }
                i { "Projection calculated for $projectionTime ms." }

                handleProjections(route, projectionPositions)
                handleWayPoints(route, projectionPositions)
            } else {
                w { "WayPoints not found." }
            }
        }
    }

    private fun CoroutineScope.handleTrackPosition(route: Gpx) {
        val track = route.track
        launch {
            var previousPosition: Int? = null
            locations().consumeEach { location ->
                // Track is never empty, so result can't be null, we can safely cast
                val position = track.findNearestPosition(location)!!
                val distance = track[position].distanceTo(location)
                if (distance <= MIN_DISTANCE) {
                    if (position != previousPosition) {
                        d { "Current point: $position, distance: $distance" }
                        currentTrackPositions.send(position)
                        previousPosition = position
                    }
                }
            }
        }
    }

    private fun CoroutineScope.handleTrack(route: Gpx) {
        val track = route.track
        launch {
            consumeLatest(
                channel1 = currentTrackPositions.openSubscription().startWith(coroutineContext, 0),
                channel2 = targetTrackPosition.openSubscription()
            ) { currentPos, targetPos ->
                i { "Positions: current - $currentPos, target - $targetPos (track: ${track.size})." }
                val passedCount = (min(currentPos, targetPos)) + 1
                passedTracks.send(track.take(passedCount))

                val remaining = track.slice(currentPos..targetPos)
                remainingTracks.offer(remaining)

                val unused = track.slice(targetPos..track.lastPosition)
                unusedTracks.offer(unused)
            }
        }
    }

    private fun CoroutineScope.handleProjections(route: Gpx, projectionPositions: List<Int>) {
        launch {
            val projection = route.track.getElements(projectionPositions)

            val targetPositions = targetTrackPosition.openSubscription().distinctUntilChanged(coroutineContext)
            targetPositions.consumeEach { targetPos ->
                val result = if (targetPos !in projectionPositions) {
                    projection + route.track[targetPos]
                } else {
                    projection
                }
                wpProjections.send(result)
            }
        }
    }

    private fun CoroutineScope.handleWayPoints(route: Gpx, projectionPositions: List<Int>) {
        val wayPoints = route.wayPoints
        launch {
            // By default we start from 0 position
            val trackPosition = currentTrackPositions.openSubscription().startWith(coroutineContext, 0)
            // The last waypoint is target by default
            val wpPositions = targetWayPoints.openSubscription().startWith(coroutineContext, wayPoints.lastPosition)

            consumeLatest(trackPosition, wpPositions) { trackPos, wpPos ->
                wpPos?.let { pos ->
                    if (pos in projectionPositions.indices) {
                        targetTrackPosition.send(projectionPositions[pos])
                    } else {
                        e { "Wrong projection point position: $pos (projection count: ${projectionPositions.size})." }
                    }
                }

                val result = wayPoints.mapIndexed { index, wayPoint ->
                    val type = when {
                        index == wpPos -> WayPoint.Type.TARGET
                        projectionPositions[index] <= trackPos -> WayPoint.Type.PASSED
                        else -> WayPoint.Type.REMAINING
                    }
                    WayPoint(wayPoint.location, type)
                }
                this@MapVM.wayPoints.send(result)
            }
        }
    }

    private fun CoroutineScope.handleRemainingTime(route: Gpx) {
        val track = route.track
        val timeTemplate = ctx.getString(R.string.time_template)
        val arriveFormatter = "HH:mm".formatter()
        launch {
            consumeLatest(
                currentTrackPositions.openSubscription(),
                targetTrackPosition.openSubscription(),
                locations().map { it.speed }.startWith(coroutineContext, 0f)
            ) { currPos, targetPos, speed ->
                val remainingSec: Long?
                when {
                    speed == 0f -> remainingSec = null
                    currPos < targetPos -> {
                        val distance = track.distance(currPos..targetPos)
                        remainingSec = (distance / speed).roundToLong()
                    }
                    else -> remainingSec = 0 // if currPos >= targetPos
                }

                remainingTime.value = remainingSec?.let { sec ->
                    val h = sec.secToFullHour()
                    val min = (sec - h.hourToSec()).secToFullMin()
                    timeTemplate.format(h, min)
                } ?: UNKNOWN_SYMBOL

                arrivingTime.value = remainingSec?.takeIf { it > 0L }?.let { sec ->
                    val arriveAtMs = sec.secToMs() + System.currentTimeMillis()
                    arriveFormatter.format(arriveAtMs)
                } ?: UNKNOWN_SYMBOL
            }
        }
    }


    private val _locationPermissionGranted = CompletableDeferred<Unit>()
    val locationPermissionGranted: Job = _locationPermissionGranted

    private fun handleLocations() {
        if (ctx.isPermissionGranted(LOCATION_PERMISSION)) {
            _locationPermissionGranted.complete()
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

    init {
        // Handle device location
        launch(Dispatchers.Main) {
            locationPermissionGranted.join()
            handleSpeed()
            d { "Location permission granted, requesting location update..." }
            val channel = locationProviderClient.locations {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 500
                fastestInterval = 50
            }
            channel.consumeEach { _locations.send(it) }
        }
    }

    private fun CoroutineScope.handleSpeed() {
        val kmPerHourTemplate = "%.1f " + ctx.getString(R.string.kmPerHour)
        launch {
            val speeds = locations()
                .map(coroutineContext) { it.speed.meterPerSecToKmPerHour() }
                .broadcast(coroutineContext, Channel.CONFLATED)

            // Current speed
            launch {
                speeds.openSubscription().distinctUntilChanged(coroutineContext).consumeEach { speed ->
                    currentSpeed.value = kmPerHourTemplate.format(speed)
                }
            }

            // Average speed
            launch {
                // Start with Unit for initial setup
                val resets = speedClicks.openSubscription().startWith(coroutineContext, Unit)
                resets.consumeSeparately(true) {
                    i { "Starting new average speed calculation." }
                    speeds.openSubscription().average(coroutineContext).consumeEach { speed ->
                        averageSpeed.value = kmPerHourTemplate.format(speed)
                    }
                }
            }
        }
    }

    private val speedClicks = BroadcastChannel<Unit>(1)

    fun onSpeedClick(): Boolean {
        speedClicks.offer()
        return true
    }


    private val longClicks = BroadcastChannel<Pair<LatLng, Projection>>(Channel.CONFLATED) // TODO: may be capacity should be 1?

    fun onMapLongClick(clickLocation: LatLng, projection: Projection) {
        longClicks.offer(clickLocation to projection)
    }

    private val clickDistance = ctx.resources.getDimensionPixelSize(R.dimen.track_click_boundaries)

    private fun CoroutineScope.handleLongClicks(route: Gpx) {
        val track = route.track
        launch {
            longClicks.consumeEach { (clickLocation, projection) ->
                // Track is never empty, so result can't be null, we can safely cast
                val nearestPos = track.findNearestPosition(clickLocation)!!
                val nearest = track[nearestPos]

                lateinit var clickPoint: Point
                lateinit var nearestPoint: Point
                withContext(Dispatchers.Main) {
                    clickPoint = projection.toScreenLocation(clickLocation)
                    nearestPoint = projection.toScreenLocation(nearest)
                }
                val distancePx = clickPoint distanceTo nearestPoint
                i { "Clicked on map: $clickPoint. Distance: ${distancePx}px (allowed ${clickDistance}px)." }
                if (distancePx <= clickDistance) {
                    targetWayPoints.send(null)
                    targetTrackPosition.send(nearestPos)
                }
            }
        }
    }



    fun onClickMarker(number: Int) {
        targetWayPoints.offer(number)
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

private fun MarkerOptions.tint(color: Float) {
    icon(BitmapDescriptorFactory.defaultMarker(color))
}

private inline var Marker.wayPointNumber: Int?
    set(value) {
        tag = value
    }
    get() = tag as? Int


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
private suspend fun Gpx.getWayPointsProjections(): List<Int> = withContext(Dispatchers.Default) {
    var startPosition = 0 // Position from which traverse track list
    val trackSeq = track.asSequence()
    wayPoints.mapNotNull { wp ->
        // Firstly we don't need to traverse points before projection of previous waypoint
        val seq = trackSeq.drop(startPosition)
        // Then find nearest point from remaining sequence
        val nearest = seq.findNearestPosition(wp.location)?.let { it + startPosition }
        nearest?.let {
            startPosition = it // Assign found position to start traverse from here for next waypoint
            it
        }
    }
}


private suspend fun Deferred<Gpx?>.awaitNotEmptyTrack(): Gpx {
    return await()?.takeIf { it.track.isNotEmpty() } ?: infiniteDeferred.await()
}

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
private val MapVM.WayPoint.color: Float get() = when (type) {
    MapVM.WayPoint.Type.PASSED -> BitmapDescriptorFactory.HUE_GREEN
    MapVM.WayPoint.Type.TARGET -> BitmapDescriptorFactory.HUE_YELLOW
    MapVM.WayPoint.Type.REMAINING -> BitmapDescriptorFactory.HUE_ORANGE // TODO: should be white
}