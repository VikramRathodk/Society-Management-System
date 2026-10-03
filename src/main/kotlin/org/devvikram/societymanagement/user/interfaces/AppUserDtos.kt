package org.devvikram.societymanagement.user.interfaces

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.ResidentType
import org.devvikram.societymanagement.user.domain.Role
import java.util.UUID

data class CreateAppUserRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phone: String,
    val email: String? = null,
    @field:NotBlank val password: String,
    @field:NotNull val role: Role,
    val societyId: UUID? = null,
    val complexId: UUID? = null,
    val flatId: UUID? = null,
    val residentType: ResidentType? = null,
)

data class UpdateProfileRequest(
    val name: String? = null,
    val email: String? = null,
    val fcmToken: String? = null,
)

data class AppUserResponse(
    val id: UUID,
    val name: String,
    val phone: String,
    val email: String?,
    val role: Role,
    val societyId: UUID?,
    val complexId: UUID?,
    val flatId: UUID?,
    val residentType: ResidentType?,
    val isActive: Boolean,
) {
    companion object {
        fun from(user: AppUser) = AppUserResponse(
            id = user.id!!,
            name = user.name,
            phone = user.phone,
            email = user.email,
            role = user.role,
            societyId = user.society?.id,
            complexId = user.complex?.id,
            flatId = user.flat?.id,
            residentType = user.residentType,
            isActive = user.isActive,
        )
    }
}
