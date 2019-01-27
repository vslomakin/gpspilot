package com.github.gpspilot

import android.app.Application
import com.crashlytics.android.Crashlytics
import com.crashlytics.android.core.CrashlyticsCore
import io.fabric.sdk.android.Fabric
import kotlinx.coroutines.GlobalScope
import org.koin.android.ext.android.get
import timber.log.Timber


class App : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashlyticsTree())
        }

        val crashlyticsCore = CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build()
        Fabric.with(this, Crashlytics.Builder().core(crashlyticsCore).build())

        startDi()

        get<Repository>().apply {
            GlobalScope.removeUnnecessaryRoutes()
        }
    }

}