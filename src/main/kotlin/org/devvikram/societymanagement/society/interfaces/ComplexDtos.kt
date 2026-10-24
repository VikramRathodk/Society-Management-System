package org.devvikram.societymanagement.society.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.society.domain.Complex

data class CreateComplexRequest(
    @field:NotBlank val name: String,
    val address: String? = null,
)

data class UpdateComplexRequest(
    val name: String? = null,
    val address: String? = null,
)

data class ComplexResponse(
    val id: Long,
    val name: String,
    val address: String?,
) {
    companion object {
        fun from(complex: Complex) = ComplexResponse(complex.id!!, complex.name, complex.address)
    }
}
