package com.github.gpspilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableFloat
import androidx.databinding.ObservableInt
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import kotlin.reflect.KClass


fun Context.longToast(@StringRes text: Int) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

fun Context.shortToast(@StringRes text: Int) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}




fun Context.start(activity: KClass<out Activity>, extras: Bundle? = null, flags: Int? = null) {
    val intent = Intent(this, activity.java).apply {
        extras?.let(::putExtras)
        flags?.let(::setFlags)
    }
    startActivity(intent)
}

inline fun <reified T : Activity> Context.start(extras: Bundle? = null, flags: Int? = null) {
    start(T::class, extras, flags)
}



fun Activity.start(activity: KClass<out Activity>, requestCode: Int?, extras: Bundle? = null) {
    val intent = Intent(this, activity.java)
    extras?.let(intent::putExtras)
    requestCode?.let { startActivityForResult(intent, requestCode) } ?: startActivity(intent)
}

inline fun <reified T : Activity> Activity.start(requestCode: Int, extras: Bundle? = null) =
    start(T::class, requestCode, extras)


/**
 * Returns intent extra data with [name] and [T] type or `null` if it unavailable.
 */
inline fun <reified T> Activity.extra(name: String): T? = with(intent) {
    if (hasExtra(name)) {
        when (T::class) {
            Long::class -> getLongExtra(name, Long.MIN_VALUE) as T
            String::class -> getStringExtra(name) as T?
            else -> throw IllegalArgumentException("${T::class} is not supported!")
        }
    } else {
        null
    }
}


typealias ObservableString = ObservableField<String>

class Observable<T> : ObservableField<T>() {
    inline var value: T? set(value) = set(value); get() = get()
}

inline var ObservableBoolean.value: Boolean set(value) = set(value); get() = get()
inline var ObservableInt.value: Int set(value) = set(value); get() = get()
inline var ObservableFloat.value: Float set(value) = set(value); get() = get()
inline var ObservableString.value: String? set(value) = set(value); get() = get()

/**
 * Wrapper around [ObservableInt] for convenient [View]'s visibility handling.
 * @param invisible Will be used for when visibility will be set to `false`.
 *                  Assumed to be either [View.INVISIBLE] or [View.GONE].
 * @param visible Initial visibility value.
 */
class ObservableVisibility(val invisible: Int, visible: Boolean) : ObservableInt() {

    var value: Boolean
        set(value) = set(value.toIntVisibility())
        get() = get().toBoolVisibility()

    init { value = visible }

    private fun Boolean.toIntVisibility() = if (this) View.VISIBLE else invisible

    private fun Int.toBoolVisibility() = (this == View.VISIBLE)

    fun switch() {
        value = !value
    }
}



inline val Context.inflater: LayoutInflater get() = LayoutInflater.from(this)


fun Context.compatDrawable(@DrawableRes resId: Int): Drawable? = ContextCompat.getDrawable(this, resId)


inline fun FragmentManager.performTransaction(body: FragmentTransaction.() -> Unit) {
    beginTransaction().apply(body).commit()
}