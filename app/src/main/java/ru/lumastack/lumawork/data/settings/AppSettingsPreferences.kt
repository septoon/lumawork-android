package ru.lumastack.lumawork.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Visibility is local to this device, as on iOS. Unknown saved ids survive app upgrades. */
data class NavigationVisibility(val disabled: Set<String> = emptySet()) {
    fun visible(available: List<String>): List<String> = available.filterNot { it in disabled }.ifEmpty { available.take(1) }
    fun enabled(id: String, available: List<String>): Boolean = id in visible(available)
    fun canDisable(id: String, available: List<String>): Boolean = !enabled(id, available) || visible(available).size > 1
    fun setEnabled(id: String, enabled: Boolean, available: List<String>): NavigationVisibility {
        if (id !in available) return this
        val next = if (enabled) disabled - id else disabled + id
        if (available.none { it !in next }) return this
        return copy(disabled = next)
    }
}

data class DeviceSettings(val navigation: NavigationVisibility = NavigationVisibility(), val salaryBiometricEnabled: Boolean = true)

class AppSettingsPreferences(context: Context) : AutoCloseable {
    private val preferences = context.applicationContext.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
    private fun read() = DeviceSettings(NavigationVisibility(preferences.getStringSet("disabled-sections", emptySet()).orEmpty().toSet()),
        preferences.getBoolean("salary-biometric-enabled", true))
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutable.value = read() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun visibility(id: String, enabled: Boolean, available: List<String>) {
        val next = mutable.value.navigation.setEnabled(id, enabled, available)
        mutable.value = mutable.value.copy(navigation = next)
        preferences.edit().putStringSet("disabled-sections", next.disabled).apply()
    }
    fun biometric(enabled: Boolean) {
        mutable.value = mutable.value.copy(salaryBiometricEnabled = enabled)
        preferences.edit().putBoolean("salary-biometric-enabled", enabled).apply()
    }
    override fun close() { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}
