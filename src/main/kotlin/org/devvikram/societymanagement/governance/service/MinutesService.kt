package org.devvikram.societymanagement.governance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.storage.DocumentFormat
import org.devvikram.societymanagement.common.storage.Photo
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.governance.model.Meeting
import org.devvikram.societymanagement.governance.model.MeetingMinutes
import org.devvikram.societymanagement.governance.model.MeetingStatus
import org.devvikram.societymanagement.governance.model.MinutesAddendum
import org.devvikram.societymanagement.governance.model.MinutesStatus
import org.devvikram.societymanagement.governance.repository.AddendumRepository
import org.devvikram.societymanagement.governance.repository.MeetingRepository
import org.devvikram.societymanagement.governance.repository.MinutesRepository
import org.devvikram.societymanagement.notice.service.NoticeService
import org.devvikram.societymanagement.notice.service.PostNoticeCommand
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// What the caller holds in the meeting's society, for minutes.
data class MinutesActor(
    val userId: Long,
    // GOVERNANCE.MINUTES.CREATE (SECRETARY and above): drafts, submits, adds addenda, announces.
    val drafts: Boolean,
    // GOVERNANCE.MINUTES.APPROVE (CHAIRMAN and above): sends back or approves, never their own draft.
    val approves: Boolean,
)

data class MinutesView(val minutes: MeetingMinutes, val addenda: List<MinutesAddendum>)

