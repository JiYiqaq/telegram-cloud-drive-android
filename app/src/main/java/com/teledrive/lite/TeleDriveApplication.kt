/*
 * Initial implementation created with OpenAI Codex
 * based on requirements provided by the project maintainer.
 */

package com.teledrive.lite

import android.app.Application
import com.teledrive.lite.app.AppContainer

class TeleDriveApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
