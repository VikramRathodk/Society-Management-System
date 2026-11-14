package org.devvikram.societymanagement.onboarding.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.onboarding.model.ResidentImportStatus
import org.devvikram.societymanagement.onboarding.service.ImportRowView
import org.devvikram.societymanagement.onboarding.service.ImportView
import org.devvikram.societymanagement.onboarding.service.PlannedWing
import org.devvikram.societymanagement.onboarding.service.SendResult
import org.devvikram.societymanagement.onboarding.service.SentInvitation
import org.devvikram.societymanagement.onboarding.service.SheetColumns
import org.devvikram.societymanagement.onboarding.service.UploadedSheet
import org.devvikram.societymanagement.onboarding.service.WentLive
import org.devvikram.societymanagement.onboarding.service.WingPattern
import org.devvikram.societymanagement.user.model.ResidentType
import java.time.Instant

// ---- Wings & flats from a pattern ----

data class WingPatternRequest(
    @field:Size(max = 50) val wing: String,
    val floorsFrom: Int,
    val floorsTo: Int,
    val flatsPerFloor: Int,
    @field:Size(max = 50) val numbering: String? = null,
    @field:Size(max = 500) val skip: List<String> = emptyList(),
    @field:Size(max = 100) val extraUnits: List<String> = emptyList(),
) {
    fun toPattern() = WingPattern(
        wing, floorsFrom, floorsTo, flatsPerFloor, numbering ?: WingPattern.DEFAULT_NUMBERING, skip, extraUnits,
    )
}

data class StructureRequest(@field:NotEmpty @field:Size(max = 50) @field:Valid val wings: List<WingPatternRequest>) {
    fun toPatterns() = wings.map { it.toPattern() }
}

// For generate, `exists: false` marks what was just created.
data class PlannedWingResponse(
    val name: String,
    val exists: Boolean,
    val newFlats: Int,
    val existingFlats: Int,
    val flats: List<PlannedFlatResponse>,
) {
    data class PlannedFlatResponse(val number: String, val exists: Boolean)

    companion object {
        fun from(w: PlannedWing) = PlannedWingResponse(
            w.name, w.exists, w.newFlats, w.existingFlats, w.flats.map { PlannedFlatResponse(it.number, it.exists) },
        )
    }
}

// ---- Residents from a sheet ----

data class ImportRowResponse(
    val id: Long,
    val row: Int,
    val wingText: String?,
    val flatText: String?,
    val name: String?,
    val phoneText: String?,
    val typeText: String?,
    val flatId: Long?,
    val flatLabel: String?,
    val phone: String?,
    val residentType: ResidentType?,
    val error: String?,
    val invitationId: Long?,
) {
    companion object {
        fun from(r: ImportRowView) = ImportRowResponse(
            r.id, r.row, r.wingText, r.flatText, r.name, r.phoneText, r.typeText, r.flatId, r.flatLabel, r.phone,
            r.residentType, r.error, r.invitationId,
        )
    }
}

data class ResidentImportResponse(
    val id: Long,
    val fileName: String?,
    val status: ResidentImportStatus,
    val uploadedAt: Instant,
    val uploadedByName: String,
    val sentAt: Instant?,
    val total: Int,
    val ready: Int,
    val withErrors: Int,
    val sent: Int,
    val rows: List<ImportRowResponse>,
) {
    companion object {
        fun from(v: ImportView) = ResidentImportResponse(
            v.id, v.fileName, v.status, v.uploadedAt, v.uploadedByName, v.sentAt, v.rows.size, v.ready, v.withErrors,
            v.sent, v.rows.map(ImportRowResponse::from),
        )
    }
}

// Columns are 1-based, as read (guessed from the header unless chosen).
data class ColumnsResponse(val flat: Int, val name: Int, val phone: Int, val wing: Int?, val type: Int?) {
    companion object {
        fun from(c: SheetColumns) = ColumnsResponse(c.flat, c.name, c.phone, c.wing, c.type)
    }
}

data class UploadedSheetResponse(val sheet: ResidentImportResponse, val header: List<String>, val columns: ColumnsResponse) {
    companion object {
        fun from(u: UploadedSheet) =
            UploadedSheetResponse(ResidentImportResponse.from(u.import), u.header, ColumnsResponse.from(u.columns))
    }
}

data class SentInvitationResponse(
    val row: Int,
    val flatLabel: String,
    val name: String,
    val phone: String,
    val invitationId: Long,
    val token: String,
    val inviteUrl: String,
) {
    companion object {
        fun from(s: SentInvitation, webBaseUrl: String) = SentInvitationResponse(
            s.row, s.flatLabel, s.name, s.phone, s.invitationId, s.token, "${webBaseUrl.trimEnd('/')}/invite/${s.token}",
        )
    }
}

// The links exist only in this response: the web turns them into a links CSV and printable QR cards.
data class SendResultResponse(val sent: List<SentInvitationResponse>, val skipped: List<ImportRowResponse>) {
    companion object {
        fun from(r: SendResult, webBaseUrl: String) =
            SendResultResponse(r.sent.map { SentInvitationResponse.from(it, webBaseUrl) }, r.skipped.map(ImportRowResponse::from))
    }
}

// residentInvitations is null when no residents sheet was staged.
data class GoLiveResponse(val onboarding: OnboardingResponse, val residentInvitations: SendResultResponse?) {
    companion object {
        fun from(w: WentLive, webBaseUrl: String) = GoLiveResponse(
            OnboardingResponse.from(w.checklist), w.residentInvitations?.let { SendResultResponse.from(it, webBaseUrl) },
        )
    }
}
