package com.github.gpspilot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import org.junit.Assert


infix fun <T> T.assertEq(expected: T) {
    Assert.assertEquals(expected, this)
}


fun Boolean.assertTrue() = assert(this)
fun Boolean.assertFalse() = Assert.assertFalse(this)


@ExperimentalCoroutinesApi
suspend fun <T> ReceiveChannel<T>.receiveImmediately(): T? {
    val alwaysNull = Channel<T?>(Channel.CONFLATED).apply { offer(null) }
    return select {
        // if value already send - it will be received immediately
        onReceiveOrNull { it }
        // if not - null will be returned
        alwaysNull.onReceiveOrNull { it }
    }
}

@ExperimentalCoroutinesApi
suspend infix fun <T> ReceiveChannel<T>.assertSent(expected: T) {
    receiveImmediately() assertEq expected
}