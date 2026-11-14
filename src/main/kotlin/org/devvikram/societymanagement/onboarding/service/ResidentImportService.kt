package org.devvikram.societymanagement.onboarding.service

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.common.csv.CsvReader
import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.invitation.repository.InvitationRepository
import org.devvikram.societymanagement.invitation.service.CreateInvitationCommand
import org.devvikram.societymanagement.invitation.service.InvitationService
import org.devvikram.societymanagement.onboarding.model.ResidentImport
import org.devvikram.societymanagement.onboarding.model.ResidentImportRow
import org.devvikram.societymanagement.onboarding.model.ResidentImportStatus
import org.devvikram.societymanagement.onboarding.repository.ResidentImportRepository
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.devvikram.societymanagement.user.service.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class ImportRowView(
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
)

data class ImportView(
    val id: Long,
    val fileName: String?,
    val status: ResidentImportStatus,
    val uploadedAt: Instant,
    val uploadedByName: String,
    val sentAt: Instant?,
    val rows: List<ImportRowView>,
) {
    val ready get() = rows.count { it.error == null && it.invitationId == null }
    val withErrors get() = rows.count { it.error != null && it.invitationId == null }
    val sent get() = rows.count { it.invitationId != null }
}

data class UploadedSheet(val import: ImportView, val header: List<String>, val columns: SheetColumns)

// A row that went out: the link exists only now (only the token's hash is kept), so this is the moment to
// share it (links CSV, QR cards). A lost link is replaced with the invitation's resend.
data class SentInvitation(val row: Int, val flatLabel: String, val name: String, val phone: String, val invitationId: Long, val token: String)

data class SendResult(val sent: List<SentInvitation>, val skipped: List<ImportRowView>)

