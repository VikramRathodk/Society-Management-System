package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.domain.Flat
import java.util.UUID

data class CreateFlatRequest(@field:NotBlank val flatNumber: String)

data class UpdateFlatRequest(@field:NotBlank val flatNumber: String)

data class FlatResponse(
    val id: UUID,
    val wingId: UUID,
    val flatNumber: String,
) {
    companion object {
        fun from(flat: Flat) = FlatResponse(flat.id!!, flat.wing.id!!, flat.flatNumber)
    }
}
