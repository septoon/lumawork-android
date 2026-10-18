package ru.lumastack.lumawork.data.salary

import android.content.Context
import java.security.MessageDigest

data class SalaryDisplayOptions(val hidden: Boolean = true, val excludedKinds: Set<SalaryKind> = emptySet())

class SalaryDisplayPreferences(context: Context, owner: String) {
    private val preferences = context.getSharedPreferences("salary-display", Context.MODE_PRIVATE)
    private val prefix = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
    fun read() = SalaryDisplayOptions(preferences.getBoolean("$prefix-hidden", true),
        preferences.getStringSet("$prefix-excluded", emptySet()).orEmpty().mapNotNull { name -> SalaryKind.entries.find { it.name == name } }.toSet())
    fun save(options: SalaryDisplayOptions) {
        preferences.edit().putBoolean("$prefix-hidden", options.hidden)
            .putStringSet("$prefix-excluded", options.excludedKinds.map { it.name }.toSet()).apply()
    }
}
