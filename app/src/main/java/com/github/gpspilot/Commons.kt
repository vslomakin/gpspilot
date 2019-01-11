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