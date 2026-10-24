package org.devvikram.societymanagement.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.NOT_FOUND, ex.message, request)

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun handleBadRequest(ex: RuntimeException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, ex.message, request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return respond(HttpStatus.BAD_REQUEST, message, request)
    }

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(ex: BadCredentialsException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.UNAUTHORIZED, "Invalid phone or password", request)

    // Catch-all for the rest: missing/invalid token (via SecurityConfig's entry point), disabled account at login, etc.
    @ExceptionHandler(AuthenticationException::class)
    fun handleUnauthenticated(ex: AuthenticationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.UNAUTHORIZED, "Authentication required", request)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.FORBIDDEN, ex.message, request)

    private fun respond(status: HttpStatus, message: String?, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(
            ErrorResponse(status = status.value(), error = status.reasonPhrase, message = message, path = request.requestURI)
        )
}
