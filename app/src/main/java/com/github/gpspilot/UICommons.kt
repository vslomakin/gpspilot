package com.github.gpspilot

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes


fun Context.longToast(@StringRes text: Int) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

fun Context.shortToast(@StringRes text: Int) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
