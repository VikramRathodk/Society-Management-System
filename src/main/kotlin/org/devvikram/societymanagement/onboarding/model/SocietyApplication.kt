package org.devvikram.societymanagement.onboarding.model

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
import jakarta.persistence.Table
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.User
import java.time.Instant

enum class ApplicationStatus { PENDING, APPROVED, REJECTED }

// A society registered from the public site, waiting for the super admin. Nothing else exists until it is
// approved: approving creates the society, the applicant's account (unless they applied signed in) and
// their SECRETARY role.
@Entity
@Table(name = "society_application")
class SocietyApplication(
    @Column(name = "society_name", nullable = false)
    var societyName: String,

    @Column(nullable = false)
    var city: String,

    var address: String?,

    @Column(name = "approx_flats")
    var approxFlats: Int?,

    @Column(name = "applicant_name", nullable = false)
    var applicantName: String,

    @Column(name = "applicant_phone", nullable = false)
    var applicantPhone: String,

    @Column(name = "applicant_email")
    var applicantEmail: String?,

    // The password the applicant chose, hashed; null when they applied with their existing account.
    @Column(name = "password_hash")
    var passwordHash: String?,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicant_user_id")
    var applicantUser: User?,

    var message: String?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "created_ip")
    var createdIp: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ApplicationStatus = ApplicationStatus.PENDING,

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    var reviewedBy: User? = null,

    var reason: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "society_id")
    var society: Society? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
