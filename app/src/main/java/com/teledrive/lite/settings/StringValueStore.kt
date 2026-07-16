package com.teledrive.lite.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences

interface StringValueStore {
    fun get(key: String): String?

    fun put(values: Map<String, String>): Boolean

    fun remove(keys: Set<String>): Boolean
}

@SuppressLint("UseKtx") // The store contract returns commit() success for atomic setup writes.
class SharedPreferencesStringValueStore(
    private val preferences: SharedPreferences,
) : StringValueStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(values: Map<String, String>): Boolean =
        preferences.edit().apply {
            values.forEach { (key, value) -> putString(key, value) }
        }.commit()

    override fun remove(keys: Set<String>): Boolean =
        preferences.edit().apply {
            keys.forEach(::remove)
        }.commit()
}
