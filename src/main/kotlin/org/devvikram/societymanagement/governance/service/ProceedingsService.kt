package org.devvikram.societymanagement.governance.service

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.governance.model.AgendaItem
import org.devvikram.societymanagement.governance.model.Ballot
import org.devvikram.societymanagement.governance.model.Meeting
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.governance.model.MeetingStatus
import org.devvikram.societymanagement.governance.model.Outcome
import org.devvikram.societymanagement.governance.model.VoteChoice
import org.devvikram.societymanagement.governance.model.VotingStatus
import org.devvikram.societymanagement.governance.repository.AgendaRepository
import org.devvikram.societymanagement.governance.repository.AttendanceRepository
import org.devvikram.societymanagement.governance.repository.BallotRepository
import org.devvikram.societymanagement.governance.repository.MeetingRepository
import org.devvikram.societymanagement.governance.repository.ResolutionCounter
import org.devvikram.societymanagement.governance.repository.RsvpRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.min

// Members of the meeting (member flats for general meetings, committee members for committee meetings),
// how many must be present, and how many are.
data class Quorum(val members: Int, val required: Int, val present: Int) {
    val met: Boolean get() = present >= required
}

data class SheetRow(
    val userId: Long,
    val name: String,
    // The member flats this person votes for (general meetings; empty for tenants and staff).
    val flats: Set<Long>,
    val committee: Boolean,
    val present: Boolean,
    val rsvp: String?,
)

data class AttendanceSheet(val meeting: Meeting, val rows: List<SheetRow>, val quorum: Quorum)

// Per item, what the viewer sees: turnout always, totals only once closed (in the item itself), and
// whether they have voted / can vote.
data class ItemState(val turnout: Int, val votedAs: Set<String>, val canVote: Boolean, val voterKeys: Set<String>)

data class RegisterEntry(val meeting: Meeting, val item: AgendaItem)