// Minutes of a held meeting: drafted, submitted, approved by someone other than the drafter, published
// to whoever may see the meeting; corrections afterwards are addenda.
@Service
class MinutesService(
    private val minutesRepository: MinutesRepository,
    private val addendumRepository: AddendumRepository,
    private val meetingRepository: MeetingRepository,
    private val storage: PhotoStorage,
    private val noticeService: NoticeService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${app.report.zone}") private val zone: ZoneId,
) {
    private fun now(): Instant = clock.instant()

    // Published minutes to anyone who sees the meeting (the controller checks that); drafts and pending
    // minutes only to those who draft or approve them. Null when there are none (for this caller).
    fun view(meetingId: Long, actor: MinutesActor): MinutesView? {
        val minutes = minutesRepository.findByMeeting(meetingId) ?: return null
        if (minutes.status != MinutesStatus.PUBLISHED && !actor.drafts && !actor.approves) return null
        return MinutesView(minutes, addendumRepository.findByMinutes(minutes.id!!))
    }

    // Writes the draft (starting the minutes if there are none). Only for a held meeting, while a draft.
    @Transactional
    fun save(meetingId: Long, body: String, actor: MinutesActor): Long {
        requireDrafter(actor)
        val meeting = lockMeeting(meetingId)
        if (meeting.status != MeetingStatus.HELD) throw ConflictException("Minutes are written once the meeting is marked held")
        val text = body.trim()
        require(text.length <= 100_000) { "Minutes can be at most 100,000 characters" }
        val existing = minutesRepository.findByMeeting(meetingId)
        if (existing == null) {
            return minutesRepository.save(MeetingMinutes(meetingId, text, actor.userId, now())).id!!
        }
        requireDraft(existing)
        existing.body = text
        existing.updatedAt = now()
        minutesRepository.save(existing)
        return existing.id!!
    }

    @Transactional
    fun setAttachment(meetingId: Long, data: ByteArray, filename: String?, actor: MinutesActor) {
        requireDrafter(actor)
        require(data.isNotEmpty()) { "The file is empty" }
        require(data.size <= DocumentFormat.MAX_BYTES) { "Files can be at most 5 MB" }
        val type = DocumentFormat.detect(data) ?: throw IllegalArgumentException("Attach a PDF, JPEG, PNG or WebP file")
        lockMeeting(meetingId)
        val minutes = draftOf(meetingId)
        val old = minutes.attachmentKey
        minutes.attachmentKey = storage.save(data, type)
        minutes.attachmentName = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.take(255)?.ifEmpty { null }
        minutes.attachmentType = type
        minutes.attachmentSize = data.size
        minutes.updatedAt = now()
        minutesRepository.save(minutes)
        old?.let(storage::delete)
    }

    @Transactional
    fun removeAttachment(meetingId: Long, actor: MinutesActor) {
        requireDrafter(actor)
        lockMeeting(meetingId)
        val minutes = draftOf(meetingId)
        val old = minutes.attachmentKey ?: return
        minutes.attachmentKey = null
        minutes.attachmentName = null
        minutes.attachmentType = null
        minutes.attachmentSize = null
        minutes.updatedAt = now()
        minutesRepository.save(minutes)
        storage.delete(old)
    }

    fun attachment(meetingId: Long, actor: MinutesActor): Pair<MeetingMinutes, Photo> {
        val minutes = view(meetingId, actor)?.minutes ?: throw NoSuchElementException("No minutes for meeting $meetingId")
        val key = minutes.attachmentKey ?: throw NoSuchElementException("The minutes have no attachment")
        return minutes to (storage.load(key) ?: throw NoSuchElementException("The attachment is missing"))
    }

    @Transactional
    fun submit(meetingId: Long, actor: MinutesActor) {
        requireDrafter(actor)
        val meeting = lockMeeting(meetingId)
        val minutes = draftOf(meetingId)
        require(minutes.body.isNotBlank() || minutes.attachmentKey != null) { "Write the minutes or attach them before submitting" }
        minutes.status = MinutesStatus.PENDING
        minutes.submittedAt = now()
        minutesRepository.save(minutes)
        events.publishEvent(MinutesSubmitted(meeting.id!!, meeting.societyId, meeting.title, minutes.draftedBy))
    }

    @Transactional
    fun sendBack(meetingId: Long, reason: String, actor: MinutesActor) {
        requireApprover(actor)
        val clean = reason.trim()
        require(clean.isNotEmpty()) { "Say what should change" }
        require(clean.length <= 500) { "The reason can be at most 500 characters" }
        val meeting = lockMeeting(meetingId)
        val minutes = pendingOf(meetingId)
        minutes.status = MinutesStatus.DRAFT
        minutes.submittedAt = null
        minutes.reviewedBy = actor.userId
        minutes.reviewedAt = now()
        minutes.reviewReason = clean
        minutesRepository.save(minutes)
        events.publishEvent(MinutesSentBack(meeting.id!!, meeting.title, minutes.draftedBy, clean))
    }

    // Publishes the minutes; the meeting becomes MINUTES_PUBLISHED. Never the drafter's own.
    @Transactional
    fun approve(meetingId: Long, actor: MinutesActor) {
        requireApprover(actor)
        val meeting = lockMeeting(meetingId)
        val minutes = pendingOf(meetingId)
        if (minutes.draftedBy == actor.userId) throw AccessDeniedException("Someone other than the drafter must approve the minutes")
        minutes.status = MinutesStatus.PUBLISHED
        minutes.approvedBy = actor.userId
        minutes.reviewedBy = actor.userId
        minutes.reviewedAt = now()
        minutes.reviewReason = null
        minutes.publishedAt = now()
        minutesRepository.save(minutes)
        meeting.status = MeetingStatus.MINUTES_PUBLISHED
        meetingRepository.save(meeting)
        events.publishEvent(MinutesPublished(meeting.id!!, meeting.societyId, meeting.kind, meeting.title, actor.userId))
    }

    // A dated correction to published minutes.
    @Transactional
    fun addAddendum(meetingId: Long, text: String, actor: MinutesActor): MinutesAddendum {
        if (!actor.drafts && !actor.approves) throw AccessDeniedException("Only the secretary or an approver adds a correction")
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Write the correction" }
        require(clean.length <= 4000) { "A correction can be at most 4,000 characters" }
        lockMeeting(meetingId)
        val minutes = minutesRepository.findByMeeting(meetingId) ?: throw NoSuchElementException("No minutes for meeting $meetingId")
        if (minutes.status != MinutesStatus.PUBLISHED) throw ConflictException("Corrections are added to published minutes; edit the draft instead")
        return addendumRepository.save(MinutesAddendum(minutes.id!!, clean, actor.userId, now()))
    }

    // Posts a notice that the minutes of a general meeting are published (once).
    @Transactional
    fun announce(meetingId: Long, actor: MinutesActor): Long {
        requireDrafter(actor)
        val meeting = lockMeeting(meetingId)
        if (!meeting.kind.general) throw ConflictException("Committee meeting minutes stay with the committee; they aren't announced")
        val minutes = minutesRepository.findByMeeting(meetingId)
        if (minutes?.status != MinutesStatus.PUBLISHED) throw ConflictException("Publish the minutes before announcing them")
        minutes.announcedNoticeId?.let { throw ConflictException("The minutes have already been announced") }
        val day = DateTimeFormatter.ofPattern("d MMMM yyyy").format(meeting.scheduledAt.atZone(zone))
        val noticeId = noticeService.post(
            PostNoticeCommand(
                societyId = meeting.societyId,
                title = "Minutes published: ${meeting.title}".take(255),
                description = "The minutes of the ${meeting.title} held on $day are published. Open Meetings in the app to read them.",
            ),
            actor.userId,
        )
        minutes.announcedNoticeId = noticeId
        minutesRepository.save(minutes)
        return noticeId
    }

    // ---- Helpers ----

    private fun lockMeeting(meetingId: Long): Meeting =
        meetingRepository.lock(meetingId) ?: throw NoSuchElementException("Meeting $meetingId not found")

    private fun draftOf(meetingId: Long): MeetingMinutes {
        val minutes = minutesRepository.findByMeeting(meetingId) ?: throw NoSuchElementException("No minutes for meeting $meetingId")
        requireDraft(minutes)
        return minutes
    }

    private fun pendingOf(meetingId: Long): MeetingMinutes {
        val minutes = minutesRepository.findByMeeting(meetingId) ?: throw NoSuchElementException("No minutes for meeting $meetingId")
        if (minutes.status != MinutesStatus.PENDING) throw ConflictException("The minutes aren't waiting for approval")
        return minutes
    }

    private fun requireDraft(minutes: MeetingMinutes) {
        when (minutes.status) {
            MinutesStatus.DRAFT -> Unit
            MinutesStatus.PENDING -> throw ConflictException("The minutes are waiting for approval")
            MinutesStatus.PUBLISHED -> throw ConflictException("Published minutes are never changed; add a correction instead")
        }
    }

    private fun requireDrafter(actor: MinutesActor) {
        if (!actor.drafts) throw AccessDeniedException("Only the secretary and above write minutes")
    }

    private fun requireApprover(actor: MinutesActor) {
        if (!actor.approves) throw AccessDeniedException("Only the chairman and above approve minutes")
    }
}
