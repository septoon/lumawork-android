package ru.lumastack.lumawork.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetContentScrollConnectionTest {
    @Test fun `remaining vertical touch scroll does not drag the sheet`() {
        assertEquals(Offset(0f, 24f), SheetContentScrollConnection.onPostScroll(
            Offset.Zero, Offset(8f, 24f), NestedScrollSource.UserInput,
        ))
        assertEquals(Offset(0f, -24f), SheetContentScrollConnection.onPostScroll(
            Offset.Zero, Offset(8f, -24f), NestedScrollSource.UserInput,
        ))
    }

    @Test fun `animated scroll deltas are not consumed`() {
        assertEquals(Offset.Zero, SheetContentScrollConnection.onPostScroll(
            Offset.Zero, Offset(0f, 24f), NestedScrollSource.SideEffect,
        ))
    }

    @Test fun `remaining vertical fling does not settle the sheet`() = runTest {
        assertEquals(Velocity(0f, 200f), SheetContentScrollConnection.onPostFling(
            Velocity.Zero, Velocity(10f, 200f),
        ))
    }
}
