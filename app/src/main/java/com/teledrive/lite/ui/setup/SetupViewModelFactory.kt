package com.teledrive.lite.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.teledrive.lite.repository.SetupConnectionService
import com.teledrive.lite.settings.SetupInitializationService

class SetupViewModelFactory(
    private val connectionService: SetupConnectionService,
    private val initializationService: SetupInitializationService,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SetupViewModel::class.java)) {
            "Unsupported ViewModel class"
        }
        @Suppress("UNCHECKED_CAST")
        return SetupViewModel(connectionService, initializationService) as T
    }
}
