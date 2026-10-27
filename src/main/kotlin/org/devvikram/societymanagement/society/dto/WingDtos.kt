package org.devvikram.societymanagement.society.dto

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.model.Wing

data class CreateWingRequest(@field:NotBlank val name: String)

data class RenameWingRequest(@field:NotBlank val name: String)

data class WingResponse(
    val id: Long,
    val societyId: Long,
    val name: String,
) {
    companion object {
        fun from(wing: Wing) = WingResponse(wing.id!!, wing.society.id!!, wing.name)
    }
}
