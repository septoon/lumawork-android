package ru.lumastack.lumawork.data.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-wide lease, retained until USB cleanup really finishes. */
class TerminalOperationGate {
    private val mutable = MutableStateFlow<String?>(null)
    val owner = mutable.asStateFlow()
    @Synchronized fun tryAcquire(operationId: String): Boolean {
        require(operationId.isNotBlank())
        if (mutable.value != null) return false
        mutable.value = operationId
        return true
    }
    @Synchronized fun release(operationId: String) {
        if (mutable.value == operationId) mutable.value = null
    }
}
internal val terminalOperationGate = TerminalOperationGate()
