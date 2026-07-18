package com.teledrive.lite.ui.settings

object SettingsPresentation {
    fun chunkSizeRows(): List<List<Int>> = CHUNK_SIZES_MIB.chunked(MAX_CHOICES_PER_ROW)

    private val CHUNK_SIZES_MIB = listOf(4, 8, 12, 18, 19)
    private const val MAX_CHOICES_PER_ROW = 3
}
