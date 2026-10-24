package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.user.application.AppUserService
import org.devvikram.societymanagement.user.application.AssignRoleCommand
import org.devvikram.societymanagement.user.application.CreateAppUserCommand
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.infrastructure.AppUserJpaRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// The container's forward to /error only happens on a real server — MockMvc never performs it — so
// this runs on a random port with a plain HTTP client. Not @Transactional (the server answers on its
// own threads), so the user it creates is deleted afterwards.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorDispatchIntegrationTest {

    @LocalServerPort var port = 0
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var appUserService: AppUserService
    @Autowired lateinit var appUserJpaRepository: AppUserJpaRepository

    private val http = HttpClient.newHttpClient()
    private lateinit var user: AppUser

    @BeforeEach
    fun setUp() {
        user = appUserService.createUser(
            CreateAppUserCommand(
                name = "Error dispatch user",
                phone = "9${(100000000..999999999).random()}",
                rawPassword = "secret123",
                role = AssignRoleCommand(roleCode = "SUPER_ADMIN"),
            )
        )
    }

    @AfterEach
    fun tearDown() {
        appUserJpaRepository.deleteById(user.id!!)
    }

    private fun get(path: String, token: String? = null): Int {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .GET().build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `an unmapped path answers 404 to an authenticated caller, not 401`() {
        assertEquals(404, get("/api/no-such-path", jwtService.generateToken(user)))
    }

    @Test
    fun `an unmapped path still answers 401 without a token`() {
        assertEquals(401, get("/api/no-such-path"))
    }
}
