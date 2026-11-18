package org.devvikram.societymanagement.governance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.governance.repository.MeetingRepository
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.ResidentType
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
import org.springframework.jdbc.core.JdbcTemplate
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

// Running meetings end to end: attendance and quorum, marking held, e-voting by secret ballot,
// resolution numbers and the register. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProceedingsIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var meetingRepository: MeetingRepository
    @Autowired lateinit var settingsService: SocietySettingsService
    @Autowired lateinit var jdbc: JdbcTemplate

    private var societyId = 0L
    private var f101 = 0L
    private var f102 = 0L
    private var f103 = 0L
    private var f104 = 0L
    // 101: owner + tenant; 102: owner + family; 103 and 104: one owner of both.
    private lateinit var owner101: User
    private lateinit var tenant101: User
    private lateinit var owner102: User
    private lateinit var family102: User
    private lateinit var owner103and104: User
    private lateinit var guard: User
    private lateinit var committee: User
    private lateinit var chairman: User
    private lateinit var secretary: User

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(name: String, role: String, flatId: Long? = null, type: ResidentType? = null) = userService.createUser(
        CreateUserCommand(
            name = name,
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = role, societyId = societyId, flatId = flatId, residentType = type),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Proceedings Test Society").id!!
        val wing = wingService.addWing(societyId, "A").id!!
        f101 = flatService.addFlat(wing, "101").id!!
        f102 = flatService.addFlat(wing, "102").id!!
        f103 = flatService.addFlat(wing, "103").id!!
        f104 = flatService.addFlat(wing, "104").id!!
        owner101 = user("Asha", "RESIDENT", f101, ResidentType.OWNER)
        tenant101 = user("Bela", "RESIDENT", f101, ResidentType.TENANT)
        owner102 = user("Chetan", "RESIDENT", f102, ResidentType.OWNER)
        family102 = user("Dev", "RESIDENT", f102, ResidentType.FAMILY)
        owner103and104 = user("Esha", "RESIDENT", f103, ResidentType.OWNER)
        userService.assignRole(owner103and104.id!!, AssignRoleCommand("RESIDENT", societyId, f104, ResidentType.OWNER))
        guard = user("Guard", "GUARD")
        committee = user("Committee", "COMMITTEE")
        chairman = user("Chairman", "CHAIRMAN")
        secretary = user("Secretary", "SECRETARY")
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun call(user: User, method: HttpMethod, path: String, body: String? = null): ResultActions {
        val req = request(method, path).header("Authorization", bearer(user))
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(req)
    }

    private fun ResultActions.json(): String = andReturn().response.contentAsString

    // An announced meeting; `today` moves it to an hour ago, as if it were being held now.
    private fun meeting(kind: String = "AGM", today: Boolean = true): Pair<Long, List<Long>> {
        val at = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS)
        val json = call(
            secretary, HttpMethod.POST, "/api/societies/$societyId/meetings",
            """{"kind":"$kind","title":"$kind 2026","scheduledAt":"$at","place":"Clubhouse","agenda":[
              {"title":"Accounts","resolutionText":"Adopt the audited accounts"},
              {"title":"Repainting","resolutionText":"Repaint the building for up to 12 lakh"},
              {"title":"Any other business"}]}""",
        ).andExpect(status().isCreated).json()
        val id = JsonPath.read<Number>(json, "$.id").toLong()
        call(secretary, HttpMethod.POST, "/api/meetings/$id/send-notice").andExpect(status().isOk)
        if (today) {
            val m = meetingRepository.findById(id)!!
            m.scheduledAt = Instant.now().minus(Duration.ofHours(1))
            meetingRepository.save(m)
        }
        val items = JsonPath.read<List<Number>>(json, "$.agenda[*].id").map { it.toLong() }
        return id to items
    }

    private fun vote(user: User, item: Long, choice: String, flatId: Long? = null) =
        call(user, HttpMethod.POST, "/api/agenda-items/$item/vote", """{"choice":"$choice"${flatId?.let { ""","flatId":$it""" } ?: ""}}""")

    // ---- Attendance and quorum ----

    @Test
    fun `general meeting quorum counts member flats with someone on the owner side present`() {
        val (id, _) = meeting()
        call(secretary, HttpMethod.GET, "/api/meetings/$id/attendance")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.quorum.members").value(4)) // 101–104; a tenant doesn't make a member flat
            .andExpect(jsonPath("$.quorum.required").value(3)) // 67% of 4, rounded up; cap 20
            .andExpect(jsonPath("$.quorum.present").value(0))
            .andExpect(jsonPath("$.people.length()").value(8)) // residents and the committee; no guard

        // A tenant and the committee don't count; family counts for 102; one owner counts for both flats.
        call(
            secretary, HttpMethod.PUT, "/api/meetings/$id/attendance",
            """{"userIds":[${tenant101.id},${committee.id},${family102.id},${owner103and104.id}]}""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.quorum.present").value(3))
            .andExpect(jsonPath("$.quorum.met").value(true))

        settingsService.update(societyId, mapOf(SocietySettingKeys.GENERAL_QUORUM_CAP to 2), secretary.id!!)
        call(secretary, HttpMethod.GET, "/api/meetings/$id/attendance").andExpect(jsonPath("$.quorum.required").value(2))

        call(secretary, HttpMethod.PUT, "/api/meetings/$id/attendance", """{"userIds":[${guard.id}]}""").andExpect(status().isBadRequest)
        call(committee, HttpMethod.GET, "/api/meetings/$id/attendance").andExpect(status().isForbidden)
    }

    @Test
    fun `committee meeting quorum counts committee members`() {
        val (id, _) = meeting("COMMITTEE")
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/attendance", """{"userIds":[${committee.id}]}""")
            .andExpect(jsonPath("$.quorum.members").value(3))
            .andExpect(jsonPath("$.quorum.required").value(2)) // 51% of 3, rounded up
            .andExpect(jsonPath("$.quorum.present").value(1))
            .andExpect(jsonPath("$.quorum.met").value(false))
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/attendance", """{"userIds":[${owner101.id}]}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `attendance waits for the meeting's day, and holding freezes the quorum`() {
        val (future, _) = meeting(today = false)
        call(secretary, HttpMethod.PUT, "/api/meetings/$future/attendance", """{"userIds":[]}""").andExpect(status().isConflict)
        call(secretary, HttpMethod.POST, "/api/meetings/$future/hold").andExpect(status().isConflict)

        val (id, _) = meeting()
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/attendance", """{"userIds":[${owner101.id},${owner102.id}]}""")
        call(secretary, HttpMethod.POST, "/api/meetings/$id/hold", """{"chairpersonId":${chairman.id}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("HELD"))
            .andExpect(jsonPath("$.chairperson.id").value(chairman.id!!))
            .andExpect(jsonPath("$.quorumRequired").value(3))
            .andExpect(jsonPath("$.quorumPresent").value(2))
        // Late arrivals are still recorded after it is marked held.
        call(secretary, HttpMethod.PUT, "/api/meetings/$id/attendance", """{"userIds":[${owner101.id},${owner102.id},${owner103and104.id}]}""")
            .andExpect(jsonPath("$.quorum.present").value(4)) // the owner of 103 and 104 brings two flats
        call(owner101, HttpMethod.GET, "/api/meetings/$id").andExpect(jsonPath("$.quorumPresent").value(4))
        call(secretary, HttpMethod.POST, "/api/meetings/$id/hold").andExpect(status().isConflict)
    }

    // ---- Voting ----

    @Test
    fun `one secret vote per member flat, final once cast, counted on close`() {
        val (id, items) = meeting()
        val item = items[0]
        call(owner101, HttpMethod.POST, "/api/agenda-items/$item/vote", """{"choice":"FOR"}""").andExpect(status().isConflict) // not open
        call(owner101, HttpMethod.POST, "/api/agenda-items/$item/open-voting").andExpect(status().isForbidden)
        call(secretary, HttpMethod.POST, "/api/agenda-items/$item/open-voting", """{"proposedBy":${owner101.id},"secondedBy":${owner102.id}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.agenda[0].votingStatus").value("OPEN"))
            .andExpect(jsonPath("$.agenda[0].proposedBy.name").value("Asha"))
            .andExpect(jsonPath("$.electorate").value(4))

        call(owner101, HttpMethod.GET, "/api/meetings/$id").andExpect(jsonPath("$.agenda[0].canVote").value(true))
        vote(owner101, item, "FOR").andExpect(status().isOk)
            .andExpect(jsonPath("$.agenda[0].youVoted").value(true))
            .andExpect(jsonPath("$.agenda[0].canVote").value(false))
            .andExpect(jsonPath("$.agenda[0].turnout").value(1))
            .andExpect(jsonPath("$.agenda[0].results").doesNotExist()) // no running totals
        vote(owner101, item, "AGAINST").andExpect(status().isConflict) // final
        vote(tenant101, item, "AGAINST").andExpect(status().isForbidden) // tenants don't vote
        vote(committee, item, "FOR").andExpect(status().isForbidden) // no member flat
        vote(guard, item, "FOR").andExpect(status().isNotFound) // can't see the meeting

        vote(family102, item, "AGAINST").andExpect(status().isOk) // family votes for 102...
        vote(owner102, item, "FOR").andExpect(status().isConflict) // ...so 102 has voted

        vote(owner103and104, item, "FOR").andExpect(status().isBadRequest) // two flats: say which
        vote(owner103and104, item, "FOR", f103).andExpect(status().isOk)
            .andExpect(jsonPath("$.agenda[0].yourVotes").value(2))
            .andExpect(jsonPath("$.agenda[0].youVoted").value(false))
        vote(owner103and104, item, "ABSTAIN").andExpect(status().isOk) // the only flat left: 104
            .andExpect(jsonPath("$.agenda[0].youVoted").value(true))
            .andExpect(jsonPath("$.agenda[0].turnout").value(4))

        // Only that a flat voted is stored, never how.
        val columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'ballot'", String::class.java)
        assertEquals(setOf("id", "agenda_item_id", "voter_key", "flat_id", "voted_by", "voted_at"), columns.filterNotNull().toSet())

        call(secretary, HttpMethod.POST, "/api/agenda-items/$item/close-voting")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.agenda[0].votingStatus").value("CLOSED"))
            .andExpect(jsonPath("$.agenda[0].outcome").value("PASSED"))
            .andExpect(jsonPath("$.agenda[0].results.votesFor").value(2))
            .andExpect(jsonPath("$.agenda[0].results.votesAgainst").value(1))
            .andExpect(jsonPath("$.agenda[0].results.abstained").value(1))
            .andExpect(jsonPath("$.agenda[0].resolutionNumber").value(org.hamcrest.Matchers.matchesPattern("AGM/\\d{4}-\\d{2}/1")))
        vote(owner102, item, "FOR").andExpect(status().isConflict) // closed
        call(secretary, HttpMethod.POST, "/api/agenda-items/$item/close-voting").andExpect(status().isConflict)
    }

    @Test
    fun `a tie is not passed, passed resolutions are numbered in order, items without a resolution aren't voted`() {
        val (_, items) = meeting()
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[2]}/open-voting").andExpect(status().isBadRequest)
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[0]}/open-voting", """{"proposedBy":${owner101.id},"secondedBy":${owner101.id}}""")
            .andExpect(status().isBadRequest)

        listOf(items[0], items[1]).forEach { call(secretary, HttpMethod.POST, "/api/agenda-items/$it/open-voting").andExpect(status().isOk) }
        vote(owner101, items[0], "FOR")
        vote(owner102, items[0], "AGAINST")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[0]}/close-voting").andExpect(jsonPath("$.agenda[0].outcome").value("REJECTED"))
            .andExpect(jsonPath("$.agenda[0].resolutionNumber").doesNotExist())

        vote(owner101, items[1], "FOR")
        val (_, other) = meeting()
        call(secretary, HttpMethod.POST, "/api/agenda-items/${other[0]}/open-voting")
        vote(owner102, other[0], "FOR")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${other[0]}/close-voting").andExpect(jsonPath("$.agenda[0].resolutionNumber").value(org.hamcrest.Matchers.endsWith("/1")))
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[1]}/close-voting").andExpect(jsonPath("$.agenda[1].resolutionNumber").value(org.hamcrest.Matchers.endsWith("/2")))
    }

    @Test
    fun `committee members vote on committee meetings, residents don't see them`() {
        val (_, items) = meeting("COMMITTEE")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[0]}/open-voting").andExpect(status().isOk)
        vote(owner101, items[0], "FOR").andExpect(status().isNotFound)
        vote(committee, items[0], "FOR").andExpect(status().isOk).andExpect(jsonPath("$.electorate").value(3))
        vote(chairman, items[0], "FOR").andExpect(status().isOk)
        vote(committee, items[0], "AGAINST").andExpect(status().isConflict)
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[0]}/close-voting")
            .andExpect(jsonPath("$.agenda[0].resolutionNumber").value(org.hamcrest.Matchers.startsWith("MC/")))
    }

    @Test
    fun `an item can be deferred instead of voted`() {
        val (_, items) = meeting()
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[1]}/defer")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.agenda[1].outcome").value("DEFERRED"))
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[1]}/open-voting").andExpect(status().isConflict)
        call(secretary, HttpMethod.POST, "/api/agenda-items/${items[1]}/defer").andExpect(status().isConflict)
    }

    // ---- Register ----

    @Test
    fun `the register lists passed resolutions the viewer may see`() {
        val (_, general) = meeting()
        val (_, committeeItems) = meeting("COMMITTEE")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${general[0]}/open-voting")
        vote(owner101, general[0], "FOR")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${general[0]}/close-voting")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${committeeItems[1]}/open-voting")
        vote(committee, committeeItems[1], "FOR")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${committeeItems[1]}/close-voting")
        call(secretary, HttpMethod.POST, "/api/agenda-items/${general[1]}/defer")

        call(owner101, HttpMethod.GET, "/api/societies/$societyId/resolutions")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Accounts"))
            .andExpect(jsonPath("$[0].results.votesFor").value(1))
        call(committee, HttpMethod.GET, "/api/societies/$societyId/resolutions").andExpect(jsonPath("$.length()").value(2))
        call(committee, HttpMethod.GET, "/api/societies/$societyId/resolutions?q=repaint").andExpect(jsonPath("$.length()").value(1))
        call(committee, HttpMethod.GET, "/api/societies/$societyId/resolutions?outcome=all").andExpect(jsonPath("$.length()").value(3))
        call(committee, HttpMethod.GET, "/api/societies/$societyId/resolutions?outcome=deferred")
            .andExpect(jsonPath("$[0].results").doesNotExist())
        call(guard, HttpMethod.GET, "/api/societies/$societyId/resolutions").andExpect(status().isForbidden)
    }
}
