package org.devvikram.societymanagement.governance.repository

import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.governance.model.AgendaItem
import org.devvikram.societymanagement.governance.model.Attendance
import org.devvikram.societymanagement.governance.model.Ballot
import org.devvikram.societymanagement.governance.model.Meeting
import org.devvikram.societymanagement.governance.model.MeetingRsvp
import org.devvikram.societymanagement.governance.model.MeetingStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

interface MeetingRepository {
    fun save(meeting: Meeting): Meeting
    fun findById(id: Long): Meeting?
    // For a change to the meeting or its agenda: one at a time per meeting.
    fun lock(id: Long): Meeting?
    // Soonest first.
    fun findBySociety(societyId: Long): List<Meeting>
    // Meetings whose notice went out, without a reminder yet, starting before `until`.
    fun findReminderCandidates(from: Instant, until: Instant): List<Meeting>
    fun delete(meeting: Meeting)
}

interface MeetingJpaRepository : JpaRepository<Meeting, Long> {
    fun findBySocietyIdOrderByScheduledAtAscIdAsc(societyId: Long): List<Meeting>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Meeting m WHERE m.id = :id")
    fun findLocked(id: Long): Meeting?

    @Query(
        "SELECT m FROM Meeting m WHERE m.status = :status AND m.reminderSentAt IS NULL " +
            "AND m.scheduledAt > :from AND m.scheduledAt <= :until ORDER BY m.scheduledAt"
    )
    fun findReminderCandidates(status: MeetingStatus, from: Instant, until: Instant): List<Meeting>
}

@Repository
class MeetingRepositoryImpl(private val jpa: MeetingJpaRepository) : MeetingRepository {
    override fun save(meeting: Meeting): Meeting = jpa.saveAndFlush(meeting)

    override fun findById(id: Long): Meeting? = jpa.findById(id).orElse(null)

    override fun lock(id: Long): Meeting? = jpa.findLocked(id)

    override fun findBySociety(societyId: Long): List<Meeting> = jpa.findBySocietyIdOrderByScheduledAtAscIdAsc(societyId)

    override fun findReminderCandidates(from: Instant, until: Instant): List<Meeting> =
        jpa.findReminderCandidates(MeetingStatus.NOTICE_SENT, from, until)

    override fun delete(meeting: Meeting) = jpa.delete(meeting)
}

interface AgendaRepository {
    fun findById(id: Long): AgendaItem?
    fun save(item: AgendaItem): AgendaItem
    // Every item of the society's meetings with an outcome, for the resolutions register.
    fun findDecided(societyId: Long): List<AgendaItem>
    // In order.
    fun findByMeeting(meetingId: Long): List<AgendaItem>
    fun findByMeetings(meetingIds: Collection<Long>): List<AgendaItem>
    fun replace(meetingId: Long, items: List<AgendaItem>)
}

interface AgendaJpaRepository : JpaRepository<AgendaItem, Long> {
    fun findByMeetingIdOrderByPositionAsc(meetingId: Long): List<AgendaItem>
    fun findByMeetingIdInOrderByPositionAsc(meetingIds: Collection<Long>): List<AgendaItem>

    @Query(
        "SELECT a FROM AgendaItem a, Meeting m WHERE m.id = a.meetingId AND m.societyId = :societyId " +
            "AND a.outcome IS NOT NULL ORDER BY a.votingClosedAt DESC, a.id DESC"
    )
    fun findDecided(societyId: Long): List<AgendaItem>

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgendaItem a WHERE a.meetingId = :meetingId")
    fun deleteByMeeting(meetingId: Long): Int
}

@Repository
class AgendaRepositoryImpl(private val jpa: AgendaJpaRepository) : AgendaRepository {
    override fun findById(id: Long): AgendaItem? = jpa.findById(id).orElse(null)

    override fun save(item: AgendaItem): AgendaItem = jpa.saveAndFlush(item)

    override fun findDecided(societyId: Long): List<AgendaItem> = jpa.findDecided(societyId)

    override fun findByMeeting(meetingId: Long): List<AgendaItem> = jpa.findByMeetingIdOrderByPositionAsc(meetingId)

    override fun findByMeetings(meetingIds: Collection<Long>): List<AgendaItem> =
        if (meetingIds.isEmpty()) emptyList() else jpa.findByMeetingIdInOrderByPositionAsc(meetingIds)

    override fun replace(meetingId: Long, items: List<AgendaItem>) {
        jpa.deleteByMeeting(meetingId)
        jpa.saveAllAndFlush(items)
    }
}

