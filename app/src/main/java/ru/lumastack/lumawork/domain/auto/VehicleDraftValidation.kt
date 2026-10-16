package ru.lumastack.lumawork.domain.auto

/** The add flow checks these at its individual steps; editing checks them together. */
object VehicleDraftValidation {
    fun identityError(draft: VehicleDraft): String? =
        if (draft.make.isBlank() || draft.model.isBlank()) "Укажите марку и модель." else null

    fun error(draft: VehicleDraft): String? =
        VehicleInputValidation.plateError(draft.licensePlate)
            ?: VehicleInputValidation.vinError(draft.vin)
            ?: identityError(draft)
}
