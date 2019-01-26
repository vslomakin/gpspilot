package com.github.gpspilot

import android.app.Activity
import android.app.Application
import android.content.Context
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
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
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
    private val documentBuilderFactory: DocumentBuilderFactory
) : CoroutineViewModel() {

    private val uiReq = BroadcastChannel<UiRequest>(1)
    fun uiRequests() = uiReq.openSubscription()

    private val routes = BroadcastChannel<List<RouteItem>>(Channel.CONFLATED)
    fun routes() = routes.openSubscription()

    private val openFileRequests = BroadcastChannel<Unit>(1)
    fun openFileRequests() = openFileRequests.openSubscription()

    val progressVisibility = ObservableVisibility(View.GONE, true)
    val headerVisibility = ObservableVisibility(View.INVISIBLE, false)
    val noRoutesVisibility = ObservableVisibility(View.GONE, false)


    private val launch = CompletableDeferred<Unit>()
    fun run() = launch.complete()

    init {
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

    private val dataFormatter = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

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

    private fun createNewFile(time: Date): File? {
        val dir = context.routeFolder
        if (dir?.mkdirs() == true) i { "Route dir created." }
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
        val fname = formatter.format(time) + ".gpx"
        return dir?.append(fname)
    }

    fun onFileOpened(uri: Uri) {
        val time = Date()
        val file = createNewFile(time) ?: run {
            uiReq.offer(Toast(R.string.external_storage_unavailable, Toast.Length.LONG))
            return
        }
        // TODO: test case when file is broken

        // TODO: show some kind of progress while file saving
        launch(Dispatchers.IO) {
            val copied = context.copyUriToFile(uri, file)
            if (copied) {
                i { "New route saved at: $file." }
                val gpx = documentBuilderFactory.parseGps(file)
                if (gpx != null) {
                    val route = UnsavedRoute(
                        id = null,
                        name = gpx.name,
                        created = time,
                        length = gpx.track.distance().roundToLong(),
                        file = file
                    )
                    repo.addRoute(route)
                } else {
                    uiReq.send(Toast(R.string.can_not_parse_route, Toast.Length.SHORT))
                }
            } else {
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

private inline val Context.routeFolder: File?
    get() = getExternalFilesDir(null)?.append("routes")

private fun Context.copyUriToFile(uri: Uri, file: File): Boolean {
    return try {
        contentResolver.openInputStream(uri).use { input: InputStream ->
            file.apply {
                createNewFile()
                outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        true
    } catch (e: Exception) {
        e logE { "Can't copy file: $file." }
        false
    }
}