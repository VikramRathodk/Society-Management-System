package org.devvikram.societymanagement.society.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.model.Flat
import java.math.BigDecimal

data class CreateFlatRequest(
    @field:NotBlank val flatNumber: String,
    @field:DecimalMin("0.01") @field:Digits(integer = 6, fraction = 2) val areaSqft: BigDecimal? = null,
)

data class UpdateFlatRequest(
    @field:NotBlank val flatNumber: String,
    @field:DecimalMin("0.01") @field:Digits(integer = 6, fraction = 2) val areaSqft: BigDecimal? = null,
)

data class FlatResponse(
    val id: Long,
    val wingId: Long,
    val flatNumber: String,
    val areaSqft: BigDecimal?,
) {
    companion object {
        fun from(flat: Flat) = FlatResponse(flat.id!!, flat.wing.id!!, flat.flatNumber, flat.areaSqft)
    }
}
