package com.github.gpspilot

import android.app.Application
import org.koin.android.ext.android.startKoin
import org.koin.android.ext.koin.androidApplication
import org.koin.android.viewmodel.ext.koin.viewModel
import org.koin.dsl.module.module
import org.koin.log.EmptyLogger
import javax.xml.parsers.DocumentBuilderFactory


fun Application.startDi() {
    startKoin(this, listOf(module()), logger = EmptyLogger())
}

private fun module() = module {
    single { DocumentBuilderFactory.newInstance() }

    viewModel { MainActivityVM(androidApplication()) }
    viewModel { MapVM(get()) }
}