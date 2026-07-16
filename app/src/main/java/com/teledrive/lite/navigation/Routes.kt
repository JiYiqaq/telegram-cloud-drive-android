package com.teledrive.lite.navigation

object Routes {
    const val Splash = "splash"
    const val Setup = "setup"
    const val Tutorial = "tutorial"
    const val Home = "home"
    const val Settings = "settings"
    const val Recovery = "recovery"
    const val About = "about"
    const val Search = "search"
    const val Connection = "connection"
    const val FileDetailPattern = "file/{fileId}"

    fun fileDetail(fileId: String): String = "file/$fileId"

    fun initialRoute(setupComplete: Boolean): String = if (setupComplete) Home else Splash
}
