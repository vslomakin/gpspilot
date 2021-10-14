package com.github.gpspilot

import org.junit.Test

class CommonsKtTest {

    @Test fun minPositionBy() {
        sequenceOf("12", "43", "4", "55").minPositionBy { it.toInt() } assertEq 2
        sequenceOf(1, 2, 3, 4).minPositionBy { it * 1000 } assertEq 0
        emptySequence<Int>().minPositionBy { it } assertEq null
    }

}