interface RsvpRepository {
    fun save(rsvp: MeetingRsvp): MeetingRsvp
    fun find(meetingId: Long, userId: Long): MeetingRsvp?
    fun findByMeeting(meetingId: Long): List<MeetingRsvp>
    fun findByUser(userId: Long, meetingIds: Collection<Long>): List<MeetingRsvp>
}

interface RsvpJpaRepository : JpaRepository<MeetingRsvp, Long> {
    fun findByMeetingIdAndUserId(meetingId: Long, userId: Long): MeetingRsvp?
    fun findByMeetingId(meetingId: Long): List<MeetingRsvp>
    fun findByUserIdAndMeetingIdIn(userId: Long, meetingIds: Collection<Long>): List<MeetingRsvp>
}

@Repository
class RsvpRepositoryImpl(private val jpa: RsvpJpaRepository) : RsvpRepository {
    override fun save(rsvp: MeetingRsvp): MeetingRsvp = jpa.saveAndFlush(rsvp)

    override fun find(meetingId: Long, userId: Long): MeetingRsvp? = jpa.findByMeetingIdAndUserId(meetingId, userId)

    override fun findByMeeting(meetingId: Long): List<MeetingRsvp> = jpa.findByMeetingId(meetingId)

    override fun findByUser(userId: Long, meetingIds: Collection<Long>): List<MeetingRsvp> =
        if (meetingIds.isEmpty()) emptyList() else jpa.findByUserIdAndMeetingIdIn(userId, meetingIds)
}

interface BallotRepository {
    fun save(ballot: Ballot): Ballot
    fun exists(itemId: Long, voterKey: String): Boolean
    fun findByItems(itemIds: Collection<Long>): List<Ballot>
}

interface BallotJpaRepository : JpaRepository<Ballot, Long> {
    fun existsByAgendaItemIdAndVoterKey(agendaItemId: Long, voterKey: String): Boolean
    fun findByAgendaItemIdIn(itemIds: Collection<Long>): List<Ballot>
}

@Repository
class BallotRepositoryImpl(private val jpa: BallotJpaRepository) : BallotRepository {
    override fun save(ballot: Ballot): Ballot = jpa.saveAndFlush(ballot)

    override fun exists(itemId: Long, voterKey: String): Boolean = jpa.existsByAgendaItemIdAndVoterKey(itemId, voterKey)

    override fun findByItems(itemIds: Collection<Long>): List<Ballot> =
        if (itemIds.isEmpty()) emptyList() else jpa.findByAgendaItemIdIn(itemIds)
}

interface AttendanceRepository {
    fun findByMeeting(meetingId: Long): List<Attendance>
    // Makes exactly these people present.
    fun replace(meetingId: Long, userIds: Set<Long>, recordedBy: Long, at: Instant)
}

interface AttendanceJpaRepository : JpaRepository<Attendance, Long> {
    fun findByMeetingId(meetingId: Long): List<Attendance>
}

@Repository
class AttendanceRepositoryImpl(private val jpa: AttendanceJpaRepository) : AttendanceRepository {
    override fun findByMeeting(meetingId: Long): List<Attendance> = jpa.findByMeetingId(meetingId)

    override fun replace(meetingId: Long, userIds: Set<Long>, recordedBy: Long, at: Instant) {
        val existing = jpa.findByMeetingId(meetingId)
        jpa.deleteAll(existing.filter { it.userId !in userIds })
        val kept = existing.map { it.userId }.toSet()
        jpa.saveAll(userIds.filter { it !in kept }.map { Attendance(meetingId, it, recordedBy, at) })
        jpa.flush()
    }
}

interface ResolutionCounter {
    // The next number for this society, prefix (AGM, SGM, MC) and financial year; safe under concurrency.
    fun next(societyId: Long, prefix: String, financialYear: String): Int
}

interface ResolutionCounterJpaRepository : JpaRepository<Ballot, Long> {
    @Query(
        value = "INSERT INTO resolution_counter (society_id, prefix, financial_year, last_number) " +
            "VALUES (:societyId, :prefix, :year, 1) ON CONFLICT (society_id, prefix, financial_year) " +
            "DO UPDATE SET last_number = resolution_counter.last_number + 1 RETURNING last_number",
        nativeQuery = true,
    )
    fun next(societyId: Long, prefix: String, year: String): Int
}

@Repository
class ResolutionCounterImpl(private val jpa: ResolutionCounterJpaRepository) : ResolutionCounter {
    override fun next(societyId: Long, prefix: String, financialYear: String): Int = jpa.next(societyId, prefix, financialYear)
}
