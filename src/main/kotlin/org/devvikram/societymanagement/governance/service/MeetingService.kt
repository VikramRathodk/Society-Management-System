package org.devvikram.societymanagement.governance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.governance.model.AgendaItem
import org.devvikram.societymanagement.governance.model.Meeting
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.governance.model.MeetingRsvp
import org.devvikram.societymanagement.governance.model.MeetingStatus
import org.devvikram.societymanagement.governance.model.RsvpResponse
import org.devvikram.societymanagement.governance.repository.AgendaRepository
import org.devvikram.societymanagement.governance.repository.MeetingRepository
import org.devvikram.societymanagement.governance.repository.RsvpRepository
import org.devvikram.societymanagement.policy.service.PolicyService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// What the caller holds in the meeting's society. The controller checks the endpoint's permission; the
// service applies the rules that depend on the meeting (its kind, its status).
data class MeetingViewer(
    val userId: Long,
    // GOVERNANCE.MEETING.VIEW: general meetings (AGM, SGM).
    val seesGeneral: Boolean,
    // GOVERNANCE.COMMITTEE.VIEW through a society-wide role: committee meetings.
    val seesCommittee: Boolean,
    // GOVERNANCE.MEETING.CREATE: prepares meetings and sees drafts.
    val organises: Boolean,
) {
    // Invited to (and may RSVP for) a meeting of this kind.
    fun invitedTo(kind: MeetingKind) = if (kind.general) seesGeneral else seesCommittee

    fun sees(meeting: Meeting) =
        if (meeting.status == MeetingStatus.DRAFT) organises else organises || invitedTo(meeting.kind)
}

data class AgendaInput(
    val title: String,
    val description: String? = null,
    val resolutionText: String? = null,
    val policyId: Long? = null,
)

// The whole editable meeting (replaced together while it is a draft).
data class MeetingInput(
    val kind: MeetingKind,
    val title: String,
    val description: String? = null,
    val scheduledAt: Instant,
    val place: String? = null,
    val onlineLink: String? = null,
    val agenda: List<AgendaInput> = emptyList(),
)

enum class MeetingView { UPCOMING, PAST, DRAFTS }

data class RsvpSummary(val yes: Int, val no: Int, val maybe: Int, val responses: List<MeetingRsvp>)

// A meeting with its agenda, the caller's RSVP and the notice period that applies to it.
data class MeetingDetails(
    val meeting: Meeting,
    val agenda: List<AgendaItem>,
    val yourRsvp: RsvpResponse?,
    val canRsvp: Boolean,
    val noticeDays: Long,
)

