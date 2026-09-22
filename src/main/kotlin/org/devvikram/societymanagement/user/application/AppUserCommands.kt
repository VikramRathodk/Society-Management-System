package org.devvikram.societymanagement.user.application

import org.devvikram.societymanagement.user.domain.ResidentType
import org.devvikram.societymanagement.user.domain.Role
import java.util.UUID

// Exactly one of societyId/complexId must be set — mirrors the chk_user_scope DB constraint.
data class CreateAppUserCommand(
    val name: String,
    val phone: String,
    val email: String? = null,
    val rawPassword: String,
    val role: Role,
    val societyId: UUID? = null,
    val complexId: UUID? = null,
    val flatId: UUID? = null,
    val residentType: ResidentType? = null,
)

data class UpdateProfileCommand(
    val name: String? = null,
    val email: String? = null,
    val fcmToken: String? = null,
)
