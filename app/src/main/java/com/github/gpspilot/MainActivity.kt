package com.github.gpspilot

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.gpspilot.UiRequest.Toast
import com.github.gpspilot.databinding.ActivityMainBinding
import i
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import org.koin.android.viewmodel.ext.android.viewModel
import w
import java.io.File
import javax.xml.parsers.DocumentBuilder
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong

private const val REQ_OPEN_FILE = 1

@UseExperimental(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class MainActivity : AppCompatActivity(), CoroutineScope {

    private val vm by viewModel<MainActivityVM>()

    override val coroutineContext: CoroutineContext by lifecycleCoroutineContext()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val binding = DataBindingUtil
            .setContentView<ActivityMainBinding>(this, R.layout.activity_main)
            .also { it.vm = vm }

        binding.routesList.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = createListAdapter(vm.routes())
        }

        launch {
            vm.openFileRequests().consumeEach {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*" // TODO: restrict to *.gpx files only
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, REQ_OPEN_FILE)
            }
        }

        handleUiRequests(vm.uiRequests())

        vm.run()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_OPEN_FILE -> onFileOpened(resultCode, data)
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun onFileOpened(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            w { "File hasn't been opened" }
            return
        }
        val uri = data?.data ?: let {
            w { "Data is null!" }
            longToast(R.string.error_occurred)
            return
        }
        vm.onFileOpened(uri)
    }
}


@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class MainActivityVM(
    private val context: Application,
    private val repo: Repository,
    private val documentBuilder: DocumentBuilder
) : CoroutineViewModel() {

    private val uiReq = BroadcastChannel<UiRequest>(1)
    fun uiRequests() = uiReq.openSubscription()

    /**
     * Contains lists of available routes to show.
     */
    private val routes = BroadcastChannel<List<RouteItem>>(Channel.CONFLATED)
    fun routes() = routes.openSubscription()

    /**
     * Contains requests to open new route file.
     */
    private val openFileRequests = BroadcastChannel<Unit>(1)
    fun openFileRequests() = openFileRequests.openSubscription()

    val progressVisibility = ObservableVisibility(View.GONE, true)
    val headerVisibility = ObservableVisibility(View.INVISIBLE, false)
    val noRoutesVisibility = ObservableVisibility(View.GONE, false)


    private val launch = CompletableDeferred<Unit>()
    fun run() = launch.complete()

    init {
        // Show current routes
        launch {
            launch.join()
            repo.getRouteList().consumeEach { routeList ->
                progressVisibility.value = false
                val vms = routeList.map { it.toVM() }
                routes.send(vms)
                noRoutesVisibility.value = routeList.isEmpty()
                headerVisibility.value = routeList.isNotEmpty()
            }
        }
    }

    private val dataFormatter = "dd.MM.yyyy".formatter()

    private fun Route.toVM() = MainActivityVM.RouteItem(
        id = id,
        date = dataFormatter.format(created),
        length = context.getString(R.string.km, length / 1000),
        name = name,
        clickListener = ::onClickRoute
    )

    fun onClickAdd() {
        openFileRequests.offer()
    }

    fun onFileOpened(uri: Uri) {
        launch(Dispatchers.IO) {
            context.saveRoute(uri)?.let { file ->
                i { "New route saved at: $file." }
                val gpx = documentBuilder.parseGps(file)
                if (gpx != null) {
                    val route = UnsavedRoute(gpx, file)
                    repo.addRoute(route)
                } else {
                    uiReq.send(Toast(R.string.can_not_parse_route, Toast.Length.SHORT))
                }
            } ?: run {
                uiReq.send(Toast(R.string.error_occurred_during_file_saving, Toast.Length.LONG))
            }
        }
    }

    private fun onClickRoute(routeItem: RouteItem) {
        val req = UiRequest.StartActivity(
            activity = MapActivity::class,
            data = MapActivity.data(routeItem.id)
        )
        uiReq.offer(req)
    }

    /**
     * View model for route item in the list.
     */
    data class RouteItem(
        override val id: Long,
        val date: String,
        val length: String,
        val name: String,
        val clickListener: (RouteItem) -> Unit
    ) : RecyclerViewItem {
        override val layout: Int = R.layout.item_route
        fun onClick() = clickListener(this)
    }
}


@Suppress("FunctionName")
private fun UnsavedRoute(gpx: Gpx, file: File) = UnsavedRoute(
    id = null,
    name = gpx.name,
    created = gpx.creation,
    length = gpx.track.distance().roundToLong(),
    file = file
)