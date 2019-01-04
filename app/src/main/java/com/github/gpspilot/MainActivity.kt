package com.github.gpspilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import d
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import org.koin.android.viewmodel.ext.android.viewModel
import w
import java.io.File
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

private const val REQ_OPEN_FILE = 1

@UseExperimental(ObsoleteCoroutinesApi::class)
class MainActivity : AppCompatActivity(), CoroutineScope {

    private val vm by viewModel<MainActivityVM>()

    override val coroutineContext: CoroutineContext by lifecycleCoroutineContext()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        launch {
            vm.onViewReady().consumeEach {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*" // TODO: restrict to *.gps files only
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, REQ_OPEN_FILE)
            }
        }

        handleUiRequests(vm.uiRequests())
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


@UseExperimental(ExperimentalCoroutinesApi::class)
class MainActivityVM(
    private val context: Context
) : CoroutineViewModel() {

    private val uiReq = BroadcastChannel<UiRequest>(1)
    fun uiRequests() = uiReq.openSubscription()

    private var routeFile: Deferred<File>?
            = context.defRouteFile?.takeIf { it.exists() }?.let(::CompletableDeferred)


    // TODO: make this logic more idiomatic
    fun onViewReady(): ReceiveChannel<Unit> = produce {
        routeFile?.let { file ->
            val req = UiRequest.StartActivity(
                activity = MapActivity::class,
                data = MapActivity.data(file.await())
            )
            uiReq.send(req)
        } ?: run {
            send(Unit)
        }
    }

    fun onFileOpened(uri: Uri) {
        if (context.routeFolder?.mkdirs() == true) {
            d { "Route dir created." }
        }

        val file = context.defRouteFile
        if (file == null) {
            w { "External storage unavailable" }
            // TODO: show toast
            return
        }

        routeFile = async {
            try {
                context.contentResolver.openInputStream(uri).use { input: InputStream ->
                    file.apply {
                        createNewFile()
                        outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                d { "File copied!" }
            } catch (e: Exception) {
                e logE { "Error occurred during file copying" }
                // TODO: show error toast
            }
            file!!
        }
    }
}

private inline val Context.routeFolder: File?
    get() = getExternalFilesDir(null)?.append("routes")

// TODO: implement choose UI
private inline val Context.defRouteFile: File?
    get() = routeFolder?.append("route.gpx")

