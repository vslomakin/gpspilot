package com.github.gpspilot

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import e
import w
import java.io.File


fun File.append(child: String): File = File(this, child)

inline infix fun Exception.logE(message: () -> String) = e(this, message)
inline infix fun Exception.logW(message: () -> String) = w(this, message)

fun Context.isPermissionGranted(permission: String): Boolean {
    val state = ContextCompat.checkSelfPermission(this, permission)
    return state == PackageManager.PERMISSION_GRANTED
}

fun Int.minInMilliseconds(): Long = this * 60L * 1000L


class LateinitValue<T> {
    var isInitialized: Boolean = false
        private set

    private var _value: T? = null
        set(value) {
            field = value
            isInitialized = true
        }
        get() {
            return if (isInitialized) field
            else throw IllegalStateException("Value isn't initialized")
        }

    @Suppress("UNCHECKED_CAST")
    var value: T
        set(newValue) {
            _value = newValue
        }
        get() = _value as T

    inline fun ifInitialized(block: (T) -> Unit) {
        if (isInitialized) block(value)
    }
}


/**
 * Helper property to call after `when` which doesn't assumed to return result,
 * but it needed to compiler check that this `when` is exhaustive.
 */
inline val Any.exhaustive get() = Unit


/**
 * Returns the first element position yielding the smallest value of the given function
 * or `null` if there are no elements.
 */
inline fun <T, R : Comparable<R>> Sequence<T>.minPositionBy(selector: (T) -> R): Int? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var minPosition = 0
    var currentPosition = 0
    var minValue = selector(iterator.next())
    while (iterator.hasNext()) {
        currentPosition++
        val v = selector(iterator.next())
        if (minValue > v) {
            minValue = v
            minPosition = currentPosition
        }
    }
    return minPosition
}


/**
 * Executes the given [block] and returns [Pair] of [block]'s result elapsed time in milliseconds.
 * The function is useful, when you need to measure of some new value evaluation and use this
 * value outside of [block] scope without `var` declaration.
 *
 * In feature, when contracts will be stable, this function become unnecessary.
 */
inline fun <T> measureTimeMillis(block: () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    return block() to (System.currentTimeMillis() - start)
}