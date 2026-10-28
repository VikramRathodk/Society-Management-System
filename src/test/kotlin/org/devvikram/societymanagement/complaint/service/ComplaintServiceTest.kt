package org.devvikram.societymanagement.complaint.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintActivity
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.complaint.repository.ComplaintActivityRepository
import org.devvikram.societymanagement.complaint.repository.ComplaintRepository
import org.devvikram.societymanagement.complaint.repository.ComplaintSearch
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.Wing
import org.devvikram.societymanagement.society.service.InMemoryFlatRepository
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

private class InMemoryUserRepository : UserRepository {
    private val store = mutableMapOf<Long, User>()
    private var nextId = 1L

    override fun save(user: User): User {
        if (user.id == null) user.id = nextId++
        store[user.id!!] = user
        return user
    }

    override fun findById(id: Long): User? = store[id]
    override fun findAll(): List<User> = store.values.toList()
    override fun findAllById(ids: Collection<Long>): List<User> = ids.mapNotNull { store[it] }
    override fun findByPhone(phone: String): User? = store.values.find { it.phone == phone }
    override fun delete(user: User) {
        store.remove(user.id)
    }
}

private class InMemoryComplaintRepository : ComplaintRepository {
    val store = mutableMapOf<Long, Complaint>()
    private var nextId = 1L

    override fun save(complaint: Complaint): Complaint {
        if (complaint.id == null) complaint.id = nextId++
        if (complaint.createdAt == null) complaint.createdAt = Instant.now()
        store[complaint.id!!] = complaint
        return complaint
    }

    override fun findById(id: Long): Complaint? = store[id]

    override fun search(search: ComplaintSearch): List<Complaint> = store.values.filter {
        it.society.id == search.societyId &&
            (search.flatIds == null || it.flat.id in search.flatIds) &&
            (search.status == null || it.status == search.status) &&
            (search.category == null || it.category == search.category)
    }

    override fun changeStatus(id: Long, from: Set<ComplaintStatus>, to: ComplaintStatus, by: User, at: Instant): Boolean {
        val complaint = store[id]?.takeIf { it.status in from } ?: return false
        complaint.status = to
        complaint.updatedBy = by
        complaint.updatedAt = at
        return true
    }

    override fun delete(complaint: Complaint) {
        store.remove(complaint.id)
    }
}

private class InMemoryComplaintActivityRepository : ComplaintActivityRepository {
    val store = mutableListOf<ComplaintActivity>()
    private var nextId = 1L

    override fun save(activity: ComplaintActivity): ComplaintActivity {
        if (activity.id == null) activity.id = nextId++
        if (activity.createdAt == null) activity.createdAt = Instant.now()
        store += activity
        return activity
    }

    override fun findByComplaintId(complaintId: Long): List<ComplaintActivity> =
        store.filter { it.complaint.id == complaintId }
}

class ComplaintServiceTest {

    private lateinit var complaints: InMemoryComplaintRepository
    private lateinit var events: MutableList<Any>
    private lateinit var service: ComplaintService
    private lateinit var flat: Flat
    private lateinit var resident: User
    private lateinit var committee: User

    @BeforeEach
    fun setUp() {
        complaints = InMemoryComplaintRepository()
        events = mutableListOf()
        val users = InMemoryUserRepository()
        val flats = InMemoryFlatRepository()
        service = ComplaintService(
            complaints, InMemoryComplaintActivityRepository(), flats, users, ApplicationEventPublisher { events += it },
        )

        val society = Society(name = "Sunrise Apartments").also { it.id = 1L }
        val wing = Wing(society = society, name = "A").also { it.id = 1L }
        flat = flats.save(Flat(wing = wing, flatNumber = "101"))
        resident = users.save(User(name = "Asha Patil", phone = "8400000001", passwordHash = "hashed"))
        committee = users.save(User(name = "Meera Joshi", phone = "8400000002", passwordHash = "hashed"))
    }

    private fun raise(description: String = "Kitchen tap leaking") =
        service.raise(RaiseComplaintCommand(flat.id!!, ComplaintCategory.PLUMBING, description), resident.id!!)

    @Test
    fun `raising a complaint opens it against the flat and its society`() {
        val complaint = service.getById(raise("  Kitchen tap leaking  "))

        assertEquals(ComplaintStatus.OPEN, complaint.status)
        assertEquals(flat.id, complaint.flat.id)
        assertEquals(1L, complaint.society.id)
        assertEquals("Kitchen tap leaking", complaint.description)
        assertEquals(resident.id, complaint.raisedBy.id)
        assertNull(complaint.updatedAt)
        assertEquals(listOf(ComplaintRaised(complaint.id!!, 1L, resident.id!!, "101", ComplaintCategory.PLUMBING)), events)
        val raised = service.activityOf(complaint.id!!).single()
        assertNull(raised.fromStatus)
        assertEquals(ComplaintStatus.OPEN, raised.toStatus)
        assertEquals(resident.id, raised.actor.id)
    }

