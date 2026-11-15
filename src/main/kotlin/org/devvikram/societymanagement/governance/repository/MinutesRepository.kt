package org.devvikram.societymanagement.governance.repository

import org.devvikram.societymanagement.governance.model.MeetingMinutes
import org.devvikram.societymanagement.governance.model.MinutesAddendum
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface MinutesRepository {
    fun save(minutes: MeetingMinutes): MeetingMinutes
    fun findByMeeting(meetingId: Long): MeetingMinutes?
    fun findByMeetings(meetingIds: Collection<Long>): List<MeetingMinutes>
}

interface MinutesJpaRepository : JpaRepository<MeetingMinutes, Long> {
    fun findByMeetingId(meetingId: Long): MeetingMinutes?
    fun findByMeetingIdIn(meetingIds: Collection<Long>): List<MeetingMinutes>
}

@Repository
class MinutesRepositoryImpl(private val jpa: MinutesJpaRepository) : MinutesRepository {
    override fun save(minutes: MeetingMinutes): MeetingMinutes = jpa.saveAndFlush(minutes)

    override fun findByMeeting(meetingId: Long): MeetingMinutes? = jpa.findByMeetingId(meetingId)

    override fun findByMeetings(meetingIds: Collection<Long>): List<MeetingMinutes> =
        if (meetingIds.isEmpty()) emptyList() else jpa.findByMeetingIdIn(meetingIds)
}

interface AddendumRepository {
    fun save(addendum: MinutesAddendum): MinutesAddendum
    // Oldest first.
    fun findByMinutes(minutesId: Long): List<MinutesAddendum>
}

interface AddendumJpaRepository : JpaRepository<MinutesAddendum, Long> {
    fun findByMinutesIdOrderByCreatedAtAscIdAsc(minutesId: Long): List<MinutesAddendum>
}

@Repository
class AddendumRepositoryImpl(private val jpa: AddendumJpaRepository) : AddendumRepository {
    override fun save(addendum: MinutesAddendum): MinutesAddendum = jpa.saveAndFlush(addendum)

    override fun findByMinutes(minutesId: Long): List<MinutesAddendum> = jpa.findByMinutesIdOrderByCreatedAtAscIdAsc(minutesId)
}
