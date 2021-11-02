package com.github.gpspilot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Test

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
class CoroutineCommonsKtTest {

    @Test fun completeBy() = runBlocking<Unit> {
        val input = BroadcastChannel<Int>(Channel.CONFLATED)
        val output = input.openSubscription().completeBy { it == 5 }

        input.send(1)
        output assertSent 1
        input.send(2)
        output assertSent 2
        input.send(5)
        output assertSent 5
        output.isClosedForReceive.assertTrue()
    }

}