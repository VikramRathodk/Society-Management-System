package org.devvikram.societymanagement.user.application

import org.devvikram.societymanagement.user.domain.ResidentType

// societyId is required for SOCIETY roles and must be empty for PLATFORM roles;
// flatId/residentType apply only to roles that require a flat (e.g. RESIDENT).
data class AssignRoleCommand(
    val roleCode: String,
    val societyId: Long? = null,
    val flatId: Long? = null,
    val residentType: ResidentType? = null,
)

// A user is always created with a first role assignment; more can be added via assignRole.
data class CreateUserCommand(
    val name: String,
    val phone: String,
    val email: String? = null,
    val rawPassword: String,
    val role: AssignRoleCommand,
)

data class UpdateProfileCommand(
    val name: String? = null,
    val email: String? = null,
    val fcmToken: String? = null,
)
