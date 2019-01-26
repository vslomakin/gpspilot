package com.github.gpspilot

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.location.Location
import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.databinding.DataBindingUtil
import com.github.gpspilot.UiRequest.Toast.Length
import com.github.gpspilot.databinding.ActivityMapBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.Projection
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.maps.android.ui.IconGenerator
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

    private val vm by viewModel<MapActivityVM>()

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
        vm.run(routePath).also {
            i { "View model launched: $it." }
        }

        // TODO: try to setup map with route before initialization
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        launch {
            mapFragment.awaitMap().apply {
                val styled = setMapStyle(MapStyleOptions.loadRawResourceStyle(this@MapActivity, R.raw.map_style))
                if (! styled) e { "Failed to parse map style json!" }

                showMyLocation()

                handleTracks()
                handleCameraBounds()
                handleWayPoints()

                setOnMapLongClickListener { vm.onMapLongClick(it, projection) }
                setOnMarkerClickListener(::onMarkerClick)
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
        val padding = resources.getDimensionPixelOffset(R.dimen.map_bounds_padding)
        vm.cameraBounds().consumeEach { bounds ->
            moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        }
    }

    private fun GoogleMap.handleWayPoints() {
        // Waypoint projections
        val projectionIcon = BitmapDescriptorFactory.fromResource(R.drawable.ic_wp_projection) // TODO: use proper drawable
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
            },
            setupMarker = { index, marker ->
                marker.wayPointNumber = index
            }
        )

        // Waypoints names
        val bottomPadding = resources.getDimensionPixelOffset(R.dimen.marker_content_bottom_padding)
        handleMarkers(
            markerLists = vm.wayPoints(),
            setupOptions = { point ->
                position(point.location)
                iconDescriptor = makeIcon(point.name) {
                    setContentPadding(0, 0, 0, bottomPadding)
                    setTextAppearance(R.style.MarkerTitle)
                    setBackground(null)
                }
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

    private fun GoogleMap.showMyLocation() {
        val greenArrow = descriptor(R.drawable.arrow_green)
        val redArrow = descriptor(R.drawable.arrow_red)
        if (greenArrow == null || redArrow == null) {
            longToast(R.string.error_occurred)
            finish()
            e { "My location drawable hasn't been loaded!" }
            return
        }


        launch {
            var marker: Marker? = null
            vm.locationIsAccurate().distinctUntilChanged(coroutineContext).consumeSeparately { isAccurate ->
                i { "Accuracy changed: $isAccurate." }
                // Accuracy changed, we need change location icon. For beginning remove previous marker:
                marker?.remove()
                marker = null

                val icon = if (isAccurate) greenArrow else redArrow

                // Now process location with new marker
                vm.locations().consumeEach { location ->
                    marker?.apply {
                        // If marker already added - just change it's params
                        position = location.toLatLng()
                        rotation = location.bearing
                    } ?: run {
                        // If its first location - add new marker with new icon
                        marker = addMarker {
                            position(location.toLatLng())
                            rotation(location.bearing)
                            flat(true)
                            anchor(0.5f, 1f)
                            icon(icon)
                        }
                    }
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
class MapActivityVM(
    private val ctx: Application,
    private val documentBuilderFactory: DocumentBuilderFactory,
    private val locationProviderClient: FusedLocationProviderClient,
    private val repo: Repository
) : CoroutineViewModel() {

    data class WayPoint(val name: String, val location: LatLng, val type: Type) {
        enum class Type { PASSED, TARGET, REMAINING }
    }


    val remainingPanelVisibility = ObservableVisibility(View.GONE, false)
    val remainingTime = ObservableString(UNKNOWN_SYMBOL)
    val arrivingTime = ObservableString(UNKNOWN_SYMBOL)
    val remainingDistance = ObservableString(UNKNOWN_SYMBOL)

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
    private val nearTrack = BroadcastChannel<Boolean>(Channel.CONFLATED)
    private val targetWayPoints = BroadcastChannel<Int?>(Channel.CONFLATED)
    private val targetTrackPosition = BroadcastChannel<Int>(Channel.CONFLATED)

    private val wayPoints = BroadcastChannel<List<WayPoint>>(Channel.CONFLATED)
    fun wayPoints() = wayPoints.openSubscription()

    private val wpProjections = BroadcastChannel<Collection<LatLng>>(Channel.CONFLATED)
    fun wpProjections() = wpProjections.openSubscription()


    private val cameraBounds = BroadcastChannel<LatLngBounds>(Channel.CONFLATED)
    fun cameraBounds() = cameraBounds.openSubscription()


    private val run = CompletableDeferred<Id>()
    fun run(routeId: Id): Boolean = run.complete(routeId)


    init {
        val routeAsync: Deferred<Gpx?> = async {
            val id = run.await()
            repo.openRoute(id)?.run {
                documentBuilderFactory.parseGps(file)
            }
        }

        val projectionAsync: Deferred<List<Int>> = async {
            val route = routeAsync.awaitNotEmptyTrack()
            if (route.wayPoints.isNotEmpty()) {
                lateinit var projectionPositions: List<Int>
                val projectionTime = measureTimeMillis {
                    projectionPositions = route.getWayPointsProjections()
                }
                i { "Projection calculated for $projectionTime ms." }
                projectionPositions
            } else {
                w { "WayPoints not found." }
                infiniteDeferred.await()
            }
        }


        // Handle case when track not loaded properly
        launch {
            val route = routeAsync.await()
            if (route == null || route.track.isEmpty()) {
                uiReq.send(UiRequest.Toast(R.string.can_not_parse_route, Length.LONG))
                uiReq.send(UiRequest.FinishActivity)
                // TODO: send Crashlytics error
            }
        }

        // Entire track showing
        launch {
            val track = routeAsync.awaitNotEmptyTrack().track
            track.bounds()?.let { bounds ->
                showEntireTrack.openSubscription().startWith(coroutineContext, Unit).consumeEach {
                    cameraBounds.send(bounds)
                }
            } ?: run {
                e { "Bounds are not returned." }
            }
        }

        // Track position
        launch {
            val track = routeAsync.awaitNotEmptyTrack().track
            launch {
                var previousPosition: Int? = null
                locations().consumeEach { location ->
                    // Track is never empty, so result can't be null, we can safely cast
                    val position = track.findNearestPosition(location)!!
                    val distance = track[position].distanceTo(location)
                    val nearTrack = distance <= MIN_DISTANCE
                    if (nearTrack) {
                        if (position != previousPosition) {
                            d { "Current point: $position, distance: $distance" }
                            currentTrackPositions.send(position)
                            previousPosition = position
                        }
                    }
                    this@MapActivityVM.nearTrack.send(nearTrack)
                }
            }
        }

        // Track polylines
        launch {
            val track = routeAsync.awaitNotEmptyTrack().track
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



        // Waypoint projections
        launch {
            val route = routeAsync.awaitNotEmptyTrack()
            val projectionPositions = projectionAsync.await()
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

        // Waypoints
        launch {
            val wayPoints = routeAsync.awaitNotEmptyTrack().wayPoints
            val projectionPositions = projectionAsync.await()

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
                    WayPoint(
                        name = wayPoint.name ?: index.inc().toString(),
                        location = wayPoint.location,
                        type = type
                    )
                }
                this@MapActivityVM.wayPoints.send(result)
            }
        }

        // Handle long clicks
        val clickDistance = ctx.resources.getDimensionPixelSize(R.dimen.track_click_boundaries)
        launch {
            val track = routeAsync.awaitNotEmptyTrack().track
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



        // Remaining panel visibility
        launch {
            nearTrack.openSubscription().consumeEach {
                remainingPanelVisibility.value = it
            }
        }

        // Remaining panel data
        launch {
            val track = routeAsync.awaitNotEmptyTrack().track
            lateinit var timeTemplate: String
            lateinit var distanceTemplate: String
            withContext(Dispatchers.Main) {
                timeTemplate = ctx.getString(R.string.time_template)
                distanceTemplate = ctx.getString(R.string.km_template)
            }
            val arriveFormatter = "HH:mm".formatter()
            whenNearTrack {
                consumeLatest(
                    currentTrackPositions.openSubscription(),
                    targetTrackPosition.openSubscription(),
                    locations().map { it.speed }.startWith(coroutineContext, 0f)
                ) { currPos, targetPos, speed ->
                    val remainingMeters = if (currPos < targetPos) {
                        track.distance(currPos..targetPos)
                    } else {
                        0.0
                    }

                    val remainingKm = remainingMeters.metersToKm()
                    remainingDistance.value = distanceTemplate.format(remainingKm)

                    val remainingSec = speed.takeIf { it > 0f }?.let {
                        (remainingMeters / it).roundToLong()
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
    }

    private suspend inline fun whenNearTrack(crossinline block: suspend () -> Unit) {
        val nearTrackChannel = nearTrack.openSubscription().distinctUntilChanged(coroutineContext)
        nearTrackChannel.consumeSeparately { if (it) block() }
    }



    private val permissionResults = BroadcastChannel<List<PermissionResult>>(1)

    fun onPermissionResult(results: List<PermissionResult>) = permissionResults.offer(results)

    private val _locations = BroadcastChannel<Location>(Channel.CONFLATED)
    fun locations() = _locations.openSubscription()

    private val locationIsAccurate = BroadcastChannel<Boolean>(Channel.CONFLATED)
    fun locationIsAccurate() = locationIsAccurate.openSubscription()

    init {
        // Handle location permission
        val permissionGranted = launch(Dispatchers.Main) {
            run.join()

            if (! ctx.isPermissionGranted(LOCATION_PERMISSION)) {
                uiReq.send(UiRequest.Permission(LOCATION_PERMISSION))
                permissionResults.openSubscription().first { it.isLocationPermissionGranted == true }
            }
            i { "Location permission granted" }
        }

        // Speed
        val kmPerHourTemplate = "%.1f " + ctx.getString(R.string.kmPerHour)
        launch {
            permissionGranted.join()
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

        // Broadcast locations
        launch(Dispatchers.Main) {
            permissionGranted.join()
            // TODO: deactivate by stop
            val channel = locationProviderClient.locations {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 500L
                fastestInterval = 50L
            }
            channel.consumeEach { location ->
                location?.let { _locations.send(it) }
                locationIsAccurate.send(location.isAccurate)
            }
        }

        // Location permission isn't granted
        launch {
            permissionResults.consumeEach { results ->
                if (results.isLocationPermissionGranted == false) {
                    uiReq.send(UiRequest.Toast(R.string.location_permission_denied, Length.SHORT))
                }
            }
        }
    }

    private val speedClicks = BroadcastChannel<Unit>(1)

    fun onSpeedClick(): Boolean {
        speedClicks.offer()
        return true
    }


    private val longClicks = BroadcastChannel<Pair<LatLng, Projection>>(1)

    fun onMapLongClick(clickLocation: LatLng, projection: Projection) {
        longClicks.offer(clickLocation to projection)
    }

    fun onClickMarker(number: Int) {
        targetWayPoints.offer(number)
    }


    private val showEntireTrack = BroadcastChannel<Unit>(Channel.CONFLATED)

    fun onClickShowEntireTrack() {
        showEntireTrack.offer()
    }
}


private fun Context.descriptor(@DrawableRes resId: Int): BitmapDescriptor? {
    val bitmap = compatDrawable(resId)?.toBitmap()
    return bitmap?.let(BitmapDescriptorFactory::fromBitmap)
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

private inline var MarkerOptions.iconDescriptor: BitmapDescriptor?
    set(value) { icon(value) }
    get() = icon

private inline fun Context.makeMarkerBitmap(text: String, setup: IconGenerator.() -> Unit): Bitmap {
    return IconGenerator(this).run {
        setup()
        makeIcon(text)
    }
}

private inline fun Context.makeIcon(text: String, setup: IconGenerator.() -> Unit): BitmapDescriptor {
    val bitmap = makeMarkerBitmap(text, setup)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}



private inline val Location?.isAccurate: Boolean
    get() = this?.run { accuracy < 10f } == true

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
private val MapActivityVM.WayPoint.color: Float get() = when (type) {
    MapActivityVM.WayPoint.Type.PASSED -> BitmapDescriptorFactory.HUE_GREEN
    MapActivityVM.WayPoint.Type.TARGET -> BitmapDescriptorFactory.HUE_YELLOW
    MapActivityVM.WayPoint.Type.REMAINING -> BitmapDescriptorFactory.HUE_ORANGE // TODO: should be white
}


private inline val List<PermissionResult>.isLocationPermissionGranted: Boolean? get() {
    val locationResult = firstOrNull { it.permission == LOCATION_PERMISSION }
    return locationResult?.granted
}