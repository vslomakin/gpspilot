package com.github.gpspilot

import androidx.lifecycle.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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


@ExperimentalCoroutinesApi
fun BroadcastChannel<Unit>.offer() = offer(Unit)


suspend fun SupportMapFragment.awaitMap(): GoogleMap {
    return CompletableDeferred<GoogleMap>().run {
        getMapAsync { complete(it) }
        await()
    }
}


/**
 * Runs [block] for every element in channel in separate coroutine.
 * When new element comes - previous coroutine cancels.
 */
@ObsoleteCoroutinesApi
suspend fun <T> ReceiveChannel<T>.consumeSeparately(
    waitPreviousCompletion: Boolean = false,
    block: suspend (T) -> Unit
) {
    val stop: suspend Job.() -> Unit = if (waitPreviousCompletion) {
        { cancelAndJoin() }
    } else {
        { cancel() }
    }

    val scope = CoroutineScope(coroutineContext)

    var job: Job? = null
    consumeEach {
        job?.stop()
        job = scope.launch { block(it) }
    }
    job?.stop() // cancel coroutine when channel closed
}



@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun <T> ReceiveChannel<T>.distinctUntilChanged(
    ctx: CoroutineContext = Dispatchers.Unconfined
): ReceiveChannel<T> = GlobalScope.produce(ctx) {
    val prev = LateinitValue<T>()
    consumeEach {
        if (!prev.isInitialized || prev.value != it) {
            send(it)
            prev.value = it
        }
    }
}

/**
 * Completes stream by item which the [predicate] will satisfy.
 * Unlike [takeWhile], it will send item for which predicate returned `true`
 * and only then completes channel.
 */
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
fun <T> ReceiveChannel<T>.completeBy(
    ctx: CoroutineContext = Dispatchers.Unconfined,
    predicate: suspend (T) -> Boolean
): ReceiveChannel<T> = GlobalScope.produce(ctx) {
    for (i in this@completeBy) {
        val isLast = predicate(i)
        send(i)
        if (isLast) break
    }
}


@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
private class CoroutineLifecycleObserver(private val lifecycle: Lifecycle) : LifecycleObserver {
    private val broadcast = ConflatedBroadcastChannel<Lifecycle.State>()
    fun states() = broadcast.openSubscription().completeBy {
        // DESTROYED - is the last state, complete channel after that
        it == Lifecycle.State.DESTROYED
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    fun onAny() = broadcast.offer(lifecycle.currentState)
}

/**
 * Runs block in separate coroutine when lifecycle appears it least at state [state]
 * and cancels this coroutine when [state] is gone.
 */
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
suspend fun LifecycleOwner.runWhen(state: Lifecycle.State, block: suspend () -> Unit) {
    val sourceCtx = coroutineContext
    lifecycle.states { states ->
        val shouldRun = states.map { it.isAtLeast(state) }.distinctUntilChanged()
        withContext(sourceCtx) {
            shouldRun.consumeSeparately {
                if (it) block()
            }
        }
    }
}

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
private suspend fun Lifecycle.states(
    scope: suspend (states: ReceiveChannel<Lifecycle.State>) -> Unit
) {
    withContext(Dispatchers.Main) {
        val observer = CoroutineLifecycleObserver(this@states)
        try {
            addObserver(observer)
            observer.states().consume {
                scope(this)
            }
        } finally {
            removeObserver(observer)
        }
    }
}



@ExperimentalCoroutinesApi
fun <T> T.asChannel(ctx: CoroutineContext = Dispatchers.Unconfined): ReceiveChannel<T> = GlobalScope.produce(ctx) {
    send(this@asChannel)
}

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun <T> ReceiveChannel<T>.append(
    another: ReceiveChannel<T>,
    capacity: Int = 0,
    ctx: CoroutineContext = Dispatchers.Unconfined
): ReceiveChannel<T> = GlobalScope.produce(ctx, capacity) {
    consumeEach { send(it) }
    another.consumeEach { send(it) }
}