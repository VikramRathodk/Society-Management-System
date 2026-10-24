package org.devvikram.societymanagement.auth.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.user.interfaces.AccessResponse
import org.devvikram.societymanagement.user.interfaces.RoleAssignmentResponse

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
