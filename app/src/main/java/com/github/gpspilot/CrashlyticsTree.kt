package com.github.gpspilot

import android.util.Log
import com.crashlytics.android.Crashlytics
import timber.log.Timber


class CrashlyticsTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.ERROR) {
            Crashlytics.log(priority, tag, "$message - $t")
        }
    }
}