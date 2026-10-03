package org.devvikram.societymanagement.auth.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.user.domain.Role
import java.util.UUID

data class LoginRequest(
    @field:NotBlank val phone: String,
    @field:NotBlank val password: String,
)

data class LoginResponse(
    val token: String,
    val userId: UUID,
    val name: String,
    val role: Role,
    val permissions: Set<String>,
)
