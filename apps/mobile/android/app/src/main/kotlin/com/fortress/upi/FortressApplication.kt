package com.fortress.upi

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.fortress.upi.core.offline.OfflineEngine
import com.fortress.upi.core.offline.OfflineEngineProvider
import com.fortress.upi.di.AppContainer

class FortressApplication : Application(), OfflineEngineProvider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appLock)
        container.onAppStart()
    }

    override fun offlineEngine(): OfflineEngine = container.offlineEngine
}
