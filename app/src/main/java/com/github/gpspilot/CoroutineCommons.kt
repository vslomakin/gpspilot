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


/**
 * Wrapper around [ViewModel] that implements [CoroutineScope].
 * Internal job will be canceled by [ViewModel.onCleared].
 */
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


/**
 * Property delegate that creates [CoroutineContext] from [Lifecycle].
 * [CoroutineContext]'s [Job] will be canceled when [Lifecycle] switches to [Lifecycle.State.DESTROYED] state.
 */
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


/**
 * Suspend until [GoogleMap] will be available.
 */
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


/**
 * Skips the same values that comes one after another.
 */
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


/**
 * Produces channel which initially sends [startItem] and then all elements from current channel.
 */
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


/**
 * [Deferred] that never completes. Useful when it needed to suspend coroutine forever.
 */
val infiniteDeferred: Deferred<Nothing> by lazy { CompletableDeferred<Nothing>() }



@ExperimentalCoroutinesApi
fun isClosed(channel: ReceiveChannel<*>, vararg channels: ReceiveChannel<*>): Boolean {
    return if (channel.isClosedForReceive) {
        true
    } else {
        channels.any { it.isClosedForReceive }
    }
}


/**
 * Calls [consumer] with latest values from [channel1] and [channel2].
 * Initially it waits until values from all channels will be received.
 */
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

/**
* Calls [consumer] with latest values from [channel1], [channel2] and [channel3].
* Initially it waits until values from all channels will be received.
*/
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


/**
 * Calculate average value from all elements.
 * Current average value will be recalculated and send for every item in current channel.
 */
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