package com.github.gpspilot

import android.app.Application
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import org.koin.android.ext.android.startKoin
import org.koin.android.ext.koin.androidApplication
import org.koin.android.viewmodel.ext.koin.viewModel
import org.koin.dsl.module.module
import org.koin.log.EmptyLogger
import javax.xml.parsers.DocumentBuilderFactory


@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
fun Application.startDi() = startKoin(
    androidContext = this,
    modules = listOf(module()),
    logger = EmptyLogger()
)

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
private fun module() = module {
    single { DocumentBuilderFactory.newInstance().newDocumentBuilder() }
    single { LocationServices.getFusedLocationProviderClient(androidApplication()) }
    single { createDb(androidApplication()) }
    single { Repository(get()) }

    viewModel { MainActivityVM(androidApplication(), get(), get()) }
    viewModel { MapActivityVM(get(), get(), get(), get()) }
}