// Meetings, their agendas and notices, RSVPs, cancelling and adjourning, and the reminder run.
@Service
class MeetingService(
    private val meetingRepository: MeetingRepository,
    private val agendaRepository: AgendaRepository,
    private val rsvpRepository: RsvpRepository,
    private val societyService: SocietyService,
    private val settings: SocietySettingsService,
    private val userRepository: UserRepository,
    private val policyService: PolicyService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${app.report.zone}") private val zone: ZoneId,
) {
    private fun now(): Instant = clock.instant()

    // ---- Preparing ----

    @Transactional
    fun create(societyId: Long, input: MeetingInput, userId: Long): Long {
        societyService.getById(societyId)
        validate(input)
        checkPolicies(societyId, input.agenda)
        val meeting = meetingRepository.save(
            Meeting(
                societyId = societyId,
                kind = input.kind,
                title = input.title.trim(),
                description = clean(input.description),
                scheduledAt = input.scheduledAt,
                place = clean(input.place),
                onlineLink = clean(input.onlineLink),
                createdBy = userId,
            )
        )
        agendaRepository.replace(meeting.id!!, agendaOf(meeting.id!!, input.agenda))
        return meeting.id!!
    }

    // Only while it is a draft: once the notice is out, the meeting changes by adjourning or cancelling.
    @Transactional
    fun update(meetingId: Long, input: MeetingInput, userId: Long) {
        val meeting = lock(meetingId)
        requireDraft(meeting, "A meeting can be edited only until its notice is sent; adjourn or cancel it instead")
        validate(input)
        checkPolicies(meeting.societyId, input.agenda)
        meeting.kind = input.kind
        meeting.title = input.title.trim()
        meeting.description = clean(input.description)
        meeting.scheduledAt = input.scheduledAt
        meeting.place = clean(input.place)
        meeting.onlineLink = clean(input.onlineLink)
        meeting.updatedAt = now()
        meeting.updatedBy = userId
        meetingRepository.save(meeting)
        agendaRepository.replace(meetingId, agendaOf(meetingId, input.agenda))
    }

    @Transactional
    fun delete(meetingId: Long) {
        val meeting = lock(meetingId)
        requireDraft(meeting, "Only a draft can be deleted; cancel the meeting instead")
        meetingRepository.delete(meeting)
    }

    // ---- Notice, cancel, adjourn ----

    // Publishes the meeting to its invitees. Later than the society's notice period needs a reason.
    @Transactional
    fun sendNotice(meetingId: Long, shortNoticeReason: String?, userId: Long) {
        val meeting = lock(meetingId)
        requireDraft(meeting, "The notice has already been sent")
        requireFuture(meeting.scheduledAt)
        require(agendaRepository.findByMeeting(meetingId).isNotEmpty()) { "Add at least one agenda item before sending the notice" }
        val required = noticeDays(meeting.societyId, meeting.kind)
        val given = daysUntil(meeting.scheduledAt)
        val reason = clean(shortNoticeReason)
        if (given < required) {
            require(reason != null) {
                "The notice period is $required days and the meeting is in $given; give a reason for the short notice"
            }
            require(reason.length <= 500) { "The reason can be at most 500 characters" }
        }
        meeting.status = MeetingStatus.NOTICE_SENT
        meeting.noticeSentAt = now()
        meeting.noticeSentBy = userId
        meeting.shortNoticeReason = if (given < required) reason else null
        meetingRepository.save(meeting)
        events.publishEvent(MeetingNoticeSent(meeting.id!!, meeting.societyId, meeting.kind, meeting.title, meeting.scheduledAt, userId))
    }

    @Transactional
    fun cancel(meetingId: Long, reason: String, userId: Long) {
        val meeting = lock(meetingId)
        if (meeting.status == MeetingStatus.DRAFT) throw ConflictException("A draft isn't announced; delete it instead")
        requireNoticeSent(meeting)
        meeting.status = MeetingStatus.CANCELLED
        meeting.cancelReason = requiredReason(reason)
        meeting.cancelledAt = now()
        meeting.cancelledBy = userId
        meetingRepository.save(meeting)
        events.publishEvent(MeetingCancelled(meeting.id!!, meeting.societyId, meeting.kind, meeting.title, meeting.scheduledAt, meeting.cancelReason!!, userId))
    }

    // Moves the meeting to a new date: a new meeting with the same agenda, announced at once (an adjourned
    // meeting needs no fresh notice period), linked both ways.
    @Transactional
    fun adjourn(meetingId: Long, scheduledAt: Instant, place: String?, onlineLink: String?, reason: String, userId: Long): Long {
        val meeting = lock(meetingId)
        requireNoticeSent(meeting)
        requireFuture(scheduledAt)
        require(scheduledAt.isAfter(meeting.scheduledAt)) { "Adjourn to a time after the meeting's own" }
        val newPlace = clean(place) ?: meeting.place
        val newLink = clean(onlineLink) ?: meeting.onlineLink
        validateWhere(newPlace, newLink)
        val next = meetingRepository.save(
            Meeting(
                societyId = meeting.societyId,
                kind = meeting.kind,
                title = meeting.title,
                description = meeting.description,
                scheduledAt = scheduledAt,
                place = newPlace,
                onlineLink = newLink,
                createdBy = userId,
            ).apply {
                status = MeetingStatus.NOTICE_SENT
                noticeSentAt = now()
                noticeSentBy = userId
                adjournedFromId = meeting.id
            }
        )
        agendaRepository.replace(
            next.id!!,
            agendaRepository.findByMeeting(meetingId).map {
                AgendaItem(next.id!!, it.position, it.title, it.description, it.resolutionText).apply { policyId = it.policyId }
            },
        )
        meeting.status = MeetingStatus.ADJOURNED
        meeting.adjournedToId = next.id
        meeting.adjournReason = requiredReason(reason)
        meeting.updatedAt = now()
        meeting.updatedBy = userId
        meetingRepository.save(meeting)
        events.publishEvent(
            MeetingAdjourned(meeting.id!!, next.id!!, meeting.societyId, meeting.kind, meeting.title, meeting.scheduledAt, scheduledAt, meeting.adjournReason!!, userId)
        )
        return next.id!!
    }

    // ---- RSVP ----

    @Transactional
    fun rsvp(meetingId: Long, response: RsvpResponse, viewer: MeetingViewer) {
        val meeting = get(meetingId)
        if (!canRsvp(meeting, viewer)) throw ConflictException("You can reply only to an announced meeting you're invited to, before it starts")
        val existing = rsvpRepository.find(meetingId, viewer.userId)
        if (existing == null) {
            rsvpRepository.save(MeetingRsvp(meetingId, viewer.userId, response, now()))
        } else {
            existing.response = response
            existing.updatedAt = now()
            rsvpRepository.save(existing)
        }
    }

    fun rsvps(meetingId: Long): RsvpSummary {
        val all = rsvpRepository.findByMeeting(meetingId)
        return RsvpSummary(
            yes = all.count { it.response == RsvpResponse.YES },
            no = all.count { it.response == RsvpResponse.NO },
            maybe = all.count { it.response == RsvpResponse.MAYBE },
            responses = all.sortedBy { it.updatedAt },
        )
    }

    // ---- Reads ----

    fun get(meetingId: Long): Meeting =
        meetingRepository.findById(meetingId) ?: throw NoSuchElementException("Meeting $meetingId not found")

    // A meeting the viewer may not see doesn't exist for them.
    fun details(meetingId: Long, viewer: MeetingViewer): MeetingDetails {
        val meeting = get(meetingId)
        if (!viewer.sees(meeting)) throw NoSuchElementException("Meeting $meetingId not found")
        return MeetingDetails(
            meeting = meeting,
            agenda = agendaRepository.findByMeeting(meetingId),
            yourRsvp = rsvpRepository.find(meetingId, viewer.userId)?.response,
            canRsvp = canRsvp(meeting, viewer),
            noticeDays = noticeDays(meeting.societyId, meeting.kind),
        )
    }

    // Upcoming: announced and not before today, soonest first. Past: everything else announced, latest
    // first. Drafts: organisers only.
    fun list(societyId: Long, view: MeetingView, kind: MeetingKind?, viewer: MeetingViewer): List<MeetingDetails> {
        if (view == MeetingView.DRAFTS && !viewer.organises) throw AccessDeniedException("Only the organisers see drafts")
        val startOfToday = LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant()
        val meetings = meetingRepository.findBySociety(societyId)
            .filter { kind == null || it.kind == kind }
            .filter(viewer::sees)
            .filter {
                val upcoming = it.status == MeetingStatus.NOTICE_SENT && !it.scheduledAt.isBefore(startOfToday)
                when (view) {
                    MeetingView.DRAFTS -> it.status == MeetingStatus.DRAFT
                    MeetingView.UPCOMING -> upcoming
                    MeetingView.PAST -> it.status != MeetingStatus.DRAFT && !upcoming
                }
            }
            .let { if (view == MeetingView.PAST) it.reversed() else it }
        val ids = meetings.map { it.id!! }
        val agendas = agendaRepository.findByMeetings(ids).groupBy { it.meetingId }
        val mine = rsvpRepository.findByUser(viewer.userId, ids).associateBy { it.meetingId }
        return meetings.map {
            MeetingDetails(it, agendas[it.id].orEmpty(), mine[it.id]?.response, canRsvp(it, viewer), noticeDays(societyId, it.kind))
        }
    }

    fun names(userIds: Collection<Long>): Map<Long, String> =
        if (userIds.isEmpty()) emptyMap() else userRepository.findAllById(userIds.toSet()).associate { it.id!! to it.name }

    // ---- Reminders ----

    // Pushes the reminder for every announced meeting within its society's `meetingReminderHours`.
    // Called by the scheduler; `now` is a parameter so tests can run it at any time.
    @Transactional
    fun sendReminders(now: Instant): Int {
        var sent = 0
        for (meeting in meetingRepository.findReminderCandidates(now, now.plus(Duration.ofHours(MAX_REMINDER_HOURS)))) {
            val hours = settings.integer(meeting.societyId, SocietySettingKeys.MEETING_REMINDER_HOURS)
            if (hours <= 0 || meeting.scheduledAt.minus(Duration.ofHours(hours)).isAfter(now)) continue
            meeting.reminderSentAt = now
            meetingRepository.save(meeting)
            events.publishEvent(MeetingReminder(meeting.id!!, meeting.societyId, meeting.kind, meeting.title, meeting.scheduledAt))
            sent++
        }
        return sent
    }

    // ---- Helpers ----

    private fun canRsvp(meeting: Meeting, viewer: MeetingViewer) =
        meeting.status == MeetingStatus.NOTICE_SENT && viewer.invitedTo(meeting.kind) && meeting.scheduledAt.isAfter(now())

    private fun noticeDays(societyId: Long, kind: MeetingKind): Long = settings.integer(
        societyId,
        when (kind) {
            MeetingKind.AGM -> SocietySettingKeys.AGM_NOTICE_DAYS
            MeetingKind.SGM -> SocietySettingKeys.SGM_NOTICE_DAYS
            MeetingKind.COMMITTEE -> SocietySettingKeys.COMMITTEE_NOTICE_DAYS
        },
    )

    // Whole days from today to the meeting's day, in the society's time zone.
    private fun daysUntil(at: Instant): Long =
        ChronoUnit.DAYS.between(LocalDate.now(clock.withZone(zone)), at.atZone(zone).toLocalDate())

    private fun lock(meetingId: Long): Meeting =
        meetingRepository.lock(meetingId) ?: throw NoSuchElementException("Meeting $meetingId not found")

    private fun requireDraft(meeting: Meeting, message: String) {
        if (meeting.status != MeetingStatus.DRAFT) throw ConflictException(message)
    }

    private fun requireNoticeSent(meeting: Meeting) {
        if (meeting.status != MeetingStatus.NOTICE_SENT) throw ConflictException("Only an announced meeting that hasn't been held can be changed this way")
    }

    private fun requireFuture(at: Instant) {
        require(at.isAfter(now())) { "The meeting must be in the future" }
    }

    private fun requiredReason(reason: String): String {
        val clean = reason.trim()
        require(clean.isNotEmpty()) { "Give a reason" }
        require(clean.length <= 500) { "The reason can be at most 500 characters" }
        return clean
    }

    private fun validate(input: MeetingInput) {
        val title = input.title.trim()
        require(title.isNotEmpty()) { "Title must not be blank" }
        require(title.length <= 200) { "Title can be at most 200 characters" }
        require((input.description?.length ?: 0) <= 4000) { "Description can be at most 4,000 characters" }
        requireFuture(input.scheduledAt)
        validateWhere(clean(input.place), clean(input.onlineLink))
        require(input.agenda.size <= 50) { "An agenda can have at most 50 items" }
        input.agenda.forEach {
            require(it.title.isNotBlank()) { "Every agenda item needs a title" }
            require(it.title.trim().length <= 300) { "Agenda item titles can be at most 300 characters" }
            require((it.description?.length ?: 0) <= 4000) { "Agenda item details can be at most 4,000 characters" }
            require((it.resolutionText?.length ?: 0) <= 4000) { "A resolution can be at most 4,000 characters" }
        }
    }

    private fun validateWhere(place: String?, link: String?) {
        require(place != null || link != null) { "Give a place, an online link, or both" }
        require((place?.length ?: 0) <= 300) { "Place can be at most 300 characters" }
        if (link != null) {
            require(link.length <= 500) { "The link can be at most 500 characters" }
            require(link.startsWith("https://") || link.startsWith("http://")) { "The online link must start with https://" }
        }
    }

    private fun agendaOf(meetingId: Long, items: List<AgendaInput>) = items.mapIndexed { i, it ->
        AgendaItem(meetingId, i + 1, it.title.trim(), clean(it.description), clean(it.resolutionText)).apply { policyId = it.policyId }
    }

    // Policies referred to must be the society's own.
    private fun checkPolicies(societyId: Long, items: List<AgendaInput>) {
        items.mapNotNull { it.policyId }.distinct().forEach { id ->
            val policy = runCatching { policyService.get(id) }.getOrNull()
            require(policy != null && policy.societyId == societyId) { "Policy $id is not one of this society's" }
        }
    }

    private fun clean(text: String?): String? = text?.trim()?.ifEmpty { null }

    companion object {
        // The largest `meetingReminderHours` allows.
        const val MAX_REMINDER_HOURS = 168L
    }
}
