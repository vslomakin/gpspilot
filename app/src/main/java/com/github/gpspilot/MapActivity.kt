package com.github.gpspilot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.gpspilot.UiRequest.Toast.Length
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
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


    private val vm by viewModel<MapVM>()

    override val coroutineContext: CoroutineContext by lifecycleCoroutineContext()

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


        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        launch {
            val map = mapFragment.awaitMap()
            vm.routes().consumeEach { route ->
                val opt = PolylineOptions().apply { addAll(route) }
                map.addPolyline(opt)

                map.moveCamera(CameraUpdateFactory.newLatLngBounds(route.bounds(), 50)) // TODO: fix padding
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

    fun run(routePath: String) {
        d { "Route path: $routePath" }
        val route = File(routePath)
        launch {
            documentBuilderFactory.parseGps(route)?.let { gpx ->
                routes.send(gpx.track)
            } ?: run {
                uiReq.send(UiRequest.Toast(R.string.can_not_parse_route, Length.LONG))
                uiReq.send(UiRequest.FinishActivity)
            }
        }
    }
}


private fun List<LatLng>.bounds() = LatLngBounds(first(), last())


