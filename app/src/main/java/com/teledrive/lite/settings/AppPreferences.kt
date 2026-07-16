package com.teledrive.lite.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.teledrive.lite.transfer.StreamingChunker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@SuppressLint("UseKtx") // commit() result is required so failed preference writes are observable.
class AppPreferences(
    private val preferences: SharedPreferences,
) {
    private val themeState = MutableStateFlow(loadTheme())
    private val chunkSizeState = MutableStateFlow(loadChunkSize())

    val themeMode: StateFlow<ThemeMode> = themeState
    val defaultChunkSizeBytes: StateFlow<Int> = chunkSizeState

    fun setThemeMode(mode: ThemeMode) {
        require(preferences.edit().putString(KEY_THEME, mode.name).commit())
        themeState.value = mode
    }

    fun setDefaultChunkSizeBytes(bytes: Int) {
        require(bytes in MIN_CHUNK_SIZE_BYTES..StreamingChunker.MAX_PLAINTEXT_CHUNK_SIZE_BYTES)
        require(preferences.edit().putInt(KEY_CHUNK_SIZE, bytes).commit())
        chunkSizeState.value = bytes
    }

    private fun loadTheme(): ThemeMode = preferences.getString(KEY_THEME, null)
        ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
        ?: ThemeMode.SYSTEM

    private fun loadChunkSize(): Int = preferences.getInt(
        KEY_CHUNK_SIZE,
        StreamingChunker.DEFAULT_CHUNK_SIZE_BYTES,
    ).takeIf { it in MIN_CHUNK_SIZE_BYTES..StreamingChunker.MAX_PLAINTEXT_CHUNK_SIZE_BYTES }
        ?: StreamingChunker.DEFAULT_CHUNK_SIZE_BYTES

    companion object {
        const val MIN_CHUNK_SIZE_BYTES = 1024 * 1024
        private const val KEY_THEME = "theme_mode"
        private const val KEY_CHUNK_SIZE = "default_chunk_size_bytes"
    }
}
