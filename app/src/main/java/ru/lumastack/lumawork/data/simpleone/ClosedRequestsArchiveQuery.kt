package ru.lumastack.lumawork.data.simpleone

import ru.lumastack.lumawork.BuildConfig

/** Matches the iOS full archive query; no relative month window. */
internal data class ClosedRequestsArchiveQuery(
    val currentUserDynamicId: String = BuildConfig.SIMPLEONE_CURRENT_USER_DYNAMIC_ID,
    val assignedUserDynamicId: String = BuildConfig.SIMPLEONE_ASSIGNED_USER_DYNAMIC_ID,
    val resolvedDateOptionId: String = BuildConfig.SIMPLEONE_RESOLVED_DATE_OPTION_ID,
) {
    fun condition(): String {
        if (listOf(currentUserDynamicId, assignedUserDynamicId, resolvedDateOptionId)
                .any { it.isBlank() || !it.all(Char::isDigit) }) {
            throw SimpleOneFailure.Http(400, "Не настроена полная выгрузка заявок SimpleOne.")
        }
        return "((multicard_engineerDYNAMIC$currentUserDynamicId" +
            "^ORassigned_userDYNAMIC$assignedUserDynamicId" +
            "^ORengineer_schedule.employeeDYNAMIC$currentUserDynamicId)" +
            "^resolved_atNOTONopt:$resolvedDateOptionId" +
            "^stateNOT INon_hold@assigned@in_progress@escalated@returned_to_work@3@6@update_received)"
    }
}
