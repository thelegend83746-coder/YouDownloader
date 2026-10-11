package com.youdownloader.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youdownloader.app.data.local.PreferencesManager
import com.youdownloader.app.data.local.ThemeMode
import com.youdownloader.app.data.model.VideoQuality
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)

    val themeMode: StateFlow<ThemeMode> = preferencesManager.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val wifiOnly: StateFlow<Boolean> = preferencesManager.wifiOnlyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val defaultQuality: StateFlow<String> = preferencesManager.defaultQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VideoQuality.Q_720P.label)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesManager.setThemeMode(mode)
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setWifiOnly(enabled)
        }
    }

    fun setDefaultQuality(quality: String) {
        viewModelScope.launch {
            preferencesManager.setDefaultQuality(quality)
        }
    }
}
