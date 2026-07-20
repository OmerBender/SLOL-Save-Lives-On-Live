package com.rescue360.detector

import android.app.Application
import com.arashivision.sdkcamera.InstaCameraSDK
import com.arashivision.sdkmedia.InstaMediaSDK
import timber.log.Timber

class Rescue360App : Application() {

    override fun onCreate() {
        super.onCreate()

        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }

        InstaCameraSDK.init(this)
        InstaMediaSDK.init(this)
    }
}
