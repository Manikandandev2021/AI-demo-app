package com.mani.aidemo

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize


// Register in AndroidManifest.xml: <application android:name=".AiDemoApp" ... >
class AiDemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(this)
        // Debug provider for development only. On first run, Logcat prints a debug token:
        // copy it into Firebase console > App Check > your app > Manage debug tokens.
        // For a release build, switch to PlayIntegrityAppCheckProviderFactory.
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
    }
}
