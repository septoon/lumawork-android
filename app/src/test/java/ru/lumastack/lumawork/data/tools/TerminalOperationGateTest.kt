package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class TerminalOperationGateTest {
    @Test fun exclusive_owner_cannot_be_replaced_or_released_by_other_operation() {
        val gate = TerminalOperationGate()
        assertTrue(gate.tryAcquire("pax"))
        assertFalse(gate.tryAcquire("mtk")); assertFalse(gate.tryAcquire("pax"))
        gate.release("mtk"); assertEquals("pax", gate.owner.value)
        gate.release("pax"); assertNull(gate.owner.value)
        assertTrue(gate.tryAcquire("mtk"))
        gate.release("mtk"); assertTrue(gate.tryAcquire("diagnostic"))
    }
}