// Running a meeting: attendance and quorum, marking it held, online e-voting by secret ballot, and the
// resolutions register.
@Service
class ProceedingsService(
    private val meetingRepository: MeetingRepository,
    private val agendaRepository: AgendaRepository,
    private val attendanceRepository: AttendanceRepository,
    private val ballotRepository: BallotRepository,
    private val rsvpRepository: RsvpRepository,
    private val counter: ResolutionCounter,
    private val audience: MeetingAudience,
    private val settings: SocietySettingsService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${app.report.zone}") private val zone: ZoneId,
) {
    private fun now(): Instant = clock.instant()

    // ---- Attendance and quorum ----

    @Transactional(readOnly = true)
    fun sheet(meetingId: Long): AttendanceSheet {
        val meeting = get(meetingId)
        val people = audience.people(meeting.societyId).filter { it.active && if (meeting.kind.general) it.seesGeneral else it.committee }
        val present = attendanceRepository.findByMeeting(meetingId).map { it.userId }.toSet()
        val rsvps = rsvpRepository.findByMeeting(meetingId).associate { it.userId to it.response.name }
        val rows = people.sortedBy { it.name.lowercase() }.map {
            SheetRow(it.userId, it.name, if (meeting.kind.general) it.voterFlats else emptySet(), it.committee, it.userId in present, rsvps[it.userId])
        }
        return AttendanceSheet(meeting, rows, quorumOf(meeting, present))
    }

    // Marks exactly these people present: from the meeting's day until its minutes are published.
    @Transactional
    fun setAttendance(meetingId: Long, userIds: Set<Long>, actorId: Long): AttendanceSheet {
        val meeting = lock(meetingId)
        val running = meeting.status == MeetingStatus.HELD ||
            (meeting.status == MeetingStatus.NOTICE_SENT && !meetingDay(meeting).isAfter(today()))
        if (!running) throw ConflictException("Attendance is recorded on the day of an announced meeting, or after it is held")
        val invited = audience.invitees(meeting.societyId, meeting.kind)
        val strangers = userIds - invited
        require(strangers.isEmpty()) { "Not invited to this meeting: ${strangers.joinToString()}" }
        attendanceRepository.replace(meetingId, userIds, actorId, now())
        if (meeting.status == MeetingStatus.HELD) {
            meeting.quorumPresent = quorumOf(meeting, userIds).present
            meetingRepository.save(meeting)
        }
        return sheet(meetingId)
    }

    // On or after its day: freezes the member count and the quorum needed. The chairperson, if given,
    // must be someone invited.
    @Transactional
    fun markHeld(meetingId: Long, chairpersonId: Long?, actorId: Long) {
        val meeting = lock(meetingId)
        if (meeting.status != MeetingStatus.NOTICE_SENT) throw ConflictException("Only an announced meeting can be marked held")
        if (meetingDay(meeting).isAfter(today())) throw ConflictException("The meeting is on ${meetingDay(meeting)}; mark it held on or after that day")
        if (chairpersonId != null) {
            require(chairpersonId in audience.invitees(meeting.societyId, meeting.kind)) { "The chairperson must be someone invited to the meeting" }
        }
        val quorum = quorumOf(meeting, attendanceRepository.findByMeeting(meetingId).map { it.userId }.toSet())
        meeting.status = MeetingStatus.HELD
        meeting.heldAt = now()
        meeting.heldBy = actorId
        meeting.chairpersonId = chairpersonId
        meeting.memberCount = quorum.members
        meeting.quorumRequired = quorum.required
        meeting.quorumPresent = quorum.present
        meetingRepository.save(meeting)
    }

    // General meetings count member flats with someone on the owner side present; committee meetings
    // count members present. Required: the percentage (rounded up), capped for general meetings.
    fun quorumOf(meeting: Meeting, present: Set<Long>): Quorum {
        val sid = meeting.societyId
        return if (meeting.kind.general) {
            val flats = audience.memberFlats(sid)
            val percent = settings.integer(sid, SocietySettingKeys.GENERAL_QUORUM_PERCENT)
            val cap = settings.integer(sid, SocietySettingKeys.GENERAL_QUORUM_CAP)
            val byPercent = ceil(flats.size * percent / 100.0).toInt()
            val required = if (cap > 0) min(byPercent, cap.toInt()) else byPercent
            val heldRequired = meeting.quorumRequired.takeIf { meeting.status != MeetingStatus.NOTICE_SENT && meeting.status != MeetingStatus.DRAFT }
            Quorum(meeting.memberCount ?: flats.size, heldRequired ?: required, flats.count { (_, voters) -> voters.any { it in present } })
        } else {
            val members = audience.committeeMembers(sid)
            val percent = settings.integer(sid, SocietySettingKeys.COMMITTEE_QUORUM_PERCENT)
            val required = ceil(members.size * percent / 100.0).toInt()
            val heldRequired = meeting.quorumRequired.takeIf { meeting.status != MeetingStatus.NOTICE_SENT && meeting.status != MeetingStatus.DRAFT }
            Quorum(meeting.memberCount ?: members.size, heldRequired ?: required, members.count { it in present })
        }
    }

    // ---- Voting ----

    // Before or during the meeting, on an item with a resolution.
    @Transactional
    fun openVoting(itemId: Long, proposedBy: Long?, secondedBy: Long?, actorId: Long) {
        val (meeting, item) = lockItem(itemId)
        if (meeting.status != MeetingStatus.NOTICE_SENT && meeting.status != MeetingStatus.HELD) {
            throw ConflictException("Voting opens only on an announced or held meeting")
        }
        requireNotNull(item.resolutionText) { "Only an item with a resolution is voted on" }
        if (item.votingStatus != VotingStatus.NOT_OPEN || item.outcome != null) throw ConflictException("Voting on this item has already been opened or decided")
        val invited = audience.invitees(meeting.societyId, meeting.kind)
        listOfNotNull(proposedBy, secondedBy).forEach {
            require(it in invited) { "The proposer and seconder must be invited to the meeting" }
        }
        require(proposedBy == null || proposedBy != secondedBy) { "The seconder must be someone other than the proposer" }
        item.proposedBy = proposedBy
        item.secondedBy = secondedBy
        item.votingStatus = VotingStatus.OPEN
        item.votingOpenedAt = now()
        item.votingOpenedBy = actorId
        agendaRepository.save(item)
        events.publishEvent(VotingOpened(meeting.id!!, meeting.societyId, meeting.kind, meeting.title, item.title, actorId))
    }

    // Counts the result (PASSED when more for than against) and numbers a passed resolution.
    @Transactional
    fun closeVoting(itemId: Long, actorId: Long) {
        val (meeting, item) = lockItem(itemId)
        if (item.votingStatus != VotingStatus.OPEN) throw ConflictException("Voting on this item isn't open")
        item.votingStatus = VotingStatus.CLOSED
        item.votingClosedAt = now()
        item.votingClosedBy = actorId
        item.outcome = if (item.votesFor > item.votesAgainst) Outcome.PASSED else Outcome.REJECTED
        if (item.outcome == Outcome.PASSED) {
            val prefix = prefixOf(meeting.kind)
            val year = financialYear(meeting.societyId)
            item.resolutionNumber = "$prefix/$year/${counter.next(meeting.societyId, prefix, year)}"
        }
        agendaRepository.save(item)
        events.publishEvent(
            VotingClosed(meeting.id!!, meeting.societyId, meeting.kind, item.title, item.outcome!!, item.votesFor, item.votesAgainst, item.votesAbstain)
        )
    }

    @Transactional
    fun defer(itemId: Long, actorId: Long) {
        val (_, item) = lockItem(itemId)
        if (item.votingStatus != VotingStatus.NOT_OPEN || item.outcome != null) throw ConflictException("Only an item not yet voted on can be deferred")
        item.outcome = Outcome.DEFERRED
        item.votingClosedAt = now()
        item.votingClosedBy = actorId
        agendaRepository.save(item)
    }

    // One vote per member flat (owner side) at general meetings, per member at committee meetings; final
    // once cast. Only the count moves; the choice is not kept.
    @Transactional
    fun vote(itemId: Long, choice: VoteChoice, flatId: Long?, access: Access) {
        val (meeting, item) = lockItem(itemId)
        if (item.votingStatus != VotingStatus.OPEN) throw ConflictException("Voting on this item isn't open")
        val keys = voterKeys(meeting, access)
        if (keys.isEmpty()) {
            throw AccessDeniedException(
                if (meeting.kind.general) "Only the owner side of a member flat votes" else "Only committee members vote"
            )
        }
        val key = when {
            flatId != null -> "F$flatId".also { require(it in keys) { "You don't vote for flat $flatId" } }
            keys.size == 1 -> keys.single()
            else -> {
                val free = keys.filterNot { ballotRepository.exists(itemId, it) }
                if (free.size == 1) free.single() else throw IllegalArgumentException("You vote for more than one flat; say which (flatId)")
            }
        }
        if (ballotRepository.exists(itemId, key)) throw ConflictException("This vote has already been cast")
        val flat = if (key.startsWith("F")) key.substring(1).toLong() else null
        ballotRepository.save(Ballot(itemId, key, flat, access.userId, now()))
        when (choice) {
            VoteChoice.FOR -> item.votesFor++
            VoteChoice.AGAINST -> item.votesAgainst++
            VoteChoice.ABSTAIN -> item.votesAbstain++
        }
        agendaRepository.save(item)
    }

    // What the viewer sees of each item of a meeting.
    @Transactional(readOnly = true)
    fun states(meeting: Meeting, items: List<AgendaItem>, access: Access): Map<Long, ItemState> {
        val ballots = ballotRepository.findByItems(items.mapNotNull { it.id }).groupBy { it.agendaItemId }
        val keys = voterKeys(meeting, access)
        return items.associate { item ->
            val cast = ballots[item.id].orEmpty().map { it.voterKey }.toSet()
            val mine = keys intersect cast
            item.id!! to ItemState(
                turnout = cast.size,
                votedAs = mine,
                canVote = item.votingStatus == VotingStatus.OPEN && (keys - cast).isNotEmpty(),
                voterKeys = keys,
            )
        }
    }

    // How many could vote on items of this meeting (member flats or committee members).
    fun electorate(meeting: Meeting): Int =
        meeting.memberCount ?: if (meeting.kind.general) audience.memberFlats(meeting.societyId).size else audience.committeeMembers(meeting.societyId).size

    // ---- Register ----

    @Transactional(readOnly = true)
    fun register(societyId: Long, viewer: MeetingViewer, query: String?, outcome: Outcome?): List<RegisterEntry> {
        val items = agendaRepository.findDecided(societyId)
        val meetings = items.map { it.meetingId }.distinct().mapNotNull { meetingRepository.findById(it) }.associateBy { it.id!! }
        val words = query?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        return items.mapNotNull { item -> meetings[item.meetingId]?.let { RegisterEntry(it, item) } }
            .filter { viewer.sees(it.meeting) }
            .filter { outcome == null || it.item.outcome == outcome }
            .filter { e ->
                val text = listOfNotNull(e.item.title, e.item.resolutionText, e.item.resolutionNumber, e.meeting.title).joinToString(" ").lowercase()
                words.all { text.contains(it) }
            }
    }

    // ---- Helpers ----

    private fun voterKeys(meeting: Meeting, access: Access): Set<String> =
        if (meeting.kind.general) {
            MeetingAudience.voterFlats(access, meeting.societyId).map { "F$it" }.toSet()
        } else if (access.canSocietyWide(Permissions.GOVERNANCE_COMMITTEE_VIEW, meeting.societyId) &&
            access.can(Permissions.GOVERNANCE_VOTE_CREATE, meeting.societyId)
        ) {
            setOf("U${access.userId}")
        } else {
            emptySet()
        }

    private fun prefixOf(kind: MeetingKind) = when (kind) {
        MeetingKind.AGM -> "AGM"
        MeetingKind.SGM -> "SGM"
        MeetingKind.COMMITTEE -> "MC"
    }

    // "2026-27" for the financial year containing today (the society's start month, April by default).
    private fun financialYear(societyId: Long): String {
        val start = settings.integer(societyId, SocietySettingKeys.FINANCIAL_YEAR_START_MONTH).toInt()
        val today = today()
        val first = if (today.monthValue >= start) today.year else today.year - 1
        return if (start == 1) "$first" else "$first-${((first + 1) % 100).toString().padStart(2, '0')}"
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    private fun meetingDay(meeting: Meeting): LocalDate = meeting.scheduledAt.atZone(zone).toLocalDate()

    private fun get(meetingId: Long): Meeting =
        meetingRepository.findById(meetingId) ?: throw NoSuchElementException("Meeting $meetingId not found")

    private fun lock(meetingId: Long): Meeting =
        meetingRepository.lock(meetingId) ?: throw NoSuchElementException("Meeting $meetingId not found")

    // The meeting is locked, so votes and closing on its items never interleave.
    private fun lockItem(itemId: Long): Pair<Meeting, AgendaItem> {
        val item = agendaRepository.findById(itemId) ?: throw NoSuchElementException("Agenda item $itemId not found")
        val meeting = lock(item.meetingId)
        return meeting to (agendaRepository.findById(itemId) ?: throw NoSuchElementException("Agenda item $itemId not found"))
    }

    fun meetingOfItem(itemId: Long): Meeting {
        val item = agendaRepository.findById(itemId) ?: throw NoSuchElementException("Agenda item $itemId not found")
        return get(item.meetingId)
    }
}
