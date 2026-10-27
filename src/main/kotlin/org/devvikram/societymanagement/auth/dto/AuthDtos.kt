package org.devvikram.societymanagement.auth.dto

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.user.dto.AccessResponse
import org.devvikram.societymanagement.user.dto.RoleAssignmentResponse

data class LoginRequest(
    @field:NotBlank val phone: String,
    @field:NotBlank val password: String,
)

data class LoginResponse(
    val token: String,
    val userId: Long,
    val name: String,
    val roles: List<RoleAssignmentResponse>,
    val access: AccessResponse,
)
