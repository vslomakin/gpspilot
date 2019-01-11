package com.github.gpspilot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
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
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import d
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
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
                handleTracks()
                handleWayPoints()
                handleLocationSources()
            }
        }
    }

    private fun GoogleMap.handleTracks() = launch {
        vm.routes().consumeEach { route ->
            addPolyline {
                addAll(route)
            }
            moveCamera(CameraUpdateFactory.newLatLngBounds(route.bounds(), 50)) // TODO: fix padding
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

    private fun GoogleMap.handleLocationSources() = launch {
        vm.locationSources().consumeEach { setLocationSource(it) }
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

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class MapVM(
    private val ctx: Application,
    private val documentBuilderFactory: DocumentBuilderFactory,
    private val locationProviderClient: FusedLocationProviderClient
) : CoroutineViewModel() {

    private val uiReq = BroadcastChannel<UiRequest>(1)
    fun uiRequests() = uiReq.openSubscription()

    private val routes = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun routes() = routes.openSubscription()

    private val wayPoints = BroadcastChannel<List<Gpx.WayPoint>>(Channel.CONFLATED)
    fun wayPoints() = wayPoints.openSubscription()

    private val locationSources = BroadcastChannel<LocationSource?>(Channel.CONFLATED)
    fun locationSources() = locationSources.openSubscription()


    private val routeFile = CompletableDeferred<File>()

    private val route: Deferred<Gpx?> = async {
        val file = routeFile.await()
        documentBuilderFactory.parseGps(file)
    }

    init {
        launch {
            route.await()?.let { gpx ->
                routes.send(gpx.track)
                wayPoints.send(gpx.wayPoints)
            } ?: run {
                uiReq.send(UiRequest.Toast(R.string.can_not_parse_route, Length.LONG))
                uiReq.send(UiRequest.FinishActivity)
            }
        }
    }

    fun run(routePath: String) {
        d { "Route path: $routePath" }
        routeFile.complete(File(routePath))
        handleLocations()
    }

    private fun handleLocations() {
        if (ctx.isPermissionGranted(LOCATION_PERMISSION)) {
            val locationSource = locationSourceWithCurrentLocation(locationProviderClient)
            locationSources.offer(locationSource)
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
}


private inline fun GoogleMap.addPolyline(setup: PolylineOptions.() -> Unit) {
    val options = PolylineOptions().apply(setup)
    addPolyline(options)
}

private inline fun GoogleMap.addMarker(setup: MarkerOptions.() -> Unit) {
    val options = MarkerOptions().apply(setup)
    addMarker(options)
}

private fun List<LatLng>.bounds() = LatLngBounds(first(), last())


@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
private fun CoroutineScope.locationSourceWithCurrentLocation(
    locationProviderClient: FusedLocationProviderClient
): LocationSource {
    val listeners = BroadcastChannel<OnLocationChangedListener?>(Channel.CONFLATED)
    val locationSource = locationSource { listeners.offer(it) }
    // TODO: deactivate by stop

    launch(Dispatchers.Main) {
        listeners.openSubscription().consumeSeparately {
            it?.let { listener ->
                val locations = locationProviderClient.locations {
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                    interval = 500
                    fastestInterval = 50
                }
                locations.consumeEach { listener.onLocationChanged(it) }
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