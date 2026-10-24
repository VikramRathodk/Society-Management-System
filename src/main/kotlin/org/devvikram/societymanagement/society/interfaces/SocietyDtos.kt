package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.domain.Society

data class CreateSocietyRequest(
    @field:NotBlank val name: String,
    val address: String? = null,
    val complexId: Long? = null,
)

data class UpdateSocietyRequest(
    val name: String? = null,
    val address: String? = null,
)

data class SocietyResponse(
    val id: Long,
    val name: String,
    val address: String?,
    val complexId: Long?,
) {
    companion object {
        fun from(society: Society) = SocietyResponse(society.id!!, society.name, society.address, society.complex?.id)
    }
}
