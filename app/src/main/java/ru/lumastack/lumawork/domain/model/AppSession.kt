package ru.lumastack.lumawork.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSession(
    val token: String,
    val user: AppUser,
)

@Serializable
data class AppUser(
    val id: String,
    val email: String,
    val role: String,
    val adminPermissions: List<String>? = null,
    val avatarUrl: String? = null,
    val profile: UserProfile? = null,
) {
    val isAdminRole: Boolean
        get() = role.lowercase() in ADMIN_ROLES

    val adminPermissionSet: Set<AdminPermission>
        get() {
            if (!isAdminRole) return emptySet()
            val values = adminPermissions ?: return AdminPermission.entries.toSet()
            return values.mapNotNull(AdminPermission::fromRawValue).toSet()
        }

    fun can(permission: AdminPermission): Boolean = permission in adminPermissionSet

    companion object {
        private val ADMIN_ROLES = setOf("admin", "administrator", "superadmin", "owner")
    }
}

enum class AdminPermission(val rawValue: String) {
    ViewOverview("overview.view"),
    ViewAuditLog("audit.view"),
    ViewGsmTemplate("gsmTemplate.view"),
    ReplaceGsmTemplate("gsmTemplate.replace"),
    ViewUsers("users.view"),
    BlockUsers("users.block"),
    ManageUserPermissions("users.permissions"),
    DeleteUsers("users.delete"),
    ViewImages("images.view"),
    CreateImages("images.create"),
    EditImages("images.edit"),
    DeleteImages("images.delete"),
    ViewFeedback("feedback.view"),
    ManageFeedback("feedback.manage");

    companion object {
        fun fromRawValue(value: String): AdminPermission? = entries.firstOrNull { it.rawValue == value }
    }
}

@Serializable
data class UserProfile(
    val firstName: String? = null,
    val lastName: String? = null,
    val middleName: String? = null,
    val jobTitle: String? = null,
    val departmentTitle: String? = null,
    val departmentGroup: String? = null,
    val personnelNumber: String? = null,
    val personalPhone: String? = null,
    val workEmail: String? = null,
    val vehicleModel: String? = null,
    val vehiclePlate: String? = null,
    val vehicleVin: String? = null,
    val vehicleSts: String? = null,
    val vehiclePts: String? = null,
    val vehicleColor: String? = null,
    val engineVolumeCm3: String? = null,
    val enginePowerHp: String? = null,
    val initialMileageKm: String? = null,
    val routeWarehouseAddress: String? = null,
    val routeHomeAddress: String? = null,
) {
    val shortDisplayName: String?
        get() = firstName.clean() ?: listOfNotNull(lastName.clean(), firstName.clean())
            .joinToString(" ")
            .ifBlank { null }
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }

