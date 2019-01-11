package com.github.gpspilot

import android.app.Application
import com.google.android.gms.location.LocationServices
import org.koin.android.ext.android.startKoin
import org.koin.android.ext.koin.androidApplication
import org.koin.android.viewmodel.ext.koin.viewModel
import org.koin.dsl.module.module
import org.koin.log.EmptyLogger
import javax.xml.parsers.DocumentBuilderFactory


fun Application.startDi() = startKoin(
    androidContext = this,
    modules = listOf(module()),
    logger = EmptyLogger()
)

private fun module() = module {
    single { DocumentBuilderFactory.newInstance() }
    single { LocationServices.getFusedLocationProviderClient(androidApplication()) }

    viewModel { MainActivityVM(androidApplication()) }
    viewModel { MapVM(get(), get(), get()) }
}