    @Test
    fun `each status change joins the timeline with its exact previous state and note`() {
        val id = raise()

        service.changeStatus(id, ComplaintStatus.RESOLVED, committee.id!!, "  Replaced the washer  ")
        service.changeStatus(id, ComplaintStatus.OPEN, committee.id!!, "Still dripping")
        service.changeStatus(id, ComplaintStatus.IN_PROGRESS, committee.id!!, "   ")

        val timeline = service.activityOf(id)
        assertEquals(
            listOf(null to ComplaintStatus.OPEN, ComplaintStatus.OPEN to ComplaintStatus.RESOLVED,
                ComplaintStatus.RESOLVED to ComplaintStatus.OPEN, ComplaintStatus.OPEN to ComplaintStatus.IN_PROGRESS),
            timeline.map { it.fromStatus to it.toStatus },
        )
        assertEquals(listOf(null, "Replaced the washer", "Still dripping", null), timeline.map { it.note })
        assertEquals("Replaced the washer", events.filterIsInstance<ComplaintStatusChanged>().first().note)
    }

    @Test
    fun `a blank description or unknown flat is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { raise("   ") }
        assertThrows(NoSuchElementException::class.java) {
            service.raise(RaiseComplaintCommand(999L, ComplaintCategory.OTHER, "x"), resident.id!!)
        }
    }

    @Test
    fun `the committee moves a complaint through its statuses and the raiser is told`() {
        val id = raise()

        service.changeStatus(id, ComplaintStatus.IN_PROGRESS, committee.id!!)
        service.changeStatus(id, ComplaintStatus.RESOLVED, committee.id!!)
        service.changeStatus(id, ComplaintStatus.CLOSED, committee.id!!)

        val complaint = service.getById(id)
        assertEquals(ComplaintStatus.CLOSED, complaint.status)
        assertEquals(committee.id, complaint.updatedBy?.id)
        assertEquals(
            listOf(ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED),
            events.filterIsInstance<ComplaintStatusChanged>().map { it.status },
        )
        assertTrue(events.filterIsInstance<ComplaintStatusChanged>().all { it.raisedById == resident.id })
    }

    @Test
    fun `a resolved complaint can be reopened, a closed one cannot move again`() {
        val id = raise()
        service.changeStatus(id, ComplaintStatus.RESOLVED, committee.id!!)
        service.changeStatus(id, ComplaintStatus.OPEN, committee.id!!)
        assertEquals(ComplaintStatus.OPEN, service.getById(id).status)

        service.changeStatus(id, ComplaintStatus.RESOLVED, committee.id!!)
        service.changeStatus(id, ComplaintStatus.CLOSED, committee.id!!)
        ComplaintStatus.entries.forEach { to ->
            assertThrows(ConflictException::class.java) { service.changeStatus(id, to, committee.id!!) }
        }
    }

    @Test
    fun `steps cannot be skipped or repeated`() {
        val id = raise()

        assertThrows(ConflictException::class.java) { service.changeStatus(id, ComplaintStatus.CLOSED, committee.id!!) }
        assertThrows(ConflictException::class.java) { service.changeStatus(id, ComplaintStatus.OPEN, committee.id!!) }
        service.changeStatus(id, ComplaintStatus.IN_PROGRESS, committee.id!!)
        assertThrows(ConflictException::class.java) {
            service.changeStatus(id, ComplaintStatus.IN_PROGRESS, committee.id!!)
        }
        assertTrue(events.filterIsInstance<ComplaintStatusChanged>().size == 1)
    }

    @Test
    fun `edits and withdrawal are allowed only until the complaint is resolved`() {
        val id = raise()
        service.update(id, UpdateComplaintCommand(category = ComplaintCategory.ELECTRICAL), resident.id!!)
        service.changeStatus(id, ComplaintStatus.IN_PROGRESS, committee.id!!)
        service.update(id, UpdateComplaintCommand(description = "Also the geyser"), resident.id!!)

        val edited = service.getById(id)
        assertEquals(ComplaintCategory.ELECTRICAL, edited.category)
        assertEquals("Also the geyser", edited.description)
        assertEquals(resident.id, edited.updatedBy?.id)

        service.changeStatus(id, ComplaintStatus.RESOLVED, committee.id!!)
        assertThrows(ConflictException::class.java) {
            service.update(id, UpdateComplaintCommand(description = "Too late"), resident.id!!)
        }
        assertThrows(ConflictException::class.java) { service.withdraw(id) }

        val other = raise()
        service.withdraw(other)
        assertThrows(NoSuchElementException::class.java) { service.getById(other) }
    }
}