// Residents from a sheet (setup wizard step 3). Rows are staged and checked again whenever they are read or
// sent, since flats, residents and settings change meanwhile. Sending runs each good row through the
// invitation module (rank rules, per-flat limit, expiry); every check that could refuse an invitation is
// made here first, so one bad row is skipped instead of failing the batch.
@Service
class ResidentImportService(
    private val repository: ResidentImportRepository,
    private val societyService: SocietyService,
    private val flatRepository: FlatRepository,
    private val userService: UserService,
    private val userRoleRepository: UserRoleRepository,
    private val invitationRepository: InvitationRepository,
    private val invitationService: InvitationService,
    private val settings: SocietySettingsService,
    private val clock: Clock,
) {
    @Transactional
    fun upload(societyId: Long, fileName: String?, data: ByteArray, given: SheetColumns?, userId: Long): UploadedSheet {
        val society = societyService.getById(societyId)
        require(data.isNotEmpty()) { "The file is empty" }
        require(data.size <= MAX_BYTES) { "A residents sheet can be at most 1 MB" }
        val text = CsvReader.decode(data)
        val records = CsvReader.records(text, CsvReader.detectDelimiter(text))
        val header = records.firstOrNull()?.map { it.trim() }.orEmpty()
        val columns = given?.also { it.validate() } ?: ResidentSheet.guess(header)
            ?: throw IllegalArgumentException("Couldn't tell which columns hold the flat, name and phone number. Choose them and upload again.")
        val sheetRows = ResidentSheet.rows(records, columns)

        // A new upload replaces the sheet that hasn't been sent.
        repository.findOpen(societyId)?.let { repository.delete(it); repository.flush() }
        val staged = ResidentImport(
            society = society,
            fileName = fileName?.take(255),
            uploadedBy = userService.getById(userId),
            uploadedAt = clock.instant(),
        )
        sheetRows.forEach {
            staged.rows += ResidentImportRow(
                residentImport = staged,
                rowNumber = it.row,
                wingText = it.wing?.take(100),
                flatText = it.flat?.take(100),
                name = it.name?.take(255),
                phoneText = it.phone?.take(50),
                typeText = it.type?.take(50),
            )
        }
        check(societyId, staged)
        return UploadedSheet(view(repository.save(staged)), header, columns)
    }

    @Transactional
    fun current(societyId: Long): ImportView? {
        societyService.getById(societyId)
        val staged = repository.findOpen(societyId) ?: return null
        check(societyId, staged)
        return view(staged)
    }

    @Transactional
    fun removeRow(societyId: Long, rowId: Long): ImportView {
        val staged = repository.findOpen(societyId) ?: throw NoSuchElementException("There is no residents sheet waiting to be sent")
        val row = staged.rows.firstOrNull { it.id == rowId } ?: throw NoSuchElementException("Row $rowId not found in this sheet")
        staged.rows.remove(row)
        check(societyId, staged)
        return view(repository.save(staged))
    }

    @Transactional
    fun discard(societyId: Long) {
        val staged = repository.findOpen(societyId) ?: throw NoSuchElementException("There is no residents sheet waiting to be sent")
        repository.delete(staged)
    }

    // Rows ready to send in the open sheet (for the checklist); as last checked.
    @Transactional(readOnly = true)
    fun readyCount(societyId: Long): Int =
        repository.findOpen(societyId)?.rows?.count { it.error == null && it.invitationId == null } ?: 0

    // Sending is for a live society; while it is being set up the sheet goes out when it goes live.
    @Transactional
    fun send(societyId: Long, actor: Access): SendResult {
        val society = societyService.getById(societyId)
        if (society.isSetup) {
            throw ConflictException("${society.name} is still being set up; the residents sheet is sent when it goes live")
        }
        return sendOpen(societyId, actor)
            ?: throw NoSuchElementException("There is no residents sheet waiting to be sent")
    }

    // At go-live (the society is already live by then). Null when nothing is staged.
    @Transactional
    fun sendOpen(societyId: Long, actor: Access): SendResult? {
        val staged = repository.findOpen(societyId) ?: return null
        check(societyId, staged)
        val now = clock.instant()
        val sent = staged.rows.filter { it.error == null && it.invitationId == null }.map { row ->
            val flat = row.flat!!
            val issued = invitationService.create(
                CreateInvitationCommand(societyId, row.phone!!, row.name!!, RESIDENT_ROLE, flat.id, row.residentType),
                actor,
            )
            row.invitationId = issued.invitation.id
            SentInvitation(row.rowNumber, label(flat), row.name!!, row.phone!!, issued.invitation.id, issued.token)
        }
        staged.status = ResidentImportStatus.SENT
        staged.sentAt = now
        staged.sentBy = userService.getById(actor.userId)
        repository.save(staged)
        return SendResult(sent, view(staged).rows.filter { it.invitationId == null })
    }

    // Works out each unsent row's flat, phone and type and why it can't be sent, if it can't.
    private fun check(societyId: Long, staged: ResidentImport) {
        val now = clock.instant()
        val flats = flatRepository.findBySocietyId(societyId)
        val byWingAndNumber = flats.associateBy { it.wing.name.trim().lowercase() to it.flatNumber.trim().lowercase() }
        val byNumber = flats.groupBy { it.flatNumber.trim().lowercase() }
        val max = settings.integer(societyId, SocietySettingKeys.MAX_RESIDENTS_PER_FLAT)
        // Per flat: the phones of its active residents, and people already counted against its limit.
        val residentPhones = mutableMapOf<Long, Set<String>>()
        val taken = mutableMapOf<Long, Long>()
        fun phonesOf(flat: Flat) = residentPhones.getOrPut(flat.id!!) {
            userRoleRepository.findByFlatId(flat.id!!).filter { it.user.isActive }.map { it.user.phone }.toSet()
        }
        fun takenIn(flat: Flat) = taken.getOrPut(flat.id!!) {
            phonesOf(flat).size + invitationRepository.countOpenForFlat(flat.id!!, now)
        }
        val seen = mutableMapOf<Pair<Long, String>, Int>()

        staged.rows.sortedBy { it.rowNumber }.filter { it.invitationId == null }.forEach { row ->
            val (flat, flatError) = resolveFlat(row, byWingAndNumber, byNumber)
            row.flat = flat
            row.phone = ResidentSheet.phone(row.phoneText)
            row.residentType = ResidentSheet.residentType(row.typeText)
            row.error = when {
                row.name.isNullOrBlank() -> "Name is missing"
                row.phoneText == null -> "Phone number is missing"
                row.phone == null -> "${row.phoneText} isn't a phone number"
                row.residentType == null -> "Type \"${row.typeText}\" should be owner, tenant or family"
                flat == null -> flatError
                else -> null
            }
            if (row.error != null || flat == null) return@forEach
            val phone = row.phone!!
            val key = flat.id!! to phone
            row.error = when {
                key in seen -> "Same person and flat as row ${seen[key]}"
                phone in phonesOf(flat) -> "Already lives in ${label(flat)}"
                userService.getByPhone(phone)?.isActive == false -> "The account for this phone number is deactivated"
                max > 0 && takenIn(flat) >= max -> "${label(flat)} is full ($max people allowed per flat)"
                else -> null
            }
            if (row.error == null) {
                seen[key] = row.rowNumber
                taken[flat.id!!] = takenIn(flat) + 1
            }
        }
    }

    private fun resolveFlat(
        row: ResidentImportRow,
        byWingAndNumber: Map<Pair<String, String>, Flat>,
        byNumber: Map<String, List<Flat>>,
    ): Pair<Flat?, String?> {
        val text = row.flatText ?: return null to "Flat is missing"
        row.wingText?.let { wing ->
            val flat = byWingAndNumber[wing.trim().lowercase() to text.trim().lowercase()]
            return flat to (if (flat == null) "No flat $wing-$text in this society" else null)
        }
        val matches = byNumber[text.trim().lowercase()].orEmpty()
        if (matches.size == 1) return matches.single() to null
        val (wing, number) = ResidentSheet.splitFlat(text)
        if (wing != null) {
            byWingAndNumber[wing.lowercase() to number.lowercase()]?.let { return it to null }
        }
        return null to if (matches.size > 1) "Flat $text is in more than one wing; add the wing" else "No flat $text in this society"
    }

    private fun view(staged: ResidentImport) = ImportView(
        id = staged.id!!,
        fileName = staged.fileName,
        status = staged.status,
        uploadedAt = staged.uploadedAt,
        uploadedByName = staged.uploadedBy.name,
        sentAt = staged.sentAt,
        rows = staged.rows.sortedBy { it.rowNumber }.map {
            ImportRowView(
                id = it.id!!, row = it.rowNumber, wingText = it.wingText, flatText = it.flatText, name = it.name,
                phoneText = it.phoneText, typeText = it.typeText, flatId = it.flat?.id, flatLabel = it.flat?.let(::label),
                phone = it.phone, residentType = it.residentType, error = it.error, invitationId = it.invitationId,
            )
        },
    )

    private fun label(flat: Flat) = "${flat.wing.name}-${flat.flatNumber}"

    companion object {
        const val RESIDENT_ROLE = "RESIDENT"
        const val MAX_BYTES = 1_048_576
    }
}
