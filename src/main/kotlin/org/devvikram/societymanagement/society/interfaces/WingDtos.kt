package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.domain.Wing
import java.util.UUID

data class CreateWingRequest(@field:NotBlank val name: String)

data class RenameWingRequest(@field:NotBlank val name: String)

data class WingResponse(
    val id: UUID,
    val societyId: UUID,
    val name: String,
) {
    companion object {
        fun from(wing: Wing) = WingResponse(wing.id!!, wing.society.id!!, wing.name)
    }
}
