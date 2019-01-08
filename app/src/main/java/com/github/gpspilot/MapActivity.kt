package com.github.gpspilot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.gpspilot.UiRequest.Toast.Length
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import d
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.koin.android.viewmodel.ext.android.viewModel
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.CoroutineContext


private const val EXTRA_FNAME = "extra_fname"

@UseExperimental(ObsoleteCoroutinesApi::class)
class MapActivity : AppCompatActivity(), CoroutineScope {

    companion object {
        fun data(route: File): Bundle = Bundle().apply {
            putString(EXTRA_FNAME, route.absolutePath)
        }
    }


    override val coroutineContext: CoroutineContext by lifecycleCoroutineContext()

    private val vm by viewModel<MapVM>()

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
                handleTracks()
                handleWayPoints()
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
}


@UseExperimental(ExperimentalCoroutinesApi::class)
class MapVM(private val documentBuilderFactory: DocumentBuilderFactory) : CoroutineViewModel() {

    private val uiReq = BroadcastChannel<UiRequest>(1)
    fun uiRequests() = uiReq.openSubscription()

    private val routes = BroadcastChannel<List<LatLng>>(Channel.CONFLATED)
    fun routes() = routes.openSubscription()

    private val wayPoints = BroadcastChannel<List<Gpx.WayPoint>>(Channel.CONFLATED)
    fun wayPoints() = wayPoints.openSubscription()

    fun run(routePath: String) {
        d { "Route path: $routePath" }
        val route = File(routePath)
        launch {
            documentBuilderFactory.parseGps(route)?.let { gpx ->
                routes.send(gpx.track)
                wayPoints.send(gpx.wayPoints)
            } ?: run {
                uiReq.send(UiRequest.Toast(R.string.can_not_parse_route, Length.LONG))
                uiReq.send(UiRequest.FinishActivity)
            }
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