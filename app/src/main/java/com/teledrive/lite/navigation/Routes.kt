package com.teledrive.lite.navigation

object Routes {
    const val Splash = "splash"
    const val Setup = "setup"
    const val Tutorial = "tutorial"
    const val Home = "home"

    fun initialRoute(setupComplete: Boolean): String = if (setupComplete) Home else Splash
}
