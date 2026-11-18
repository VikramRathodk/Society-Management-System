package org.devvikram.societymanagement.governance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.governance.repository.MeetingRepository
import org.devvikram.societymanagement.governance.service.MeetingAudience
import org.devvikram.societymanagement.governance.service.MeetingService
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Meetings end to end through the controller: preparing, notice periods, who sees and is invited to
// what, cancelling, adjourning, RSVP and reminders. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MeetingIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var meetingService: MeetingService
    @Autowired lateinit var meetingRepository: MeetingRepository
    @Autowired lateinit var audience: MeetingAudience

    private var societyId = 0L
    private lateinit var resident: User
    private lateinit var guard: User
    private lateinit var committee: User
    private lateinit var chairman: User
    private lateinit var secretary: User
    private lateinit var outsider: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, inSociety: Long = societyId, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = inSociety, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Meeting Test Society").id!!
        val wing = wingService.addWing(societyId, "A")
        resident = user("RESIDENT", flatId = flatService.addFlat(wing.id!!, "101").id!!)
        guard = user("GUARD")
        committee = user("COMMITTEE")
        chairman = user("CHAIRMAN")
        secretary = user("SECRETARY")
        outsider = user("SECRETARY", inSociety = societyService.createSociety("Other Society").id!!)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val req = request(method, path).header("Authorization", bearer(user))
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(req)
    }

    private fun ResultActions.id(): Long = JsonPath.read<Number>(andReturn().response.contentAsString, "$.id").toLong()

    private fun inDays(days: Long): Instant = Instant.now().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS)

    private fun body(
        kind: String = "AGM",
        at: Instant = inDays(20),
        title: String = "Annual general meeting 2026",
        place: String? = "Clubhouse",
        agenda: String = """[{"title":"Accounts for 2025-26","resolutionText":"Adopt the audited accounts"},{"title":"Any other business"}]""",
    ) = """{"kind":"$kind","title":"$title","scheduledAt":"$at"${place?.let { ""","place":"$it"""" } ?: ""},"agenda":$agenda}"""

    private fun draft(kind: String = "AGM", at: Instant = inDays(20)): Long =
        call(secretary, HttpMethod.POST, "/api/societies/$societyId/meetings", body(kind = kind, at = at))
            .andExpect(status().isCreated).id()

    private fun announced(kind: String = "AGM", at: Instant = inDays(20)): Long {
        val id = draft(kind, at)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/send-notice").andExpect(status().isOk)
        return id
    }

    // ---- Preparing ----

    @Test
    fun `the secretary prepares a meeting with its agenda, others cannot`() {
        call(secretary, HttpMethod.POST, "/api/societies/$societyId/meetings", body())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.general").value(true))
            .andExpect(jsonPath("$.noticeDays").value(14))
            .andExpect(jsonPath("$.agenda.length()").value(2))
            .andExpect(jsonPath("$.agenda[0].position").value(1))
            .andExpect(jsonPath("$.agenda[0].resolutionText").value("Adopt the audited accounts"))
            .andExpect(jsonPath("$.rsvpCounts.yes").value(0))
        listOf(resident, committee, chairman, guard, outsider).forEach {
            call(it, HttpMethod.POST, "/api/societies/$societyId/meetings", body()).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `a meeting needs a future time and a place or link`() {
        call(secretary, HttpMethod.POST, "/api/societies/$societyId/meetings", body(at = inDays(-1))).andExpect(status().isBadRequest)
        call(secretary, HttpMethod.POST, "/api/societies/$societyId/meetings", body(place = null)).andExpect(status().isBadRequest)
        call(
            secretary, HttpMethod.POST, "/api/societies/$societyId/meetings",
            """{"kind":"COMMITTEE","title":"Online","scheduledAt":"${inDays(5)}","onlineLink":"meet.example.com/x","agenda":[]}""",
        ).andExpect(status().isBadRequest)
        call(
            secretary, HttpMethod.POST, "/api/societies/$societyId/meetings",
            """{"kind":"COMMITTEE","title":"Online","scheduledAt":"${inDays(5)}","onlineLink":"https://meet.example.com/x","agenda":[]}""",
        ).andExpect(status().isCreated).andExpect(jsonPath("$.noticeDays").value(2))
    }

    @Test
    fun `a draft is replaced whole and deleted, and only organisers see it`() {
        val id = draft()
        call(secretary, HttpMethod.PUT, "/api/meetings/$id", body(title = "AGM (revised)", agenda = """[{"title":"Only item"}]"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("AGM (revised)"))
            .andExpect(jsonPath("$.agenda.length()").value(1))
        listOf(resident, committee, chairman).forEach {
            call(it, HttpMethod.GET, "/api/meetings/$id").andExpect(status().isNotFound)
            call(it, HttpMethod.GET, "/api/societies/$societyId/meetings?view=drafts").andExpect(status().isForbidden)
        }
        call(secretary, HttpMethod.GET, "/api/societies/$societyId/meetings?view=drafts").andExpect(jsonPath("$.length()").value(1))
        call(secretary, HttpMethod.DELETE, "/api/meetings/$id").andExpect(status().isNoContent)
        call(secretary, HttpMethod.GET, "/api/meetings/$id").andExpect(status().isNotFound)
    }

    // ---- Notice ----

    @Test
    fun `the notice goes out within the notice period, or later with a reason`() {
        val inTime = draft(at = inDays(20))
        call(secretary, HttpMethod.POST, "/api/meetings/$inTime/send-notice")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NOTICE_SENT"))
            .andExpect(jsonPath("$.noticeSentBy.id").value(secretary.id!!))
            .andExpect(jsonPath("$.shortNoticeReason").doesNotExist())

        val short = draft(at = inDays(3))
        call(secretary, HttpMethod.POST, "/api/meetings/$short/send-notice").andExpect(status().isBadRequest)
        call(secretary, HttpMethod.POST, "/api/meetings/$short/send-notice", """{"shortNoticeReason":"  "}""").andExpect(status().isBadRequest)
        call(secretary, HttpMethod.POST, "/api/meetings/$short/send-notice", """{"shortNoticeReason":"Water tank repair can't wait"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shortNoticeReason").value("Water tank repair can't wait"))

        call(secretary, HttpMethod.POST, "/api/meetings/$inTime/send-notice").andExpect(status().isConflict)
        call(secretary, HttpMethod.PUT, "/api/meetings/$inTime", body()).andExpect(status().isConflict)
        call(secretary, HttpMethod.DELETE, "/api/meetings/$inTime").andExpect(status().isConflict)
        call(committee, HttpMethod.POST, "/api/meetings/$short/cancel", """{"reason":"x"}""").andExpect(status().isForbidden)
    }

    @Test
    fun `a notice needs an agenda`() {
        val id = call(secretary, HttpMethod.POST, "/api/societies/$societyId/meetings", body(agenda = "[]")).andExpect(status().isCreated).id()
        call(secretary, HttpMethod.POST, "/api/meetings/$id/send-notice").andExpect(status().isBadRequest)
    }

    // ---- Who sees and is invited ----

    @Test
    fun `residents see general meetings, only the committee sees committee meetings, guards see neither`() {
        val agm = announced("AGM")
        val committeeMeeting = announced("COMMITTEE", inDays(10))

        call(resident, HttpMethod.GET, "/api/meetings/$agm").andExpect(status().isOk).andExpect(jsonPath("$.canRsvp").value(true))
        call(resident, HttpMethod.GET, "/api/meetings/$committeeMeeting").andExpect(status().isNotFound)
        call(resident, HttpMethod.GET, "/api/societies/$societyId/meetings")
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(agm))
            .andExpect(jsonPath("$[0].rsvpCounts").doesNotExist())
        listOf(committee, chairman).forEach {
            call(it, HttpMethod.GET, "/api/societies/$societyId/meetings")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(committeeMeeting)) // soonest first
        }
        call(guard, HttpMethod.GET, "/api/societies/$societyId/meetings").andExpect(status().isForbidden)
        call(guard, HttpMethod.GET, "/api/meetings/$agm").andExpect(status().isNotFound)
        call(outsider, HttpMethod.GET, "/api/meetings/$agm").andExpect(status().isNotFound)
    }

    @Test
    fun `invitees follow the kind of meeting`() {
        val general = audience.invitees(societyId, MeetingKind.AGM)
        val committeeOnly = audience.invitees(societyId, MeetingKind.COMMITTEE)
        assertEquals(setOf(resident.id, committee.id, chairman.id, secretary.id), general)
        assertEquals(setOf(committee.id, chairman.id, secretary.id), committeeOnly)
    }

    // ---- Cancel, adjourn ----

    @Test
    fun `an announced meeting is cancelled with a reason`() {
        val id = announced()
        call(secretary, HttpMethod.POST, "/api/meetings/$id/cancel", """{"reason":" "}""").andExpect(status().isBadRequest)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/cancel", """{"reason":"Clubhouse under repair"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("Clubhouse under repair"))
            .andExpect(jsonPath("$.canRsvp").value(false))
        call(resident, HttpMethod.GET, "/api/societies/$societyId/meetings?view=past").andExpect(jsonPath("$[0].id").value(id))
        call(secretary, HttpMethod.POST, "/api/meetings/${draft()}/cancel", """{"reason":"x"}""").andExpect(status().isConflict)
    }

    @Test
    fun `adjourning moves the meeting to a new date with the same agenda, linked both ways`() {
        val id = announced(at = inDays(20))
        val later = inDays(27)
        call(secretary, HttpMethod.POST, "/api/meetings/$id/adjourn", """{"scheduledAt":"${inDays(10)}","reason":"No quorum"}""")
            .andExpect(status().isBadRequest)
        val next = call(
            secretary, HttpMethod.POST, "/api/meetings/$id/adjourn",
            """{"scheduledAt":"$later","place":"Terrace","reason":"No quorum"}""",
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("NOTICE_SENT"))
            .andExpect(jsonPath("$.place").value("Terrace"))
            .andExpect(jsonPath("$.adjournedFromId").value(id))
            .andExpect(jsonPath("$.agenda.length()").value(2))
            .andExpect(jsonPath("$.agenda[0].resolutionText").value("Adopt the audited accounts"))
            .id()
        call(resident, HttpMethod.GET, "/api/meetings/$id")
            .andExpect(jsonPath("$.status").value("ADJOURNED"))
            .andExpect(jsonPath("$.adjournedToId").value(next))
            .andExpect(jsonPath("$.adjournReason").value("No quorum"))
        call(secretary, HttpMethod.POST, "/api/meetings/$id/adjourn", """{"scheduledAt":"$later","reason":"again"}""").andExpect(status().isConflict)
    }

    // ---- RSVP ----

    @Test
    fun `invitees reply, organisers see the counts and who said what`() {
        val agm = announced()
        call(resident, HttpMethod.PUT, "/api/meetings/$agm/rsvp", """{"response":"YES"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.yourRsvp").value("YES"))
        call(resident, HttpMethod.PUT, "/api/meetings/$agm/rsvp", """{"response":"MAYBE"}""").andExpect(jsonPath("$.yourRsvp").value("MAYBE"))
        call(committee, HttpMethod.PUT, "/api/meetings/$agm/rsvp", """{"response":"NO"}""").andExpect(status().isOk)

        call(secretary, HttpMethod.GET, "/api/meetings/$agm")
            .andExpect(jsonPath("$.rsvpCounts.maybe").value(1))
            .andExpect(jsonPath("$.rsvpCounts.no").value(1))
        call(secretary, HttpMethod.GET, "/api/meetings/$agm/rsvps")
            .andExpect(jsonPath("$.responses.length()").value(2))
            .andExpect(jsonPath("$.responses[0].user.name").value("RESIDENT user"))
        call(resident, HttpMethod.GET, "/api/meetings/$agm/rsvps").andExpect(status().isForbidden)

        val committeeMeeting = announced("COMMITTEE", inDays(10))
        call(resident, HttpMethod.PUT, "/api/meetings/$committeeMeeting/rsvp", """{"response":"YES"}""").andExpect(status().isNotFound)
        call(resident, HttpMethod.PUT, "/api/meetings/${draft()}/rsvp", """{"response":"YES"}""").andExpect(status().isNotFound)
    }

    // ---- Reminders ----

    @Test
    fun `the reminder goes once, within the society's reminder hours`() {
        val id = announced(at = inDays(20))
        val at = meetingService.get(id).scheduledAt
        meetingService.sendReminders(at.minus(Duration.ofHours(30)))
        assertNull(meetingRepository.findById(id)!!.reminderSentAt)

        val due = at.minus(Duration.ofHours(23))
        assertTrue(meetingService.sendReminders(due) >= 1)
        assertNotNull(meetingRepository.findById(id)!!.reminderSentAt)
        val before = meetingRepository.findById(id)!!.reminderSentAt
        meetingService.sendReminders(due.plus(Duration.ofHours(1)))
        assertEquals(before, meetingRepository.findById(id)!!.reminderSentAt)
    }
}
