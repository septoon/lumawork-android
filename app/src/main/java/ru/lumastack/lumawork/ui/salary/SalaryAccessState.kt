package ru.lumastack.lumawork.ui.salary

internal enum class SalaryAccessPhase { EnterPin, CreatePin, ConfirmPin, RecoveryCode }

/** Kept in memory only: PIN input and email proof never survive leaving the salary gate. */
internal data class SalaryAccessState(
    val savedPin: String?,
    val phase: SalaryAccessPhase = if (savedPin == null) SalaryAccessPhase.CreatePin else SalaryAccessPhase.EnterPin,
    val input: String = "",
    val firstPin: String? = null,
    val pinToSave: String? = null,
    val recoveryCode: String = "",
    val isBusy: Boolean = false,
    val isUnlocked: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    fun digit(digit: Int): SalaryAccessState {
        if (isBusy || isUnlocked || digit !in 0..9 || input.length >= 4 || phase == SalaryAccessPhase.RecoveryCode) return this
        val next = input + digit
        val entered = copy(input = next, error = null, notice = null)
        if (next.length != 4) return entered
        return when (phase) {
            SalaryAccessPhase.EnterPin -> if (next == savedPin) entered.copy(isUnlocked = true, input = "")
                else entered.copy(input = "", error = "Неверный PIN. Попробуйте ещё раз.")
            SalaryAccessPhase.CreatePin -> entered.copy(phase = SalaryAccessPhase.ConfirmPin, firstPin = next, input = "")
            SalaryAccessPhase.ConfirmPin -> if (next == firstPin) entered.copy(pinToSave = next)
                else entered.copy(input = "", error = "PIN не совпадают. Повторите код.")
            SalaryAccessPhase.RecoveryCode -> this
        }
    }

    fun pinSaved(): SalaryAccessState = if (pinToSave != null) {
        SalaryAccessState(savedPin = pinToSave, isUnlocked = true)
    } else this

    fun deleteDigit(): SalaryAccessState = if (isBusy) this else copy(input = input.dropLast(1), error = null)

    fun restartSetup(): SalaryAccessState = when (phase) {
        SalaryAccessPhase.CreatePin, SalaryAccessPhase.ConfirmPin -> copy(
            phase = SalaryAccessPhase.CreatePin, input = "", firstPin = null, pinToSave = null, error = null,
        )
        else -> this
    }

    fun requestRecovery(): SalaryAccessState = if (
        !isBusy && savedPin != null && phase in setOf(SalaryAccessPhase.EnterPin, SalaryAccessPhase.RecoveryCode)
    ) copy(isBusy = true, input = "", error = null, notice = null) else this

    fun recoverySent(email: String): SalaryAccessState = if (isBusy && phase in setOf(SalaryAccessPhase.EnterPin, SalaryAccessPhase.RecoveryCode)) {
        copy(phase = SalaryAccessPhase.RecoveryCode, recoveryCode = "", isBusy = false, notice = "Разовый код отправлен на $email.")
    } else this

    fun recoveryInput(value: String): SalaryAccessState = if (isBusy || phase != SalaryAccessPhase.RecoveryCode) this
        else copy(recoveryCode = value.filter { it in '0'..'9' }.take(6), error = null)

    fun verifyRecovery(): SalaryAccessState = when {
        isBusy || phase != SalaryAccessPhase.RecoveryCode -> this
        recoveryCode.length != 6 -> copy(error = "Введите 6-значный код.")
        else -> copy(isBusy = true, error = null, notice = null)
    }

    fun recoveryVerified(sameUser: Boolean): SalaryAccessState = when {
        !isBusy || phase != SalaryAccessPhase.RecoveryCode -> this
        !sameUser -> failed("Подтверждён другой пользователь. Войдите в приложение заново.")
        else -> copy(
            phase = SalaryAccessPhase.CreatePin, input = "", firstPin = null, recoveryCode = "", isBusy = false,
            notice = "Код подтверждён. Придумайте новый PIN.",
        )
    }

    fun failed(message: String): SalaryAccessState = copy(isBusy = false, error = message, notice = null, pinToSave = null, input = "")

    fun returnToPin(): SalaryAccessState = SalaryAccessState(savedPin)

    fun biometricUnlocked(): SalaryAccessState = if (savedPin != null && phase == SalaryAccessPhase.EnterPin && !isBusy) {
        copy(isUnlocked = true, input = "", error = null)
    } else this

    fun relock(): SalaryAccessState = SalaryAccessState(savedPin)
}

internal fun salaryRecoveryMatchesUser(expectedId: String, expectedEmail: String, actualId: String, actualEmail: String): Boolean =
    expectedId.isNotBlank() && expectedId == actualId && expectedEmail.isNotBlank() &&
        expectedEmail.trim().equals(actualEmail.trim(), ignoreCase = true)
