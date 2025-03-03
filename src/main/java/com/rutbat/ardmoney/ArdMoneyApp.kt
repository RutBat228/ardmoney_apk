package com.rutbat.ardmoney

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class ArdMoneyApp : Application(), DefaultLifecycleObserver {

    companion object {
        var isAppInForeground: Boolean = false
            private set
    }

    override fun onCreate() {
        super<Application>.onCreate()
        // ConfigManager.init(this) больше не нужен
        ProcessLifecycleOwner.get().lifecycle.addObserver(this as DefaultLifecycleObserver)
    }

    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        isAppInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        isAppInForeground = false
    }
}