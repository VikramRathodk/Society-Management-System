package org.devvikram.societymanagement.society.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

@Entity
@Table(name = "flat", uniqueConstraints = [UniqueConstraint(columnNames = ["wing_id", "flat_number"])])
class Flat(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wing_id", nullable = false)
    var wing: Wing,

    @Column(name = "flat_number", nullable = false)
    var flatNumber: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
