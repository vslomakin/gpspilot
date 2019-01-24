package com.github.gpspilot

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import androidx.core.content.ContextCompat
import e
import w
import java.io.File
import java.lang.Math.pow
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt


fun File.append(child: String): File = File(this, child)

inline infix fun Exception.logE(message: () -> String) = e(this, message)
inline infix fun Exception.logW(message: () -> String) = w(this, message)

fun Context.isPermissionGranted(permission: String): Boolean {
    val state = ContextCompat.checkSelfPermission(this, permission)
    return state == PackageManager.PERMISSION_GRANTED
}

fun Float.meterPerSecToKmPerHour(): Float = this * 3.6f
fun Long.secToFullHour(): Long = this / 3600L
fun Long.secToFullMin(): Long = this / 60L
fun Long.hourToSec(): Long = this * 3600L
fun Long.secToMs(): Long = this *  1000L


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
 * Returns the first element position yielding the smallest value of the given function
 * or `null` if there are no elements.
 */
inline fun <T, R : Comparable<R>> Iterable<T>.minPositionBy(selector: (T) -> R): Int? {
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


inline val List<*>.lastPosition: Int get() = size - 1

fun <T> List<T>.indexOrNull(item: T): Int? = indexOf(item).takeIf { it >= 0 }


inline fun <T1, T2> ifAllInitialized(
    value1: LateinitValue<T1>,
    value2: LateinitValue<T2>,
    action: (T1, T2) -> Unit
) {
    value1.ifInitialized { v1 ->
        value2.ifInitialized { v2 ->
            action(v1, v2)
        }
    }
}

inline fun <T1, T2, T3> ifAllInitialized(
    value1: LateinitValue<T1>,
    value2: LateinitValue<T2>,
    value3: LateinitValue<T3>,
    action: (T1, T2, T3) -> Unit
) {
    value1.ifInitialized { v1 ->
        value2.ifInitialized { v2 ->
            value3.ifInitialized { v3 ->
                action(v1, v2, v3)
            }
        }
    }
}


fun <T> List<T>.getElements(positions: List<Int>): List<T> {
    return positions.map { get(it) }
}

infix fun Point.distanceTo(another: Point): Double {
    val xDelta = pow(another.x.toDouble() - x.toDouble(), 2.0)
    val yDelta = pow(another.y.toDouble() - y.toDouble(), 2.0)
    return sqrt(xDelta + yDelta)
}


infix fun IntRange.exceed(another: IntRange): Boolean {
    return (first < another.first) || (last > another.last)
}

infix fun IntRange.notExceed(another: IntRange): Boolean = !(this exceed another)

fun IntRange.skip(count: Int): IntRange = (first + count)..last

fun String.formatter() = SimpleDateFormat(this, Locale.getDefault())