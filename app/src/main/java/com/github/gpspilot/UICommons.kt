package com.github.gpspilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
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

inline fun <reified T : Activity> Context.startInNewTask() {
    start<T>(flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
}



fun Activity.start(activity: KClass<out Activity>, requestCode: Int?, extras: Bundle? = null) {
    val intent = Intent(this, activity.java)
    extras?.let(intent::putExtras)
    requestCode?.let { startActivityForResult(intent, requestCode) } ?: startActivity(intent)
}

inline fun <reified T : Activity> Activity.start(requestCode: Int, extras: Bundle? = null) =
    start(T::class, requestCode, extras)



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
