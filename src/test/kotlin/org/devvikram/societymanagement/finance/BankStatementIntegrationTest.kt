package org.devvikram.societymanagement.finance

import com.jayway.jsonpath.JsonPath
import org.devvikram.societymanagement.auth.security.JwtService
import org.devvikram.societymanagement.finance.model.StatementStatus
import org.devvikram.societymanagement.finance.repository.BankStatementRepository
import org.devvikram.societymanagement.maintenance.RecordingPushConfig
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.society.service.WingService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// Bank reconciliation step R1: how a bank's CSV reads (column mapping, preview), statement periods, and
// importing their lines with duplicates and out-of-period rows skipped. Books start 2026-04-01 with bank
// 50,000. Real local Postgres, rolled back after each test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(RecordingPushConfig::class)
class BankStatementIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var wingService: WingService
    @Autowired lateinit var flatService: FlatService
    @Autowired lateinit var bankStatementRepository: BankStatementRepository

    private var societyId = 0L
    private var bank = 0L
    private lateinit var resident: User
    private lateinit var committee: User
    private lateinit var secretary: User

    // HDFC-style April statement: details above the header, a summary below.
    private val april = """
        HDFC BANK Ltd.,,,,,,
        Account No :XXXXXXXX1234,,,,,,
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/04/26,UPI-RAMESH-MAINT APR,000412345678,01/04/26,,"2,500.00","52,500.00"
        01/04/26,UPI-RAMESH-MAINT APR,000412345678,01/04/26,,"2,500.00","55,000.00"
        03/04/26,NEFT-SECURITY SERVICES,N093260001,03/04/26,"18,000.00",,"37,000.00"
        30/04/26,CHRG: SMS ALERT,,30/04/26,17.70,,"36,982.30"
        ,,,,,,
        STATEMENT SUMMARY :-,,,,,,
    """.trimIndent()

    private val mapping = """{"delimiter":",","headerRow":3,"dateColumn":1,"dateFormat":"dd/MM/yy","descriptionColumn":2,
        "referenceColumn":3,"amountStyle":"DEBIT_CREDIT","debitColumn":5,"creditColumn":6,"balanceColumn":7}"""

    private fun randomPhone() = "9${(100000000..999999999).random()}"

    private fun user(roleCode: String, flatId: Long? = null) = userService.createUser(
        CreateUserCommand(
            name = "$roleCode user",
            phone = randomPhone(),
            rawPassword = "secret123",
            role = AssignRoleCommand(roleCode = roleCode, societyId = societyId, flatId = flatId),
        )
    )

    @BeforeEach
    fun setUp() {
        societyId = societyService.createSociety("Reconciliation Test Society").id!!
        val flatId = flatService.addFlat(wingService.addWing(societyId, "A").id!!, "101").id!!
        resident = user("RESIDENT", flatId = flatId)
        committee = user("COMMITTEE")
        secretary = user("SECRETARY")

        sendAs(secretary, "POST", "/api/societies/$societyId/finance/chart").andExpect(status().isCreated)
        bank = idOf(sendAs(secretary, "POST", "/api/societies/$societyId/finance/bank-accounts",
            """{"name":"HDFC Savings","bankName":"HDFC Bank","accountType":"SAVINGS"}"""))
        sendAs(secretary, "POST", "/api/societies/$societyId/finance/start",
            """{"startDate":"2026-04-01","openings":[{"accountId":$bank,"amount":50000}]}""")
            .andExpect(status().isCreated)
    }

    private fun bearer(user: User) = "Bearer ${jwtService.generateToken(user)}"

    private fun getAs(user: User, path: String) = mockMvc.perform(get(path).header("Authorization", bearer(user)))

    private fun sendAs(user: User, method: String, path: String, body: String? = null): ResultActions {
        val builder = when (method) {
            "POST" -> post(path)
            "PUT" -> put(path)
            "PATCH" -> patch(path)
            else -> delete(path)
        }.header("Authorization", bearer(user))
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mockMvc.perform(builder)
    }

    private fun body(result: ResultActions): String = result.andReturn().response.contentAsString

    private fun idOf(result: ResultActions): Long = JsonPath.read<Number>(body(result), "$.id").toLong()

    private fun csv(text: String, name: String = "april.csv") = MockMultipartFile("file", name, "text/csv", text.toByteArray())

    private fun upload(user: User, path: String, text: String, mappingJson: String? = null): ResultActions {
        val request = multipart(path).file(csv(text)).header("Authorization", bearer(user))
        if (mappingJson != null) request.file(MockMultipartFile("mapping", "", "application/json", mappingJson.toByteArray()))
        return mockMvc.perform(request)
    }

    private fun saveMapping() =
        sendAs(secretary, "PUT", "/api/finance/bank-accounts/$bank/statement-mapping", mapping).andExpect(status().isOk)

    private fun createStatement(from: String = "2026-04-01", to: String = "2026-04-30", opening: String = "50000", closing: String = "36982.30") =
        sendAs(secretary, "POST", "/api/finance/bank-accounts/$bank/statements",
            """{"periodFrom":"$from","periodTo":"$to","openingBalance":$opening,"closingBalance":$closing}""")

    // Completing comes with matching (R2/R3); here it is set directly to test what follows a completed one.
    private fun complete(statementId: Long) {
        val statement = bankStatementRepository.findById(statementId)!!
        statement.status = StatementStatus.COMPLETED
        statement.completedAt = Instant.now()
        statement.completedBy = secretary.id
        bankStatementRepository.save(statement)
    }

    @Test
    fun `a first upload suggests the mapping, a saved mapping is used after that`() {
        getAs(secretary, "/api/finance/bank-accounts/$bank/statement-mapping")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapping").value(nullValue()))
            .andExpect(jsonPath("$.dateFormats", hasSize<Any>(12)))

        upload(secretary, "/api/finance/bank-accounts/$bank/statement-preview", april)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mappingSource").value("SUGGESTED"))
            .andExpect(jsonPath("$.delimiter").value(","))
            .andExpect(jsonPath("$.rows", hasSize<Any>(9)))
            .andExpect(jsonPath("$.mapping.headerRow").value(3))
            .andExpect(jsonPath("$.mapping.dateFormat").value("dd/MM/yy"))
            .andExpect(jsonPath("$.parsed.lineCount").value(4))
            .andExpect(jsonPath("$.parsed.credits").value(5000.0))
            .andExpect(jsonPath("$.parsed.debits").value(18017.7))
            .andExpect(jsonPath("$.parsed.firstDate").value("2026-04-01"))

        // Trying another mapping before saving it: the wrong date format reads nothing.
        upload(secretary, "/api/finance/bank-accounts/$bank/statement-preview", april,
            mapping.replace("dd/MM/yy", "yyyy-MM-dd"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mappingSource").value("GIVEN"))
            .andExpect(jsonPath("$.parsed.lineCount").value(0))
            .andExpect(jsonPath("$.parsed.errors[0].message", containsString("No row has a date")))

        saveMapping().andExpect(jsonPath("$.mapping.debitColumn").value(5))
        upload(secretary, "/api/finance/bank-accounts/$bank/statement-preview", april)
            .andExpect(jsonPath("$.mappingSource").value("SAVED"))

        sendAs(secretary, "PUT", "/api/finance/bank-accounts/$bank/statement-mapping",
            mapping.replace(""""creditColumn":6,""", ""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `importing a statement keeps every transaction once`() {
        saveMapping()
        val statement = idOf(createStatement().andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.accountName").value("HDFC Savings")))

        upload(secretary, "/api/finance/statements/$statement/imports", april)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.import.rowsImported").value(4))
            .andExpect(jsonPath("$.import.rowsDuplicate").value(0))
            .andExpect(jsonPath("$.import.rowsIgnored").value(1))
            .andExpect(jsonPath("$.import.filename").value("april.csv"))
            .andExpect(jsonPath("$.statement.lines").value(4))
            .andExpect(jsonPath("$.statement.unmatched").value(4))
            .andExpect(jsonPath("$.statement.linesClosing").value(36982.3))
            .andExpect(jsonPath("$.statement.linesDifference").value(0.0))

        // Two identical UPI payments on one day are two transactions.
        getAs(committee, "/api/finance/statements/$statement/lines")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(4)))
            .andExpect(jsonPath("$[0].credit").value(2500.0))
            .andExpect(jsonPath("$[1].credit").value(2500.0))
            .andExpect(jsonPath("$[2].debit").value(18000.0))
            .andExpect(jsonPath("$[0].reference").value("000412345678"))
            .andExpect(jsonPath("$[0].state").value("UNMATCHED"))

        // The same file again adds nothing.
        upload(secretary, "/api/finance/statements/$statement/imports", april)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("4 row(s) already imported")))

        // A wider export overlapping April: only the new April row comes in; May is outside the period.
        val wider = april.lines().take(7).joinToString("\n") + "\n" + """
            15/04/26,INT CREDIT,,15/04/26,,120.00,"37,102.30"
            02/05/26,UPI-MAY,,02/05/26,,"2,500.00","39,602.30"
        """.trimIndent()
        upload(secretary, "/api/finance/statements/$statement/imports", wider)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.import.rowsImported").value(1))
            .andExpect(jsonPath("$.import.rowsDuplicate").value(4))
            .andExpect(jsonPath("$.outsidePeriod", hasSize<Any>(1)))
            .andExpect(jsonPath("$.statement.lines").value(5))
            .andExpect(jsonPath("$.statement.linesDifference").value(-120.0))

        getAs(secretary, "/api/finance/statements/$statement/imports")
            .andExpect(jsonPath("$", hasSize<Any>(2)))
        getAs(secretary, "/api/finance/statements/$statement/lines?state=MATCHED")
            .andExpect(jsonPath("$", hasSize<Any>(0)))
    }

    @Test
    fun `a file with unreadable rows is refused whole, and an import can be taken back out`() {
        saveMapping()
        val statement = idOf(createStatement())
        val broken = april.replace("17.70", "seventeen")
        upload(secretary, "/api/finance/statements/$statement/imports", broken)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("row 7")))
        getAs(secretary, "/api/finance/statements/$statement/lines").andExpect(jsonPath("$", hasSize<Any>(0)))

        val result = upload(secretary, "/api/finance/statements/$statement/imports", april).andExpect(status().isCreated)
        val importId = JsonPath.read<Number>(body(result), "$.import.id").toLong()
        getAs(secretary, "/api/finance/statement-imports/$importId/file")
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("UPI-RAMESH-MAINT APR")))

        sendAs(secretary, "DELETE", "/api/finance/statement-imports/$importId").andExpect(status().isNoContent)
        getAs(secretary, "/api/finance/statements/$statement/lines").andExpect(jsonPath("$", hasSize<Any>(0)))
        // Its lines are gone, so the same file can come in again.
        upload(secretary, "/api/finance/statements/$statement/imports", april).andExpect(status().isCreated)
    }

    @Test
    fun `an import needs the mapping first`() {
        val statement = idOf(createStatement())
        upload(secretary, "/api/finance/statements/$statement/imports", april)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("column mapping")))
    }

    @Test
    fun `statement periods follow each other, one open at a time`() {
        createStatement(from = "2026-03-31").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("before the books start")))
        createStatement(to = "2099-01-31").andExpect(status().isBadRequest)
        val april = idOf(createStatement().andExpect(status().isCreated))

        createStatement(from = "2026-05-01", to = "2026-05-31", opening = "36982.30", closing = "40000")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message", containsString("2026-04-01 to 2026-04-30 first")))

        complete(april)
        createStatement(from = "2026-05-02", to = "2026-05-31", opening = "36982.30", closing = "40000")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("must start on 2026-05-01")))
        createStatement(from = "2026-05-01", to = "2026-05-31", opening = "36000", closing = "40000")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("36982.30")))
        val may = idOf(createStatement(from = "2026-05-01", to = "2026-05-31", opening = "36982.30", closing = "40000")
            .andExpect(status().isCreated))

        // A completed statement can't change; an open one can, within its rules.
        sendAs(secretary, "PATCH", "/api/finance/statements/$april", """{"closingBalance":1}""")
            .andExpect(status().isConflict)
        sendAs(secretary, "PATCH", "/api/finance/statements/$may", """{"openingBalance":1}""")
            .andExpect(status().isBadRequest)
        sendAs(secretary, "PATCH", "/api/finance/statements/$may", """{"periodTo":"2026-06-30","closingBalance":41000.5}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.periodTo").value("2026-06-30"))
            .andExpect(jsonPath("$.closingBalance").value(41000.5))

        getAs(committee, "/api/societies/$societyId/finance/statements")
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].id").value(may))
        getAs(committee, "/api/societies/$societyId/finance/statements?accountId=$bank")
            .andExpect(jsonPath("$", hasSize<Any>(2)))

        sendAs(secretary, "DELETE", "/api/finance/statements/$april").andExpect(status().isConflict)
        sendAs(secretary, "DELETE", "/api/finance/statements/$may").andExpect(status().isNoContent)
        getAs(committee, "/api/societies/$societyId/finance/statements").andExpect(jsonPath("$", hasSize<Any>(1)))
    }

    @Test
    fun `lines outside a new period block the change`() {
        saveMapping()
        val statement = idOf(createStatement())
        upload(secretary, "/api/finance/statements/$statement/imports", april).andExpect(status().isCreated)
        sendAs(secretary, "PATCH", "/api/finance/statements/$statement", """{"periodTo":"2026-04-15"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("1 imported line(s)")))
    }

    @Test
    fun `the committee reads, the secretary imports, residents see nothing`() {
        saveMapping()
        val statement = idOf(createStatement())
        getAs(committee, "/api/finance/statements/$statement").andExpect(status().isOk)
        getAs(committee, "/api/finance/bank-accounts/$bank/statement-mapping").andExpect(status().isOk)
        upload(committee, "/api/finance/statements/$statement/imports", april).andExpect(status().isForbidden)
        sendAs(committee, "PUT", "/api/finance/bank-accounts/$bank/statement-mapping", mapping).andExpect(status().isForbidden)
        sendAs(committee, "POST", "/api/finance/bank-accounts/$bank/statements",
            """{"periodFrom":"2026-05-01","periodTo":"2026-05-31","openingBalance":1,"closingBalance":1}""")
            .andExpect(status().isForbidden)
        getAs(resident, "/api/finance/statements/$statement").andExpect(status().isForbidden)
        getAs(resident, "/api/societies/$societyId/finance/statements").andExpect(status().isForbidden)

        // Another society's secretary can't reach this one's statements.
        val otherSociety = societyService.createSociety("Other Society").id!!
        val outsider = userService.createUser(CreateUserCommand(name = "Outsider", phone = randomPhone(),
            rawPassword = "secret123", role = AssignRoleCommand(roleCode = "SECRETARY", societyId = otherSociety)))
        getAs(outsider, "/api/finance/statements/$statement").andExpect(status().isForbidden)
        upload(outsider, "/api/finance/statements/$statement/imports", april).andExpect(status().isForbidden)
    }

    @Test
    fun `mapping, statements and imports are in the audit log`() {
        saveMapping()
        val statement = idOf(createStatement())
        upload(secretary, "/api/finance/statements/$statement/imports", april).andExpect(status().isCreated)
        val json = body(getAs(secretary, "/api/societies/$societyId/finance/audit?entityType=RECONCILIATION")
            .andExpect(status().isOk))
        val actions = JsonPath.read<List<String>>(json, "$..action")
        assertEquals(setOf("IMPORT", "CREATE"), actions.toSet())
        val bankAudit = body(getAs(secretary, "/api/societies/$societyId/finance/audit?entityType=BANK_ACCOUNT&entityId=$bank"))
        assertEquals(true, bankAudit.contains("statementMapping"))
    }
}
