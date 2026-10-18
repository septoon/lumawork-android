package ru.lumastack.lumawork.ui.salary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryAccessStateTest {
    @Test
    fun `initial setup requires confirmation and successful secure save before unlocking`() {
        val confirmed = SalaryAccessState(null).enter("1357").enter("1357")
        assertFalse(confirmed.isUnlocked)
        assertNull(confirmed.savedPin)
        assertEquals("1357", confirmed.pinToSave)

        val failedSave = confirmed.failed("Не удалось сохранить PIN.")
        assertFalse(failedSave.isUnlocked)
        assertNull(failedSave.savedPin)
        assertNull(failedSave.pinToSave)

        val saved = failedSave.enter("1357").pinSaved()
        assertTrue(saved.isUnlocked)
        assertEquals("1357", saved.savedPin)
    }

    @Test
    fun `wrong pin stays locked with a visible error and can be retried`() {
        val wrong = SalaryAccessState("1357").enter("2468")
        assertFalse(wrong.isUnlocked)
        assertNotNull(wrong.error)
        assertEquals("", wrong.input)
        assertTrue(wrong.enter("1357").isUnlocked)
    }

    @Test
    fun `confirmation mismatch and deleting digits cannot unlock or replace existing pin`() {
        val confirmed = recoveryReady().recoveryVerified(true).enter("2468").enter("1111")
        assertEquals("1357", confirmed.savedPin)
        assertNull(confirmed.pinToSave)
        assertFalse(confirmed.isUnlocked)
        assertNotNull(confirmed.error)
        val edited = confirmed.enter("24").deleteDigit().enter("468")
        assertEquals("2468", edited.pinToSave)
    }

    @Test
    fun `a recovery code response cannot start reset without explicit verification`() {
        val untouched = SalaryAccessState("1357").recoveryVerified(true).recoverySent("user@test.invalid")
        assertEquals(SalaryAccessPhase.EnterPin, untouched.phase)
        assertFalse(untouched.isUnlocked)
        assertEquals("1357", untouched.savedPin)
    }

    @Test
    fun `verification requires six digits and rejects a different account`() {
        val input = SalaryAccessState("1357").requestRecovery().recoverySent("user@test.invalid")
        assertFalse(input.recoveryInput("12345").verifyRecovery().isBusy)
        val rejected = input.recoveryInput("123456").verifyRecovery().recoveryVerified(false)
        assertEquals(SalaryAccessPhase.RecoveryCode, rejected.phase)
        assertFalse(rejected.isUnlocked)
        assertNotNull(rejected.error)
        assertEquals("1357", rejected.savedPin)
    }

    @Test
    fun `recovery checks both stable user id and email`() {
        assertTrue(salaryRecoveryMatchesUser("owner", " Owner@Test.invalid ", "owner", "owner@test.invalid"))
        assertFalse(salaryRecoveryMatchesUser("owner", "owner@test.invalid", "someone-else", "owner@test.invalid"))
        assertFalse(salaryRecoveryMatchesUser("owner", "owner@test.invalid", "owner", "other@test.invalid"))
        assertFalse(salaryRecoveryMatchesUser("", "owner@test.invalid", "", "owner@test.invalid"))
        assertFalse(salaryRecoveryMatchesUser("owner", "", "owner", ""))
    }

    @Test
    fun `leaving foreground revokes recovery proof and ignores late verification`() {
        val pending = recoveryReady()
        val background = pending.relock().recoveryVerified(true)
        assertEquals(SalaryAccessPhase.EnterPin, background.phase)
        assertFalse(background.isUnlocked)
        val resetInProgress = pending.recoveryVerified(true).enter("2468").relock()
        assertEquals(SalaryAccessPhase.EnterPin, resetInProgress.phase)
        assertEquals("1357", resetInProgress.savedPin)
        assertNull(resetInProgress.firstPin)
        assertNull(resetInProgress.pinToSave)
    }

    @Test
    fun `successful recovery preserves old pin until replacement is securely saved`() {
        val replacement = recoveryReady().recoveryVerified(true).enter("2468").enter("2468")
        assertFalse(replacement.isUnlocked)
        assertEquals("1357", replacement.savedPin)
        assertEquals("2468", replacement.pinToSave)
        assertEquals("2468", replacement.pinSaved().savedPin)
    }

    @Test
    fun `biometric success only unlocks an existing pin gate and background relocks it`() {
        assertFalse(SalaryAccessState(null).biometricUnlocked().isUnlocked)
        assertFalse(recoveryReady().biometricUnlocked().isUnlocked)
        val unlocked = SalaryAccessState("1357").biometricUnlocked()
        assertTrue(unlocked.isUnlocked)
        assertFalse(unlocked.relock().isUnlocked)
        assertEquals("1357", unlocked.relock().savedPin)
    }

    @Test fun `late document presentation is rejected after lock even if user unlocks again`() {
        val access = SalaryPresentationAccess()
        assertNull(access.ticket())
        access.unlock()
        val pending = access.ticket()
        assertTrue(access.permits(pending))
        access.lock()
        assertFalse(access.permits(pending))
        access.unlock()
        assertFalse(access.permits(pending))
        assertTrue(access.permits(access.ticket()))
    }

    private fun recoveryReady(): SalaryAccessState = SalaryAccessState("1357")
        .requestRecovery().recoverySent("owner@test.invalid")
        .recoveryInput("123456").verifyRecovery()

    private fun SalaryAccessState.enter(pin: String): SalaryAccessState = pin.fold(this) { state, digit -> state.digit(digit.digitToInt()) }
}
