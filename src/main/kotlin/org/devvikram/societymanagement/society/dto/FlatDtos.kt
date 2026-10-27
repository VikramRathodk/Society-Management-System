package org.devvikram.societymanagement.society.dto

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.model.Flat

data class CreateFlatRequest(@field:NotBlank val flatNumber: String)

data class UpdateFlatRequest(@field:NotBlank val flatNumber: String)

data class FlatResponse(
    val id: Long,
    val wingId: Long,
    val flatNumber: String,
) {
    companion object {
        fun from(flat: Flat) = FlatResponse(flat.id!!, flat.wing.id!!, flat.flatNumber)
    }
}
