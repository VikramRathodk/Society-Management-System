package org.devvikram.societymanagement.governance.repository

import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.governance.model.AgendaItem
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
    // In order.
    fun findByMeeting(meetingId: Long): List<AgendaItem>
    fun findByMeetings(meetingIds: Collection<Long>): List<AgendaItem>
    fun replace(meetingId: Long, items: List<AgendaItem>)
}

interface AgendaJpaRepository : JpaRepository<AgendaItem, Long> {
    fun findByMeetingIdOrderByPositionAsc(meetingId: Long): List<AgendaItem>
    fun findByMeetingIdInOrderByPositionAsc(meetingIds: Collection<Long>): List<AgendaItem>

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgendaItem a WHERE a.meetingId = :meetingId")
    fun deleteByMeeting(meetingId: Long): Int
}

@Repository
class AgendaRepositoryImpl(private val jpa: AgendaJpaRepository) : AgendaRepository {
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

