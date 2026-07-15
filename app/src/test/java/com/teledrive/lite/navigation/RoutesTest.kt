package com.teledrive.lite.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test
    fun completedSetupStartsAtHomeAndFirstRunStartsAtSplash() {
        assertEquals(Routes.Splash, Routes.initialRoute(setupComplete = false))
        assertEquals(Routes.Home, Routes.initialRoute(setupComplete = true))
    }
}
