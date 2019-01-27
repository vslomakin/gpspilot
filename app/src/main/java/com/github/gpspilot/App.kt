package com.github.gpspilot

import android.app.Application
import kotlinx.coroutines.GlobalScope
import org.koin.android.ext.android.get
import timber.log.Timber


class App : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startDi()

        get<Repository>().apply {
            GlobalScope.removeUnnecessaryRoutes()
        }
    }

}