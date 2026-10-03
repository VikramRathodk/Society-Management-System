package org.devvikram.societymanagement.auth.interfaces

import jakarta.validation.Valid
import org.devvikram.societymanagement.auth.application.AuthService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse {
        val result = authService.login(request.phone, request.password)
        return LoginResponse(
            token = result.token,
            userId = result.user.id!!,
            name = result.user.name,
            role = result.user.role,
            permissions = result.permissions,
        )
    }
}
