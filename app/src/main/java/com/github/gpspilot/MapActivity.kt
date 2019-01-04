package com.github.gpspilot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import d
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.coroutines.CoroutineContext


private const val EXTRA_FNAME = "extra_fname"

class MapActivity : AppCompatActivity(), CoroutineScope {

    companion object {
        fun data(route: File): Bundle = Bundle().apply {
            putString(EXTRA_FNAME, route.absolutePath)
        }
    }


    override val coroutineContext: CoroutineContext by lifecycleCoroutineContext()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val routePath: String = extra(EXTRA_FNAME) ?: run {
            longToast(R.string.error_occurred)
            finish()
            return
        }
        d { "Route path: $routePath" }

        setContentView(R.layout.activity_map)
    }
}


