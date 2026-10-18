package ru.lumastack.lumawork.ui.salary

/** An async presentation may finish only within the same unlocked foreground interval. */
internal class SalaryPresentationAccess {
    private var generation = 0L
    private var unlocked = false
    fun unlock() { unlocked = true }
    fun lock() { unlocked = false; generation++ }
    fun ticket(): Long? = generation.takeIf { unlocked }
    fun permits(ticket: Long?): Boolean = unlocked && ticket != null && ticket == generation
}
