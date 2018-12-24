package com.github.gpspilot

import e
import w
import java.io.File


fun File.append(child: String): File = File(this, child)

inline infix fun Exception.logE(message: () -> String) = e(this, message)
inline infix fun Exception.logW(message: () -> String) = w(this, message)