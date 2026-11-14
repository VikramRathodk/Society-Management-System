package org.devvikram.societymanagement.onboarding.model

import jakarta.persistence.CascadeType
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
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.model.User
import java.time.Instant

enum class ResidentImportStatus { OPEN, SENT }

// A residents sheet staged for the society: OPEN until its rows are sent as invitations.
@Entity
@Table(name = "resident_import")
class ResidentImport(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @Column(name = "file_name")
    var fileName: String?,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    var uploadedBy: User,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ResidentImportStatus = ResidentImportStatus.OPEN,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sent_by")
    var sentBy: User? = null,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @OneToMany(mappedBy = "residentImport", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("rowNumber")
    var rows: MutableList<ResidentImportRow> = mutableListOf(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)

// One line of the sheet: what it said, and what it was understood as. `error` is why it can't be sent.
@Entity
@Table(name = "resident_import_row")
class ResidentImportRow(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id", nullable = false)
    var residentImport: ResidentImport,

    @Column(name = "row_number", nullable = false)
    var rowNumber: Int,

    @Column(name = "wing_text") var wingText: String?,
    @Column(name = "flat_text") var flatText: String?,
    var name: String?,
    @Column(name = "phone_text") var phoneText: String?,
    @Column(name = "type_text") var typeText: String?,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flat_id")
    var flat: Flat? = null,

    var phone: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "resident_type")
    var residentType: ResidentType? = null,

    var error: String? = null,

    @Column(name = "invitation_id")
    var invitationId: Long? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
