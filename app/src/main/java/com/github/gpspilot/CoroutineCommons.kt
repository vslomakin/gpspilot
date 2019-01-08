package com.github.gpspilot

import androidx.lifecycle.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlin.coroutines.CoroutineContext
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


open class CoroutineViewModel(
    private val defCoroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel(), CoroutineScope {
    private val job = Job()

    override val coroutineContext: CoroutineContext
        get() = job + defCoroutineDispatcher

    override fun onCleared() {
        job.cancel()
    }
}


class LifecycleCoroutineContext(
    private val lifecycle: Lifecycle,
    private val defDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ReadOnlyProperty<Any, CoroutineContext>, LifecycleObserver {
    private val job = Job()

    override fun getValue(thisRef: Any, property: KProperty<*>): CoroutineContext = job + defDispatcher

    private inline val isActive: Boolean
        get() = lifecycle.currentState != Lifecycle.State.DESTROYED

    private fun cancelIfDestroyed(): Boolean = isActive.not().also { destroyed ->
        if (destroyed) job.cancel()
    }

    init {
        if (! cancelIfDestroyed()) {
            lifecycle.addObserver(this)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    fun onState() {
        if (cancelIfDestroyed()) {
            lifecycle.removeObserver(this)
        }
    }
}

fun LifecycleOwner.lifecycleCoroutineContext(
    defDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ReadOnlyProperty<Any, CoroutineContext> {
    return LifecycleCoroutineContext(lifecycle, defDispatcher)
}


@UseExperimental(ExperimentalCoroutinesApi::class)
fun BroadcastChannel<Unit>.offer() = offer(kotlin.Unit)


suspend fun SupportMapFragment.awaitMap(): GoogleMap {
    return CompletableDeferred<GoogleMap>().run {
        getMapAsync { complete(it) }
        await()
    }
}