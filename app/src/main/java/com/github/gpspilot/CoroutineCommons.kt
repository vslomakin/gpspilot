package com.github.gpspilot

import androidx.lifecycle.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import d
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
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


@ExperimentalCoroutinesApi fun BroadcastChannel<Unit>.offer() = offer(Unit)
@ExperimentalCoroutinesApi suspend fun BroadcastChannel<Unit>.send() = send(Unit)

fun CompletableDeferred<Unit>.complete() = complete(Unit)


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
    ctx: CoroutineContext
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
        val shouldRun = states.map { it.isAtLeast(state) }.distinctUntilChanged(coroutineContext)
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
fun <T> T.asChannel(ctx: CoroutineContext): ReceiveChannel<T> = GlobalScope.produce(ctx) {
    send(this@asChannel)
}

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun <T> ReceiveChannel<T>.append(
    another: ReceiveChannel<T>,
    capacity: Int = 0,
    ctx: CoroutineContext
): ReceiveChannel<T> = GlobalScope.produce(ctx, capacity) {
    consumeEach { send(it) }
    another.consumeEach { send(it) }
}

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
fun <T> ReceiveChannel<T>.startWith(
    ctx: CoroutineContext,
    startItem: T,
    capacity: Int = 0
): ReceiveChannel<T> = GlobalScope.produce(ctx, capacity) {
    send(startItem)
    consumeEach { send(it) }
}


val infiniteDeferred: Deferred<Nothing> by lazy { CompletableDeferred<Nothing>() }



@ExperimentalCoroutinesApi
fun isClosed(channel: ReceiveChannel<*>, vararg channels: ReceiveChannel<*>): Boolean {
    return if (channel.isClosedForReceive) {
        true
    } else {
        channels.any { it.isClosedForReceive }
    }
}

@ExperimentalCoroutinesApi
suspend fun <T1, T2> consumeLatest(
    channel1: ReceiveChannel<T1>,
    channel2: ReceiveChannel<T2>,
    consumer: suspend (T1, T2) -> Unit
) {
    val value1 = LateinitValue<T1>()
    val value2 = LateinitValue<T2>()
    try {
        while (! isClosed(channel1, channel2)) {
            select<Unit> {
                channel1.onReceive { value1.value = it }
                channel2.onReceive { value2.value = it }
            }

            ifAllInitialized(value1, value2) { v1, v2 -> consumer(v1, v2) }
        }
    } catch (e: ClosedReceiveChannelException) {
        d { "One of channels has been closed." }
    }
}


@ExperimentalCoroutinesApi
suspend fun <T1, T2, T3> consumeLatest(
    channel1: ReceiveChannel<T1>,
    channel2: ReceiveChannel<T2>,
    channel3: ReceiveChannel<T3>,
    consumer: suspend (T1, T2, T3) -> Unit
) {
    val value1 = LateinitValue<T1>()
    val value2 = LateinitValue<T2>()
    val value3 = LateinitValue<T3>()
    try {
        while (! isClosed(channel1, channel2, channel3)) {
            select<Unit> {
                channel1.onReceive { value1.value = it }
                channel2.onReceive { value2.value = it }
                channel3.onReceive { value3.value = it }
            }

            ifAllInitialized(value1, value2, value3) { v1, v2, v3 -> consumer(v1, v2, v3) }
        }
    } catch (e: ClosedReceiveChannelException) {
        d { "One of channels has been closed." }
    }
}



@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun <T> ReceiveChannel<T>.broadcast(
    ctx: CoroutineContext,
    capacity: Int
): BroadcastChannel<T> = GlobalScope.broadcast(ctx, capacity) {
    this@broadcast.consumeEach { send(it) }
}


@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun ReceiveChannel<Float>.average(
    ctx: CoroutineContext,
    capacity: Int = 0
): ReceiveChannel<Float> = GlobalScope.produce(ctx, capacity) {
    var sum = 0.0
    var count = 0L
    consumeEach {
        sum += it
        count++
        val average = (sum / count).toFloat()
        send(average)
    }
}