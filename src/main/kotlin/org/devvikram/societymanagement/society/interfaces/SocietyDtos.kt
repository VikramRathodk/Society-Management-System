package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.domain.Society
import java.util.UUID

data class CreateSocietyRequest(
    @field:NotBlank val name: String,
    val address: String? = null,
    val complexId: UUID? = null,
)

data class UpdateSocietyRequest(
    val name: String? = null,
    val address: String? = null,
)

data class SocietyResponse(
    val id: UUID,
    val name: String,
    val address: String?,
    val complexId: UUID?,
) {
    companion object {
        fun from(society: Society) = SocietyResponse(society.id!!, society.name, society.address, society.complex?.id)
    }
}
