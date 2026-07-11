package com.example.hjp

import android.app.Application

class HjpApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
