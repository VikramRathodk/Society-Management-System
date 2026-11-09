package org.devvikram.societymanagement.society.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class SocietySettingId(val societyId: Long = 0, val key: String = "") : Serializable

// One setting a society changed from its default. The value is stored as text in the form its
// definition produces (see SettingDefinition.normalize); updatedBy is a user id.
@Entity
@Table(name = "society_setting")
@IdClass(SocietySettingId::class)
class SocietySetting(
    @Id
    @Column(name = "society_id")
    var societyId: Long,

    @Id
    @Column(name = "setting_key")
    var key: String,

    @Column(name = "setting_value", nullable = false)
    var value: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "updated_by")
    var updatedBy: Long?,
)
