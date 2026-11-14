package org.devvikram.societymanagement.society.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

// SETUP: being set up by its committee; nobody holds a flat role yet. LIVE: in use by its residents.
enum class SocietyStatus { SETUP, LIVE }

// How the society came onto the platform.
enum class SocietySource { PLATFORM, SELF_SIGNUP }

@Entity
@Table(name = "society")
class Society(
    @Column(nullable = false)
    var name: String,

    // The street address; city, state and PIN are separate fields.
    var address: String? = null,

    // nullable: standalone society if null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complex_id")
    var complex: Complex? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SocietyStatus = SocietyStatus.LIVE,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var source: SocietySource = SocietySource.PLATFORM,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @Column(name = "went_live_at")
    var wentLiveAt: Instant? = null

    // A user id (the society module doesn't depend on `user`).
    @Column(name = "went_live_by")
    var wentLiveBy: Long? = null

    // ---- Profile ----

    @Column(name = "registration_number")
    var registrationNumber: String? = null

    var city: String? = null

    var state: String? = null

    var pincode: String? = null

    @Column(name = "contact_phone")
    var contactPhone: String? = null

    @Column(name = "contact_email")
    var contactEmail: String? = null

    // Key in PhotoStorage.
    @Column(name = "logo_key")
    var logoKey: String? = null

    var notes: String? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }

    val isSetup: Boolean get() = status == SocietyStatus.SETUP

    // The profile fields a society needs before it can go live.
    fun missingProfile(): List<String> = buildList {
        if (address.isNullOrBlank()) add("address")
        if (city.isNullOrBlank()) add("city")
        if (state.isNullOrBlank()) add("state")
        if (pincode.isNullOrBlank()) add("pincode")
        if (contactPhone.isNullOrBlank()) add("contactPhone")
    }
}
